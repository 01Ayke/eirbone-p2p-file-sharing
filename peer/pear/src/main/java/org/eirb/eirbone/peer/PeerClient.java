package org.eirb.eirbone.peer;

import org.eirb.eirbone.config.Config;
import org.eirb.eirbone.storage.FileManager;

import java.io.*;
import java.net.Socket;

public class PeerClient {

    @FunctionalInterface
    public interface ProgressListener {
        void onProgress(int downloadedPieces, int totalPieces);
    }

    private final Config      config;
    private final FileManager fileManager;

    public PeerClient(Config config, FileManager fileManager) {
        this.config      = config;
        this.fileManager = fileManager;
    }

    public void downloadFrom(String ip, int port, String filename, String md5, long fileLength)
            throws IOException {
        downloadFrom(ip, port, filename, md5, fileLength, null);
    }

    public void downloadFrom(String ip, int port, String filename, String md5, long fileLength,
                             ProgressListener listener) throws IOException {
        int nbPieces = (int) Math.ceil((double) fileLength / 1024);
        System.out.println("[INFO] Connexion Ã  " + ip + ":" + port + " pour " + filename
                           + " (" + nbPieces + " piÃ¨ces)");
        try (Socket socket = new Socket(ip, port)) {
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(socket.getInputStream()));
            PrintWriter writer = new PrintWriter(
                    new OutputStreamWriter(socket.getOutputStream()), true);
            InputStream rawIn = socket.getInputStream();

            writer.println("interested " + md5);
            String haveResponse = reader.readLine();
            System.out.println("[DEBUG] << " + haveResponse);

            StringBuilder req = new StringBuilder("getpieces " + md5 + " [");
            for (int i = 0; i < nbPieces; i++) {
                if (i > 0) req.append(" ");
                req.append(i);
            }
            req.append("]");
            writer.println(req.toString());
            System.out.println("[DEBUG] >> " + req);

            String dataHeader = reader.readLine();
            System.out.println("[DEBUG] << " + dataHeader.substring(0, Math.min(80, dataHeader.length())));

            byte[] buf = new byte[1024];
            for (int i = 0; i < nbPieces; i++) {
                readFully(rawIn, buf, 1024);
                fileManager.writePiece(md5, i, buf);
                if (listener != null) {
                    listener.onProgress(i + 1, nbPieces);
                } else if (i % Math.max(1, nbPieces / 10) == 0) {
                    System.out.printf("[INFO] Progression : %d/%d piÃ¨ces (%.0f%%)%n",
                            i + 1, nbPieces, 100.0 * (i + 1) / nbPieces);
                }
            }
            fileManager.truncate(md5, fileLength);
            fileManager.renameAfterDownload(md5, filename);
        }
    }

    private void readFully(InputStream in, byte[] buf, int length) throws IOException {
        int totalRead = 0;
        while (totalRead < length) {
            int n = in.read(buf, totalRead, length - totalRead);
            if (n < 0) throw new EOFException("Connexion fermÃ©e avant la fin des donnÃ©es");
            totalRead += n;
        }
    }
}
