package org.eirb.eirbone.peer;

import org.eirb.eirbone.server.TCPServer;
import org.eirb.eirbone.tracker.TrackerClient;
import org.eirb.eirbone.tracker.TrackerClient.FileInfo;
import org.eirb.eirbone.utils.FileInfos;
import org.eirb.eirbone.utils.FileManager;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;

/**
 * Point d'entrée principal du peer.
 * Gère :
 * - Le serveur P2P (TCPServer)
 * - La connexion au tracker
 * - Les téléchargements (DownloadManager)
 * - Le timer d'update périodique vers le tracker
 */
public class PeerSession implements AutoCloseable {

    private final int             listenPort;
    private final String          trackerHost;
    private final int             trackerPort;
    private final int             updateIntervalSec;    // depuis config
    private final int             maxConcurrentPeers;  // depuis config

    private TCPServer             server;
    private TrackerClient         tracker;
    private ScheduledExecutorService scheduler;

    private final List<FileInfos>           seededFiles  = new ArrayList<>();
    private final Map<String, DownloadManager> downloads  = new ConcurrentHashMap<>();

    public PeerSession(String trackerHost, int trackerPort,
                       int listenPort, int updateIntervalSec, int maxConcurrentPeers) {
        this.trackerHost       = trackerHost;
        this.trackerPort       = trackerPort;
        this.listenPort        = listenPort;
        this.updateIntervalSec = updateIntervalSec;
        this.maxConcurrentPeers = maxConcurrentPeers;
    }

    /** Démarre le serveur, se connecte au tracker, lance les timers. */
    public void start() throws IOException {
        // 1. Démarrer le serveur P2P
        server = new TCPServer(listenPort);
        server.start();

        // 2. Se connecter au tracker
        tracker = new TrackerClient(trackerHost, trackerPort);
        tracker.connect();

        // 3. Annoncer notre présence
        announce();

        // 4. Lancer le timer d'update périodique
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "peer-update-timer");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleAtFixedRate(this::periodicUpdate,
                updateIntervalSec, updateIntervalSec, TimeUnit.SECONDS);

        System.out.println("[PeerSession] Démarré sur port " + listenPort);
    }

    /**
     * Ajoute un fichier à partager (on est seeder).
     * Enregistre le fichier dans le serveur P2P et ré-annonce au tracker.
     */
    public void addSeedFile(String filePath, int pieceSize) throws IOException {
        FileInfos fi = FileManager.buildFileInfos(filePath, pieceSize);
        server.addFile(fi, filePath);
        seededFiles.add(fi);
        System.out.println("[PeerSession] Seed: " + fi.getFilename() + " clé=" + fi.getKey());
        announce(); // ré-annoncer avec le nouveau fichier
    }

    /**
     * Lance le téléchargement d'un fichier identifié par sa clé.
     * 1. Interroge le tracker pour trouver les peers.
     * 2. Crée un DownloadManager et lance le téléchargement.
     * 3. Une fois terminé, passe le fichier en seed.
     */
    public void download(String key, String outputDir) throws IOException {
        System.out.println("[PeerSession] Recherche des peers pour " + key + "...");
        List<String> peers = tracker.getFile(key);
        if (peers.isEmpty()) {
            System.err.println("[PeerSession] Aucun peer trouvé pour " + key);
            return;
        }

        // Récupérer les métadonnées du fichier via look
        FileInfo targetInfo = null;
        for (FileInfos fi : seededFiles) {
            if (fi.getKey().equals(key)) {
                targetInfo = new FileInfo();
                targetInfo.filename = fi.getFilename();
                targetInfo.length = fi.getLength();
                targetInfo.pieceSize = fi.getPieceSize();
                targetInfo.key = fi.getKey();
                break;
            }
        }

        if (targetInfo == null) {
            System.err.println("[PeerSession] Fichier inconnu: " + key);
            return;
        }

        FileInfos fi = new FileInfos(targetInfo.filename, targetInfo.length, targetInfo.pieceSize, targetInfo.key);
        DownloadManager dm = new DownloadManager(fi, outputDir);
        downloads.put(key, dm);

        // Manifest initial
        DownloadManifest manifest = new DownloadManifest(dm.getOutputPath());

        dm.setListener(new DownloadManager.ProgressListener() {
            @Override
            public void onPieceReceived(int idx, int total, int downloaded) {
                System.out.printf("[DL] %s : %d/%d pièces (%.1f%%)%n",
                        fi.getFilename(), downloaded, total, 100.0 * downloaded / total);
                try { manifest.save(dm, fi); } catch (IOException ignored) {}
            }
            @Override
            public void onComplete(String filePath) {
                System.out.println("[DL] Terminé : " + filePath);
                manifest.delete();
                downloads.remove(key);
                // Devenir seeder du fichier terminé
                try {
                    server.addFile(fi, filePath);
                    seededFiles.add(fi);
                    announce();
                } catch (Exception e) {
                    System.err.println("[PeerSession] Impossible de passer en seed: " + e.getMessage());
                }
            }
            @Override
            public void onError(String msg) {
                System.err.println("[DL] Erreur: " + msg);
            }
        });

        // Lancer dans un thread dédié
        Thread dlThread = new Thread(() -> dm.download(peers, maxConcurrentPeers), "dl-" + key);
        dlThread.setDaemon(true);
        dlThread.start();
    }

    /** Envoie announce au tracker avec la liste actuelle des fichiers seedés. */
    private void announce() {
        try {
            ArrayList<FileInfos> trackerFiles = new ArrayList<>(seededFiles);
            ArrayList<String> leechKeys = new ArrayList<>(downloads.keySet());
            tracker.announce(listenPort, trackerFiles, leechKeys);
        } catch (IOException e) {
            System.err.println("[PeerSession] announce échoué: " + e.getMessage());
        }
    }

    /** Appelé périodiquement : envoie update au tracker. */
    private void periodicUpdate() {
        try {
            List<String> seedKeys  = new ArrayList<>();
            for (FileInfos fi : seededFiles) seedKeys.add(fi.getKey());
            List<String> leechKeys = new ArrayList<>(downloads.keySet());
            tracker.update(seedKeys, leechKeys);
            System.out.println("[PeerSession] Update envoyé: "
                    + seedKeys.size() + " seeds, " + leechKeys.size() + " leechs");
        } catch (IOException e) {
            System.err.println("[PeerSession] update échoué: " + e.getMessage());
        }
    }

    @Override
    public void close() {
        if (scheduler != null) scheduler.shutdownNow();
        if (tracker   != null) tracker.disconnect();
        if (server    != null) server.stop();
    }
}