package org.eirb.eirbone.download;

import org.eirb.eirbone.config.Config;
import org.eirb.eirbone.peer.PeerClient;
import org.eirb.eirbone.storage.FileManager;
import org.eirb.eirbone.tracker.TrackerClient;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class DownloadManager {

    // Représente une tâche de téléchargement avec son état courant
    public static class DownloadTask {
        public final String id;
        public final String filename;
        public final String md5;
        public final long   totalSize;
        public final String peerIp;
        public final int    peerPort;
        public volatile int    downloadedPieces;
        public volatile int    totalPieces;
        public volatile String status;        // queued | downloading | done | error
        public volatile String errorMessage;
        public final long startTime;

        public DownloadTask(String id, String filename, String md5, long totalSize,
                            String peerIp, int peerPort) {
            this.id          = id;
            this.filename    = filename;
            this.md5         = md5;
            this.totalSize   = totalSize;
            this.peerIp      = peerIp;
            this.peerPort    = peerPort;
            // Nombre de pièces nécessaires (arrondi au supérieur)
            this.totalPieces = (int) Math.ceil((double) totalSize / 1024);
            this.status      = "queued";
            this.startTime   = System.currentTimeMillis();
        }

        // Retourne la progression en pourcentage (0.0 à 100.0)
        public double progressPercent() {
            if (totalPieces == 0) return status.equals("done") ? 100.0 : 0.0;
            return 100.0 * downloadedPieces / totalPieces;
        }
    }

    private final Config        config;
    private final TrackerClient tracker;
    private final FileManager   fileManager;

    // Table des tâches en cours, accessible par identifiant UUID
    private final Map<String, DownloadTask> tasks = new ConcurrentHashMap<>();

    public DownloadManager(Config config, TrackerClient tracker, FileManager fileManager) {
        this.config      = config;
        this.tracker     = tracker;
        this.fileManager = fileManager;
    }

    // Commande CLI : recherche le fichier sur le tracker et choisit automatiquement le premier peer
    public void download(String filename) {
        new Thread(() -> {
            try {
                doAutoDownload(filename);
            } catch (Exception e) {
                System.err.println("[ERROR] Téléchargement échoué : " + e.getMessage());
            }
        }, "download-" + filename).start();
    }

    // API web : démarre le téléchargement vers un peer déjà sélectionné par l'interface
    public String downloadWithPeer(String filename, String md5, long size,
                                   String peerIp, int peerPort) {
        String taskId = UUID.randomUUID().toString();
        DownloadTask task = new DownloadTask(taskId, filename, md5, size, peerIp, peerPort);
        tasks.put(taskId, task);
        new Thread(() -> executeTask(task), "dl-" + filename).start();
        return taskId;
    }

    // Retourne toutes les tâches enregistrées (terminées ou en cours)
    public Collection<DownloadTask> getTasks() {
        return tasks.values();
    }

    // -------------------------------------------------------------------------

    // Interroge le tracker pour trouver le fichier, puis lance le téléchargement
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
        System.out.println("[INFO] Tâche démarrée : " + taskId);
    }

    // Exécute une tâche de téléchargement et met à jour son statut en temps réel
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
            System.out.println("[INFO] Téléchargement terminé : " + task.filename);
        } catch (Exception e) {
            task.status       = "error";
            task.errorMessage = e.getMessage();
            System.err.println("[ERROR] Téléchargement échoué (" + task.filename + ") : " + e.getMessage());
        }
    }

    // Extrait le MD5 depuis la réponse "list [filename size pieceSize md5]"
    private String parseMd5(String response, String filename) {
        String[] t = extractTokens(response, filename);
        return t != null ? t[3] : null;
    }

    // Extrait la taille en octets depuis la réponse du tracker
    private long parseLength(String response, String filename) {
        String[] t = extractTokens(response, filename);
        if (t == null) return 0;
        try { return Long.parseLong(t[1]); } catch (NumberFormatException e) { return 0; }
    }

    // Analyse la réponse du tracker et retourne les 4 tokens si le nom de fichier correspond
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
