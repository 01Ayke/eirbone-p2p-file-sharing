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
        public volatile String status;
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
            this.totalPieces = (int) Math.ceil((double) totalSize / 1024);
            this.status      = "queued";
            this.startTime   = System.currentTimeMillis();
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

    // Commande CLI : recherche le fichier sur le tracker et télécharge depuis TOUS les peers disponibles
    public void download(String filename) {
        new Thread(() -> {
            try {
                doMultiPeerDownload(filename);
            } catch (Exception e) {
                System.err.println("[ERROR] Téléchargement échoué : " + e.getMessage());
            }
        }, "download-" + filename).start();
    }

    // API web : télécharge depuis un peer unique déjà sélectionné
    public String downloadWithPeer(String filename, String md5, long size,
                                   String peerIp, int peerPort) {
        String taskId = UUID.randomUUID().toString();
        DownloadTask task = new DownloadTask(taskId, filename, md5, size, peerIp, peerPort);
        tasks.put(taskId, task);
        new Thread(() -> {
            tracker.addLeech(md5);
            notifyTracker();
            try {
                executeTask(task);
            } finally {
                tracker.removeLeech(md5);
                notifyTracker();
            }
        }, "dl-" + filename).start();
        return taskId;
    }

    public Collection<DownloadTask> getTasks() {
        return tasks.values();
    }

    // -------------------------------------------------------------------------

    /**
     * Téléchargement multi-peers :
     *   1. Déclare le fichier en leech auprès du tracker.
     *   2. Interroge le tracker pour obtenir TOUS les peers qui possèdent le fichier.
     *   3. Pour chaque peer, télécharge les pièces manquantes sur une connexion persistante.
     *   4. Après chaque batch, diffuse le buffermap mis à jour à tous les peers connectés.
     *   5. Finalise (truncate + rename) une seule fois quand toutes les pièces sont reçues.
     *   6. Retire le fichier de la liste leech et met à jour le tracker.
     */
    private void doMultiPeerDownload(String filename) throws IOException {
        System.out.println("[INFO] Recherche de " + filename + " sur le tracker...");
        String lookResponse = tracker.look(filename);
        String md5    = parseMd5(lookResponse, filename);
        long   length = parseLength(lookResponse, filename);
        if (md5 == null) {
            System.err.println("[ERROR] Fichier introuvable sur le tracker : " + filename);
            return;
        }

        List<TrackerClient.PeerAddress> peers = tracker.getFile(md5);
        if (peers.isEmpty()) {
            System.err.println("[ERROR] Aucun peer disponible pour " + filename);
            return;
        }

        int totalPieces = (int) Math.ceil((double) length / 1024);
        Set<Integer> owned = ConcurrentHashMap.newKeySet();

        // Signale au tracker que ce peer est leecher de ce fichier
        tracker.addLeech(md5);
        notifyTracker();

        System.out.println("[INFO] " + peers.size() + " peer(s) disponible(s) pour " + filename
            + " — " + totalPieces + " pièce(s) à récupérer");

        PeerClient client = new PeerClient(config, fileManager);
        try {
            for (TrackerClient.PeerAddress peer : peers) {
                if (owned.size() >= totalPieces) break;

                System.out.println("[INFO] Tentative depuis " + peer.ip + ":" + peer.port
                    + " — pièces manquantes : " + (totalPieces - owned.size()));
                try {
                    List<Integer> downloaded = client.fetchPiecesFrom(
                        peer.ip, peer.port, md5, length, owned);
                    owned.addAll(downloaded);
                    System.out.println("[INFO] " + downloaded.size() + " pièce(s) récupérées depuis "
                        + peer.ip + ":" + peer.port
                        + " — progression : " + owned.size() + "/" + totalPieces);
                    if (!downloaded.isEmpty()) {
                        // Informer tous les peers connectés du buffermap mis à jour
                        client.broadcastHave(md5, owned, totalPieces);
                    }
                } catch (IOException e) {
                    System.err.println("[WARN] Peer " + peer.ip + ":" + peer.port
                        + " inaccessible : " + e.getMessage());
                }
            }
        } finally {
            // Ferme toutes les connexions TCP persistantes
            client.closeAll();
            // Retire le fichier de la liste leech, quel que soit le résultat
            tracker.removeLeech(md5);
            notifyTracker();
        }

        if (owned.size() < totalPieces) {
            System.err.println("[ERROR] Téléchargement incomplet : "
                + owned.size() + "/" + totalPieces + " pièces obtenues");
            return;
        }

        // Finalisation : supprime le padding et renomme le fichier temporaire
        fileManager.truncate(md5, length);
        fileManager.renameAfterDownload(md5, filename);
        System.out.println("[INFO] ✓ Téléchargement terminé : " + filename
            + " (" + owned.size() + "/" + totalPieces + " pièces, "
            + length + " octets)");
    }

    // Exécute une tâche de téléchargement depuis un unique peer (chemin API web)
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
            System.err.println("[ERROR] Téléchargement échoué (" + task.filename + ") : "
                + e.getMessage());
        }
    }

    /** Envoie une mise à jour au tracker (silencieux si non connecté ou erreur réseau). */
    private void notifyTracker() {
        try {
            tracker.sendUpdate();
        } catch (Exception e) {
            System.err.println("[WARN] Mise à jour tracker : " + e.getMessage());
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
