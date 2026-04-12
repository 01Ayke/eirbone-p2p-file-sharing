package org.eirb.eirbone.download;

import org.eirb.eirbone.config.Config;
import org.eirb.eirbone.peer.PeerClient;
import org.eirb.eirbone.storage.FileManager;
import org.eirb.eirbone.tracker.TrackerClient;

import java.io.IOException;
import java.util.List;

public class DownloadManager {

    private final Config        config;
    private final TrackerClient tracker;
    private final FileManager   fileManager;

    public DownloadManager(Config config, TrackerClient tracker, FileManager fileManager) {
        this.config      = config;
        this.tracker     = tracker;
        this.fileManager = fileManager;
    }

    // lance le téléchargement d'un fichier identifié par son nom
    public void download(String filename) {
        // on lance le téléchargement dans un thread séparé pour ne pas
        // bloquer la CLI pendant le transfert
        new Thread(() -> {
            try {
                doDownload(filename);
            } catch (Exception e) {
                System.err.println("[ERROR] Téléchargement échoué : " + e.getMessage());
            }
        }, "download-" + filename).start();
    }

    private void doDownload(String filename) throws IOException {
        System.out.println("[INFO] Recherche de " + filename + " sur le tracker...");

        // look
        String lookResponse = tracker.look(filename);
        String md5    = parseMd5FromLookResponse(lookResponse, filename);
        long   length = parseLengthFromLookResponse(lookResponse, filename);
        if (md5 == null) {
            System.err.println("[ERROR] Fichier introuvable sur le réseau : " + filename);
            return;
        }
        System.out.println("[INFO] Fichier trouvé : md5=" + md5 + " taille=" + length + " octets");

        // getfile
        List<TrackerClient.PeerAddress> peers = tracker.getFile(md5);
        if (peers.isEmpty()) {
            System.err.println("[ERROR] Aucun peer disponible pour " + filename);
            return;
        }
        System.out.println("[INFO] " + peers.size() + " peer(s) disponible(s) : " + peers);
        
        // se connecter au premier peer et télécharger
        TrackerClient.PeerAddress peer = peers.get(0);
        PeerClient client = new PeerClient(config, fileManager);
        client.downloadFrom(peer.ip, peer.port, filename, md5, length);
        System.out.println("[INFO] Téléchargement terminé : " + filename);
    }

    // extrait le MD5 depuis "list [nom taille psize md5]"
    private String parseMd5FromLookResponse(String response, String filename) {
        if (response == null || !response.startsWith("list [")) return null;
        int start = response.indexOf('[') + 1;
        int end   = response.indexOf(']');
        if (start >= end) return null;
        String[] tokens = response.substring(start, end).trim().split(" ");
        if (tokens.length >= 4 && tokens[0].equals(filename)) {
            return tokens[3];
        }
        return null;
    }

    // extrait la taille depuis "list [nom taille psize md5]"
    private long parseLengthFromLookResponse(String response, String filename) {
        if (response == null || !response.startsWith("list [")) return 0;
        int start = response.indexOf('[') + 1;
        int end = response.indexOf(']');
        if (start >= end) return 0;

        String[] tokens = response.substring(start, end).trim().split(" ");
        if (tokens.length >= 4 && tokens[0].equals(filename)) {
            return Long.parseLong(tokens[1]);
        }
        return 0;
    }
}