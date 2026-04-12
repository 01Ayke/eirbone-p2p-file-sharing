package org.eirb.eirbone.peer;

import org.eirb.eirbone.config.Config;
import org.eirb.eirbone.storage.FileManager;

import java.io.*;
import java.net.*;
import java.util.Base64;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class PeerServer implements Runnable {

    private final Config      config;
    private final FileManager fileManager;
    private final ExecutorService threadPool = Executors.newFixedThreadPool(10);

    public PeerServer(Config config, FileManager fileManager) {
        this.config      = config;
        this.fileManager = fileManager;
    }

    @Override
    public void run() {
try (ServerSocket serverSocket = new ServerSocket(config.peerPort)) {
        System.out.println("[INFO] PeerServer en écoute sur le port " + config.peerPort);
            while (!Thread.currentThread().isInterrupted()) {
                Socket clientSocket = serverSocket.accept();
                threadPool.submit(() -> handlePeer(clientSocket));
            }
        } catch (IOException e) {
            System.err.println("[ERROR] PeerServer : " + e.getMessage());
        }
    }

    // gère la communication avec un peer connecté
    private void handlePeer(Socket socket) {
        String peerAddr = socket.getInetAddress().getHostAddress() + ":" + socket.getPort();
        System.out.println("[INFO] Peer connecté : " + peerAddr);
        try (
            BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            OutputStream   out    = socket.getOutputStream();
            PrintWriter    writer = new PrintWriter(out, true)
        ) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                System.out.println("[DEBUG] " + peerAddr + " >> " + line);
                if (line.startsWith("interested ")) {
                    handleInterested(line, writer);
                } else if (line.startsWith("getpieces ")) {
                    handleGetPieces(line, writer, out);
                } else if (line.startsWith("have ")) {
                    System.out.println("[DEBUG] have reçu, ignoré pour l'instant");
                } else {
                    System.out.println("[WARN] Commande inconnue ignorée : " + line);
                }
            }
        } catch (IOException e) {
            System.out.println("[INFO] Peer déconnecté : " + peerAddr);
        }
    }

    // répond à interested <md5> 
    // avec have <md5> <buffermap_base64>
    private void handleInterested(String line, PrintWriter writer) throws IOException {
        String md5 = line.substring("interested ".length()).trim();
        FileManager.FileInfo fileInfo = findFileByMd5(md5);
        if (fileInfo == null) {
            writer.println("have " + md5 + " " + encodedEmptyBuffermap(0));
            return;
        }
        int nbPieces = (int) Math.ceil((double) fileInfo.length / 1024);
        int nbBytes = (nbPieces + 7) / 8;
        byte[] buffermap = new byte[nbBytes];
        for (int i = 0; i < nbPieces; i++) {
            buffermap[i / 8] |= (byte) (1 << (7 - (i % 8)));
        }
        String encoded = Base64.getEncoder().encodeToString(buffermap);
        writer.println("have " + md5 + " " + encoded);
        System.out.println("[INFO] Intéressé par " + fileInfo.filename + " → buffermap envoyé (" + nbPieces + " pièces)");
    }

    // réponse à getpieces <md5> [3 5 7]
    //  avec le header et les données binaires
    private void handleGetPieces(String line, PrintWriter writer, OutputStream out) throws IOException {
        int spaceAfterCmd = line.indexOf(' ');
        int spaceAfterMd5 = line.indexOf(' ', spaceAfterCmd + 1);
        String md5 = line.substring(spaceAfterCmd + 1, spaceAfterMd5);
        int start = line.indexOf('[');
        int end = line.indexOf(']');
        String res = line.substring(start + 1, end).trim();
        String[] parts = res.split(" ");
        FileManager.FileInfo fileInfo = findFileByMd5(md5);
        if (fileInfo == null) {
            writer.println("error file not found");
            return;
        }

        StringBuilder header = new StringBuilder("data " + md5 + " [");
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) header.append(" ");
            header.append(parts[i]).append(" 1024");
        }
        header.append("]");
        writer.println(header.toString());
        System.out.println("[DEBUG] << " + header);
        for (String pieceIndexStr : parts) {
            int pieceIndex = Integer.parseInt(pieceIndexStr.trim());
            byte[] pieceData = fileManager.readPiece(fileInfo.filename, pieceIndex);
            out.write(pieceData);
        }
        out.flush();
        System.out.println("[INFO] " + parts.length + " pièce(s) envoyée(s) pour " + fileInfo.filename);
    }

    // cherche dans shared/ le fichier dont le md5 correspond
    private FileManager.FileInfo findFileByMd5(String md5) throws IOException {
        for (FileManager.FileInfo fi : fileManager.listSharedFiles()) {
            if (fi.md5.equals(md5)) return fi;
        }
        return null;
    }

    private String encodedEmptyBuffermap(int nbBytes) {
        return Base64.getEncoder().encodeToString(new byte[nbBytes]);
    }
}