package org.eirb.eirbone.peer;

import org.eirb.eirbone.config.Config;
import org.eirb.eirbone.storage.FileManager;

import java.io.*;
import java.net.Socket;
import java.util.Base64;

public class PeerClient {

    private final Config      config;
    private final FileManager fileManager;

    public PeerClient(Config config, FileManager fileManager) {
        this.config      = config;
        this.fileManager = fileManager;
    }

    // se connecter à un peer et télécharger toutes les pièces du fichier
    public void downloadFrom(String ip, int port, String filename, String md5, long fileLength)
            throws IOException {

        int nbPieces = (int) Math.ceil((double) fileLength / 1024);
        System.out.println("[INFO] Connexion à " + ip + ":" + port + " pour " + filename
                           + " (" + nbPieces + " pièces)");
        try (Socket socket = new Socket(ip, port)) {
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(socket.getInputStream()));
            PrintWriter writer = new PrintWriter(
                    new OutputStreamWriter(socket.getOutputStream()), true);
            InputStream rawIn = socket.getInputStream();

            // envoyer interested <md5>
            writer.println("interested " + md5);
            String haveResponse = reader.readLine();
            System.out.println("[DEBUG] << " + haveResponse);

            // demander toutes les pièces
            StringBuilder req = new StringBuilder("getpieces " + md5 + " [");
            for (int i = 0; i < nbPieces; i++) {
                if (i > 0) req.append(" ");
                req.append(i);
            }
            req.append("]");
            writer.println(req.toString());
            System.out.println("[DEBUG] >> " + req);

            // lire le header de réponse
            String dataHeader = reader.readLine();
            System.out.println("[DEBUG] << " + dataHeader.substring(0, Math.min(80, dataHeader.length())));

            // lire les données binaires
            byte[] buf = new byte[1024];
            for (int i = 0; i < nbPieces; i++) {
                readFully(rawIn, buf, 1024);
                fileManager.writePiece(filename, i, buf);
                if (i % Math.max(1, nbPieces / 10) == 0) { // afficher progression tous les 10%
                    System.out.printf("[INFO] Progression : %d/%d pièces (%.0f%%)%n",
                            i + 1, nbPieces, 100.0 * (i + 1) / nbPieces);
                }
            }

            // tronquer le fichier à sa vraie taille
            fileManager.truncate(filename, fileLength);
        }
    }

    // lit exactement length octets depuis l'InputStream et les stocke dans buf
    private void readFully(InputStream in, byte[] buf, int length) throws IOException {
        int totalRead = 0;
        while (totalRead < length) {
            int n = in.read(buf, totalRead, length - totalRead);
            if (n < 0) throw new EOFException("Connexion fermée avant la fin des données");
            totalRead += n;
        }
    }
}