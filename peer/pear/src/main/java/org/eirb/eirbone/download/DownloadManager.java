package org.eirb.eirbone.download;

import org.eirb.eirbone.config.Config;
import org.eirb.eirbone.peer.PeerClient;
import org.eirb.eirbone.storage.FileManager;
import org.eirb.eirbone.tracker.TrackerClient;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class DownloadManager {

    public static class DownloadTask {
        public final String id;
        public final String filename;
        public final String md5;
        public final long   totalSize;
        public final String peerIp;
        public final int    peerPort;
        public volatile int    downloadedPieces;
        public volatile int    totalPieces;
        public volatile String status;       // queued | downloading | done | error
        public volatile String errorMessage;
        public final long startTime;

        public DownloadTask(String id, String filename, String md5, long totalSize,
                            String peerIp, int peerPort) {
            this.id           = id;
            this.filename     = filename;
            this.md5          = md5;
            this.totalSize    = totalSize;
            this.peerIp       = peerIp;
            this.peerPort     = peerPort;
            this.totalPieces  = (int) Math.ceil((double) totalSize / 1024);
            this.status       = "queued";
            this.startTime    = System.currentTimeMillis();
        }

        public double progressPercent() {
            if (totalPieces == 0) return status.equals("done") ? 100.0 : 0.0;
            return 100.0 * downloadedPieces / totalPieces;
        }
    }

    private final Config        config;
    private final TrackerClient tracker;
    private final FileManager   fileManager;

    private final Map<String, DownloadTask> tasks = new ConcurrentHashMap<>();

    public DownloadManager(Config config, TrackerClient tracker, FileManager fileManager) {
        this.config      = config;
        this.tracker     = tracker;
        this.fileManager = fileManager;
    }

    // CLI: download by filename â€” auto-selects first available peer
    public void download(String filename) {
        new Thread(() -> {
            try {
                doAutoDownload(filename);
            } catch (Exception e) {
                System.err.println("[ERROR] TÃ©lÃ©chargement Ã©chouÃ© : " + e.getMessage());
            }
        }, "download-" + filename).start();
    }

    // Web API: download with a specific peer already chosen
    public String downloadWithPeer(String filename, String md5, long size,
                                   String peerIp, int peerPort) {
        String taskId = UUID.randomUUID().toString();
        DownloadTask task = new DownloadTask(taskId, filename, md5, size, peerIp, peerPort);
        tasks.put(taskId, task);
        new Thread(() -> executeTask(task), "dl-" + filename).start();
        return taskId;
    }

    public Collection<DownloadTask> getTasks() {
        return tasks.values();
    }

    // -------------------------------------------------------------------------

    private void doAutoDownload(String filename) throws IOException {
        System.out.println("[INFO] Recherche de " + filename + " sur le tracker...");
        String lookResponse = tracker.look(filename);
        String md5    = parseMd5(lookResponse, filename);
        long   length = parseLength(lookResponse, filename);
        if (md5 == null) {
            System.err.println("[ERROR] Fichier introuvable : " + filename);
            return;
        }
        List<TrackerClient.PeerAddress> peers = tracker.getFile(md5);
        if (peers.isEmpty()) {
            System.err.println("[ERROR] Aucun peer disponible pour " + filename);
            return;
        }
        TrackerClient.PeerAddress peer = peers.get(0);
        String taskId = downloadWithPeer(filename, md5, length, peer.ip, peer.port);
        System.out.println("[INFO] TÃ¢che dÃ©marrÃ©e : " + taskId);
    }

    private void executeTask(DownloadTask task) {
        task.status = "downloading";
        try {
            PeerClient client = new PeerClient(config, fileManager);
            client.downloadFrom(task.peerIp, task.peerPort, task.filename, task.md5,
                    task.totalSize, (done, total) -> {
                        task.downloadedPieces = done;
                        task.totalPieces      = total;
                    });
            task.downloadedPieces = task.totalPieces;
            task.status = "done";
            System.out.println("[INFO] TÃ©lÃ©chargement terminÃ© : " + task.filename);
        } catch (Exception e) {
            task.status       = "error";
            task.errorMessage = e.getMessage();
            System.err.println("[ERROR] TÃ©lÃ©chargement Ã©chouÃ© (" + task.filename + ") : " + e.getMessage());
        }
    }

    private String parseMd5(String response, String filename) {
        String[] t = extractTokens(response, filename);
        return t != null ? t[3] : null;
    }

    private long parseLength(String response, String filename) {
        String[] t = extractTokens(response, filename);
        if (t == null) return 0;
        try { return Long.parseLong(t[1]); } catch (NumberFormatException e) { return 0; }
    }

    private String[] extractTokens(String response, String filename) {
        if (response == null || !response.startsWith("list [")) return null;
        int start = response.indexOf('[') + 1;
        int end   = response.indexOf(']');
        if (start >= end) return null;
        String[] tokens = response.substring(start, end).trim().split(" ");
        if (tokens.length >= 4 && tokens[0].equals(filename)) return tokens;
        return null;
    }
}
