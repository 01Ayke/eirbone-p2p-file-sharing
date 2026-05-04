package org.eirb.eirbone.peer;

import org.eirb.eirbone.config.Config;
import org.eirb.eirbone.storage.FileManager;

import java.io.*;
import java.net.*;
import java.util.Base64;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class PeerServer implements Runnable {

    private final Config           config;
    private final FileManager      fileManager;
    private final ExecutorService  threadPool = Executors.newFixedThreadPool(10);

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

    private void handlePeer(Socket socket) {
        String peerAddr = socket.getInetAddress().getHostAddress() + ":" + socket.getPort();
        System.out.println("[INFO] Peer connecté : " + peerAddr);
        int malformedConsecutive = 0;
        try (
            Socket         ignored = socket;   // garantit socket.close() en sortie
            BufferedReader reader  = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            OutputStream   out     = socket.getOutputStream();
            PrintWriter    writer  = new PrintWriter(out, true)
        ) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;
                System.out.println("[DEBUG] " + peerAddr + " >> " + line);
                if (line.startsWith("interested ")) {
                    malformedConsecutive = 0;
                    handleInterested(line, writer);
                } else if (line.startsWith("getpieces ")) {
                    malformedConsecutive = 0;
                    handleGetPieces(line, writer, out);
                } else if (line.startsWith("have ")) {
                    malformedConsecutive = 0;
                    handleHave(line, writer, peerAddr);
                } else {
                    malformedConsecutive++;
                    writer.println("error malformed message");
                    System.out.println("[WARN] Message malformé (" + malformedConsecutive
                        + "/3) de " + peerAddr + " : " + line);
                    if (malformedConsecutive >= 3) {
                        System.out.println("[WARN] 3 messages malformés consécutifs — "
                            + "fermeture de la connexion avec " + peerAddr);
                        break;
                    }
                }
            }
        } catch (IOException e) {
            System.out.println("[INFO] Peer déconnecté : " + peerAddr);
        }
    }

    /**
     * Répond à "interested <md5>" avec "have <md5> <buffermap_base64>".
     * Seuls les bits des pièces réellement possédées sont à 1 (fichier partiel ou complet).
     */
    private void handleInterested(String line, PrintWriter writer) throws IOException {
        String md5 = line.substring("interested ".length()).trim();
        FileManager.FileInfo fileInfo = findFileByMd5(md5);
        if (fileInfo == null) {
            writer.println("have " + md5 + " " + Base64.getEncoder().encodeToString(new byte[0]));
            return;
        }

        int nbPieces = (int) Math.ceil((double) fileInfo.length / 1024);
        int nbBytes  = (nbPieces + 7) / 8;
        byte[] buffermap = new byte[nbBytes];

        Set<Integer> owned = fileManager.getOwnedPieces(fileInfo.filename, fileInfo.length);
        for (int i : owned) {
            if (i < nbPieces) {
                buffermap[i / 8] |= (byte) (1 << (7 - (i % 8)));
            }
        }

        String encoded = Base64.getEncoder().encodeToString(buffermap);
        writer.println("have " + md5 + " " + encoded);
        System.out.println("[INFO] Intéressé par " + fileInfo.filename
            + " → buffermap envoyé (pièces disponibles : " + owned + ")");
    }

    /**
     * Répond à "getpieces <md5> [3 5 7]" : envoie l'en-tête puis les données binaires.
     * Ne sert que les pièces présentes dans le manifeste (ou toutes si fichier complet).
     */
    private void handleGetPieces(String line, PrintWriter writer, OutputStream out) throws IOException {
        int spaceAfterCmd = line.indexOf(' ');
        int spaceAfterMd5 = line.indexOf(' ', spaceAfterCmd + 1);
        String md5   = line.substring(spaceAfterCmd + 1, spaceAfterMd5);
        int    start = line.indexOf('[');
        int    end   = line.indexOf(']');
        String[] parts = line.substring(start + 1, end).trim().split(" ");

        FileManager.FileInfo fileInfo = findFileByMd5(md5);
        if (fileInfo == null) {
            writer.println("error file not found");
            return;
        }

        Set<Integer> owned = fileManager.getOwnedPieces(fileInfo.filename, fileInfo.length);

        // En-tête data
        StringBuilder header = new StringBuilder("data " + md5 + " [");
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) header.append(" ");
            header.append(parts[i]).append(" 1024");
        }
        header.append("]");
        writer.println(header.toString());
        System.out.println("[DEBUG] << " + header);

        // Données binaires — zéros si une pièce n'est pas disponible (ne devrait pas arriver)
        for (String pieceIndexStr : parts) {
            int pieceIndex = Integer.parseInt(pieceIndexStr.trim());
            if (!owned.contains(pieceIndex)) {
                System.err.println("[WARN] Pièce " + pieceIndex + " demandée mais absente du manifeste !");
                out.write(new byte[1024]);
                continue;
            }
            byte[] pieceData = fileManager.readPiece(fileInfo.filename, pieceIndex);
            out.write(pieceData);
        }
        out.flush();
        System.out.println("[INFO] " + parts.length + " pièce(s) envoyée(s) pour " + fileInfo.filename);
    }

    /**
     * Reçoit "have <md5> <buffermap_base64>" d'un peer.
     * Répond avec notre propre buffermap pour le même fichier.
     */
    private void handleHave(String line, PrintWriter writer, String peerAddr) throws IOException {
        String[] parts = line.split(" ", 3);
        if (parts.length < 3) return;
        String md5       = parts[1];
        String buffermap = parts[2];
        System.out.println("[INFO] Buffermap reçu de " + peerAddr
            + " pour " + md5 + " : " + buffermap);

        FileManager.FileInfo fileInfo = findFileByMd5(md5);
        if (fileInfo == null) {
            writer.println("have " + md5 + " " + Base64.getEncoder().encodeToString(new byte[0]));
            return;
        }

        int nbPieces = (int) Math.ceil((double) fileInfo.length / 1024);
        int nbBytes  = (nbPieces + 7) / 8;
        byte[] bm    = new byte[nbBytes];

        Set<Integer> owned = fileManager.getOwnedPieces(fileInfo.filename, fileInfo.length);
        for (int i : owned) {
            if (i < nbPieces) bm[i / 8] |= (byte) (1 << (7 - (i % 8)));
        }

        String encoded = Base64.getEncoder().encodeToString(bm);
        writer.println("have " + md5 + " " + encoded);
        System.out.println("[INFO] Réponse have envoyée à " + peerAddr
            + " pour " + md5 + " (" + owned.size() + "/" + nbPieces + " pièces)");
    }

    private FileManager.FileInfo findFileByMd5(String md5) throws IOException {
        for (FileManager.FileInfo fi : fileManager.listSharedFiles()) {
            if (fi.md5.equals(md5)) return fi;
        }
        return null;
    }
}
