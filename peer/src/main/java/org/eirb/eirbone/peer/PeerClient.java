package org.eirb.eirbone.peer;

import org.eirb.eirbone.config.Config;
import org.eirb.eirbone.storage.FileManager;

import java.io.*;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class PeerClient {

    @FunctionalInterface
    public interface ProgressListener {
        void onProgress(int piecesRecues, int piecesTotal);
    }

    /**
     * Connexion TCP persistante vers un peer.
     * Utilise DataInputStream + BufferedInputStream pour éviter que le BufferedReader
     * ne consomme des octets binaires destinés aux pièces.
     */
    private static class PeerConn {
        final Socket          socket;
        final DataInputStream din;
        final PrintWriter     writer;

        PeerConn(String ip, int port) throws IOException {
            socket = new Socket(ip, port);
            din    = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
            writer = new PrintWriter(new OutputStreamWriter(socket.getOutputStream()), true);
        }

        /** Lit une ligne ASCII depuis le flux partagé (sans consommer de données binaires). */
        String readLine() throws IOException {
            StringBuilder sb = new StringBuilder();
            int b;
            while ((b = din.read()) != -1) {
                if (b == '\n') break;
                if (b != '\r') sb.append((char) b);
            }
            if (b == -1 && sb.length() == 0) throw new EOFException("Connexion fermée par le peer");
            return sb.toString();
        }

        /** Lit exactement buf.length octets binaires depuis le flux partagé. */
        void readFully(byte[] buf) throws IOException {
            din.readFully(buf);
        }

        boolean isOpen() { return !socket.isClosed() && socket.isConnected(); }

        void close() { try { socket.close(); } catch (Exception ignored) {} }
    }

    private final Config      config;
    private final FileManager fileManager;

    // Pool de connexions TCP persistantes (keep-alive) — une par pair "ip:port"
    private final Map<String, PeerConn> pool = new ConcurrentHashMap<>();

    public PeerClient(Config config, FileManager fileManager) {
        this.config      = config;
        this.fileManager = fileManager;
    }

    // -------------------------------------------------------------------------
    // API publique
    // -------------------------------------------------------------------------

    // Télécharge un fichier complet depuis un unique peer (chemin API web)
    public void downloadFrom(String ip, int port, String filename, String md5, long fileLength)
            throws IOException {
        downloadFrom(ip, port, filename, md5, fileLength, null);
    }

    public void downloadFrom(String ip, int port, String filename, String md5, long fileLength,
                             ProgressListener listener) throws IOException {
        int nbPieces = (int) Math.ceil((double) fileLength / 1024);
        System.out.println("[INFO] Connexion à " + ip + ":" + port + " pour " + filename
                           + " (" + nbPieces + " pièces)");
        try (Socket socket = new Socket(ip, port)) {
            DataInputStream din = new DataInputStream(
                    new BufferedInputStream(socket.getInputStream()));
            PrintWriter writer = new PrintWriter(
                    new OutputStreamWriter(socket.getOutputStream()), true);

            // Étape 1 : interested → buffermap
            writer.println("interested " + md5);
            String haveResponse = readLineFrom(din);
            System.out.println("[DEBUG] << " + haveResponse);

            List<Integer> available = decodePieces(haveResponse, nbPieces);
            if (available.isEmpty()) {
                System.out.println("[WARN] Le peer " + ip + ":" + port
                    + " ne possède aucune pièce de " + filename);
                return;
            }
            System.out.println("[INFO] Pièces disponibles chez " + ip + ":" + port
                + " → " + available);

            // Étape 2 : getpieces avec les pièces disponibles selon le buffermap
            StringBuilder req = new StringBuilder("getpieces " + md5 + " [");
            for (int k = 0; k < available.size(); k++) {
                if (k > 0) req.append(" ");
                req.append(available.get(k));
            }
            req.append("]");
            writer.println(req.toString());
            System.out.println("[DEBUG] >> " + req);

            // Étape 3 : en-tête data puis données binaires
            String dataHeader = readLineFrom(din);
            System.out.println("[DEBUG] << " + dataHeader.substring(0, Math.min(80, dataHeader.length())));

            byte[] buf = new byte[1024];
            for (int k = 0; k < available.size(); k++) {
                int pieceIndex = available.get(k);
                din.readFully(buf);
                fileManager.writePiece(md5, pieceIndex, buf);
                if (listener != null) {
                    listener.onProgress(k + 1, available.size());
                } else if (k % Math.max(1, available.size() / 10) == 0) {
                    System.out.printf("[INFO] Progression : pièce %d reçue (%d/%d — %.0f%%)%n",
                            pieceIndex, k + 1, available.size(), 100.0 * (k + 1) / available.size());
                }
            }
            // Étape 4 : retire le padding et renomme
            fileManager.truncate(md5, fileLength);
            fileManager.renameAfterDownload(md5, filename);
        }
    }

    /**
     * Télécharge depuis un peer les pièces non encore possédées, sur une connexion persistante.
     * NE renomme PAS et NE tronque PAS — la finalisation est faite par DownloadManager.
     * La connexion reste ouverte dans le pool pour réutilisation (keep-alive).
     *
     * @param alreadyOwned ensemble des indices déjà possédés (mis à jour par l'appelant)
     * @return             liste des indices nouvellement téléchargés
     */
    public List<Integer> fetchPiecesFrom(String ip, int port, String md5, long fileLength,
                                          Set<Integer> alreadyOwned) throws IOException {
        int nbPieces = (int) Math.ceil((double) fileLength / 1024);
        List<Integer> downloaded = new ArrayList<>();

        PeerConn conn = acquireConn(ip, port);
        try {
            // Intérêt → buffermap
            conn.writer.println("interested " + md5);
            String haveResponse = conn.readLine();
            System.out.println("[DEBUG] << " + haveResponse);

            List<Integer> available = decodePieces(haveResponse, nbPieces);

            // Seules les pièces disponibles ET pas encore possédées
            List<Integer> toRequest = new ArrayList<>();
            for (int idx : available) {
                if (!alreadyOwned.contains(idx)) toRequest.add(idx);
            }

            if (toRequest.isEmpty()) {
                System.out.println("[INFO] Aucune nouvelle pièce disponible chez "
                    + ip + ":" + port + " (disponibles=" + available
                    + ", déjà possédées=" + alreadyOwned + ")");
                return downloaded;
            }
            System.out.println("[INFO] Pièces à récupérer depuis " + ip + ":" + port
                + " → " + toRequest);

            // Requête getpieces
            StringBuilder req = new StringBuilder("getpieces " + md5 + " [");
            for (int k = 0; k < toRequest.size(); k++) {
                if (k > 0) req.append(" ");
                req.append(toRequest.get(k));
            }
            req.append("]");
            conn.writer.println(req.toString());
            System.out.println("[DEBUG] >> " + req);

            // En-tête data
            String dataHeader = conn.readLine();
            System.out.println("[DEBUG] << " + dataHeader.substring(0, Math.min(80, dataHeader.length())));

            // Réception pièce par pièce au bon offset
            byte[] buf = new byte[1024];
            for (int k = 0; k < toRequest.size(); k++) {
                int pieceIndex = toRequest.get(k);
                conn.readFully(buf);
                fileManager.writePiece(md5, pieceIndex, buf);
                downloaded.add(pieceIndex);
                System.out.printf("[INFO] Pièce %d reçue depuis %s:%d%n", pieceIndex, ip, port);
            }
        } catch (IOException e) {
            // Connexion brisée — on l'expulse du pool
            pool.remove(ip + ":" + port);
            throw e;
        }
        return downloaded;
    }

    /**
     * Diffuse "have <md5> <buffermap>" à tous les peers actuellement connectés dans le pool.
     * Permet d'informer les seeders des pièces acquises pendant le téléchargement.
     */
    public void broadcastHave(String md5, Set<Integer> owned, int totalPieces) {
        byte[] bm      = buildBuffermap(owned, totalPieces);
        String encoded = Base64.getEncoder().encodeToString(bm);
        String msg     = "have " + md5 + " " + encoded;
        List<String> broken = new ArrayList<>();
        for (Map.Entry<String, PeerConn> entry : pool.entrySet()) {
            try {
                entry.getValue().writer.println(msg);
                System.out.println("[INFO] have envoyé à " + entry.getKey()
                    + " (" + owned.size() + "/" + totalPieces + " pièces)");
            } catch (Exception e) {
                broken.add(entry.getKey());
            }
        }
        broken.forEach(pool::remove);
    }

    /** Ferme toutes les connexions persistantes du pool. */
    public void closeAll() {
        pool.values().forEach(PeerConn::close);
        pool.clear();
        System.out.println("[INFO] Connexions TCP keep-alive fermées.");
    }

    // -------------------------------------------------------------------------
    // Méthodes privées
    // -------------------------------------------------------------------------

    /** Récupère ou crée une connexion persistante vers ip:port. */
    private PeerConn acquireConn(String ip, int port) throws IOException {
        String key = ip + ":" + port;
        PeerConn c = pool.get(key);
        if (c != null && c.isOpen()) return c;
        System.out.println("[INFO] Ouverture connexion TCP keep-alive vers " + key);
        c = new PeerConn(ip, port);
        pool.put(key, c);
        return c;
    }

    /** Lit une ligne ASCII depuis un DataInputStream sans sur-buffering. */
    private String readLineFrom(DataInputStream din) throws IOException {
        StringBuilder sb = new StringBuilder();
        int b;
        while ((b = din.read()) != -1) {
            if (b == '\n') break;
            if (b != '\r') sb.append((char) b);
        }
        if (b == -1 && sb.length() == 0) throw new EOFException("Connexion fermée par le peer");
        return sb.toString();
    }

    /** Construit le buffermap à partir d'un ensemble d'indices possédés. */
    private byte[] buildBuffermap(Set<Integer> owned, int totalPieces) {
        int nbBytes = (totalPieces + 7) / 8;
        byte[] bm = new byte[nbBytes];
        for (int i : owned) {
            if (i < totalPieces) bm[i / 8] |= (byte) (1 << (7 - (i % 8)));
        }
        return bm;
    }

    /** Décode le buffermap Base64 "have <md5> <base64>" → indices disponibles. */
    private List<Integer> decodePieces(String haveResponse, int nbPieces) {
        List<Integer> result = new ArrayList<>();
        if (haveResponse == null) return result;
        String[] parts = haveResponse.split(" ");
        if (parts.length < 3) return result;
        try {
            byte[] bm = Base64.getDecoder().decode(parts[2].trim());
            for (int i = 0; i < nbPieces; i++) {
                if ((bm[i / 8] & (1 << (7 - (i % 8)))) != 0) {
                    result.add(i);
                }
            }
        } catch (IllegalArgumentException e) {
            System.err.println("[WARN] Buffermap invalide reçu : " + parts[2]);
        }
        return result;
    }
}
