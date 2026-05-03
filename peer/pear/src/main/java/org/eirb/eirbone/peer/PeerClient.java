package org.eirb.eirbone.peer;

import org.eirb.eirbone.config.Config;
import org.eirb.eirbone.storage.FileManager;

import java.io.*;
import java.net.Socket;

public class PeerClient {

    // Interface fonctionnelle pour suivre la progression du téléchargement
    @FunctionalInterface
    public interface ProgressListener {
        void onProgress(int piecesRecues, int piecesTotal);
    }

    private final Config      config;
    private final FileManager fileManager;

    public PeerClient(Config config, FileManager fileManager) {
        this.config      = config;
        this.fileManager = fileManager;
    }

    // Télécharge un fichier depuis un peer sans suivi de progression
    public void downloadFrom(String ip, int port, String filename, String md5, long fileLength)
            throws IOException {
        downloadFrom(ip, port, filename, md5, fileLength, null);
    }

    // Télécharge un fichier depuis un peer en notifiant le listener à chaque pièce reçue
    public void downloadFrom(String ip, int port, String filename, String md5, long fileLength,
                             ProgressListener listener) throws IOException {
        int nbPieces = (int) Math.ceil((double) fileLength / 1024);
        System.out.println("[INFO] Connexion à " + ip + ":" + port + " pour " + filename
                           + " (" + nbPieces + " pièces)");
        try (Socket socket = new Socket(ip, port)) {
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(socket.getInputStream()));
            PrintWriter writer = new PrintWriter(
                    new OutputStreamWriter(socket.getOutputStream()), true);
            InputStream rawIn = socket.getInputStream();

            // Étape 1 : signale l'intérêt pour le fichier et récupère le buffermap
            writer.println("interested " + md5);
            String haveResponse = reader.readLine();
            System.out.println("[DEBUG] << " + haveResponse);

            // Étape 2 : demande toutes les pièces
            StringBuilder req = new StringBuilder("getpieces " + md5 + " [");
            for (int i = 0; i < nbPieces; i++) {
                if (i > 0) req.append(" ");
                req.append(i);
            }
            req.append("]");
            writer.println(req.toString());
            System.out.println("[DEBUG] >> " + req);

            // Étape 3 : lit l'en-tête de réponse puis les données binaires pièce par pièce
            String dataHeader = reader.readLine();
            System.out.println("[DEBUG] << " + dataHeader.substring(0, Math.min(80, dataHeader.length())));

            byte[] buf = new byte[1024];
            for (int i = 0; i < nbPieces; i++) {
                readFully(rawIn, buf, 1024);
                fileManager.writePiece(md5, i, buf);
                if (listener != null) {
                    listener.onProgress(i + 1, nbPieces);
                } else if (i % Math.max(1, nbPieces / 10) == 0) {
                    System.out.printf("[INFO] Progression : %d/%d pièces (%.0f%%)%n",
                            i + 1, nbPieces, 100.0 * (i + 1) / nbPieces);
                }
            }
            // Étape 4 : supprime le rembourrage et renomme le fichier temporaire
            fileManager.truncate(md5, fileLength);
            fileManager.renameAfterDownload(md5, filename);
        }
    }

    // Lit exactement 'length' octets depuis le flux, bloque jusqu'à avoir tout reçu
    private void readFully(InputStream in, byte[] buf, int length) throws IOException {
        int totalRead = 0;
        while (totalRead < length) {
            int n = in.read(buf, totalRead, length - totalRead);
            if (n < 0) throw new EOFException("Connexion fermée avant la fin des données");
            totalRead += n;
        }
    }
}
