package org.eirb.eirbone.peer;

import org.eirb.eirbone.utils.FileInfos;
import org.eirb.eirbone.utils.FileManager;

import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.Base64;

/**
 * Gère le téléchargement d'un fichier depuis plusieurs peers en parallèle.
 * - Maintient un buffer map local (quelles pièces on a déjà).
 * - Choisit les pièces à demander selon disponibilité chez les peers.
 * - Écrit chaque pièce reçue sur disque au bon offset.
 * - Détecte la complétion et vérifie le MD5 final.
 */
public class DownloadManager {

    private final FileInfos fileInfos;
    private final String    outputPath;
    private final boolean[] localBufferMap;   // true = pièce disponible localement
    private final int       pieceCount;
    private int             downloadedCount;
    private volatile boolean complete = false;

    // Listeners pour signaler la progression
    public interface ProgressListener {
        void onPieceReceived(int pieceIndex, int total, int downloaded);
        void onComplete(String filePath);
        void onError(String message);
    }

    private ProgressListener listener;

    public DownloadManager(FileInfos fileInfos, String outputDir) throws IOException {
        this.fileInfos  = fileInfos;
        this.pieceCount = (int) Math.ceil((double) fileInfos.getLength() / fileInfos.getPieceSize());
        this.localBufferMap = new boolean[pieceCount];
        this.downloadedCount = 0;

        // Préparer le fichier de sortie (pré-allouer)
        File dir = new File(outputDir);
        dir.mkdirs();
        File output = new File(dir, fileInfos.getFilename());
        this.outputPath = output.getAbsolutePath();

        // Pré-allouer le fichier à la bonne taille
        try (RandomAccessFile raf = new RandomAccessFile(output, "rw")) {
            raf.setLength(fileInfos.getLength());
        }
    }

    public void setListener(ProgressListener listener) {
        this.listener = listener;
    }

    public boolean isComplete() { return complete; }
    public String  getOutputPath() { return outputPath; }
    public int     getPieceCount() { return pieceCount; }
    public boolean[] getLocalBufferMap() { return localBufferMap.clone(); }

    /**
     * Lance le téléchargement depuis la liste de peers fournie.
     * Bloque jusqu'à complétion ou erreur.
     */
    public void download(List<String> peerAddresses, int maxConcurrentPeers) {
        int slots = Math.min(peerAddresses.size(), maxConcurrentPeers);
        ExecutorService pool = Executors.newFixedThreadPool(slots);

        // Distribuer les pièces entre les peers disponibles
        List<Future<?>> futures = new ArrayList<>();
        List<List<Integer>> assignments = assignPieces(peerAddresses);

        for (int i = 0; i < peerAddresses.size() && i < assignments.size(); i++) {
            final String peerAddr = peerAddresses.get(i);
            final List<Integer> pieces = assignments.get(i);
            if (pieces.isEmpty()) continue;

            futures.add(pool.submit(() -> downloadFromPeer(peerAddr, pieces)));
        }

        // Attendre tous les téléchargements
        for (Future<?> f : futures) {
            try { f.get(); }
            catch (Exception e) {
                if (listener != null) listener.onError("Erreur worker: " + e.getMessage());
            }
        }

        pool.shutdown();
        checkCompletion();
    }

    /**
     * Télécharge un ensemble de pièces depuis un peer donné.
     * Format d'adresse : "ip:port"
     */
    private void downloadFromPeer(String peerAddr, List<Integer> pieceIndices) {
        String[] parts = peerAddr.split(":");
        if (parts.length != 2) return;

        String host = parts[0];
        int    port;
        try { port = Integer.parseInt(parts[1]); }
        catch (NumberFormatException e) { return; }

        try {
            PeerClient client = new PeerClient(host, port);
            client.connect();

            // 1. Récupérer le buffer map du peer
            String haveResponse = client.interested(fileInfos.getKey());
            boolean[] remoteBM  = decodeBufferMap(haveResponse, pieceCount);

            // 2. Filtrer les pièces que ce peer possède vraiment
            List<Integer> available = new ArrayList<>();
            for (int idx : pieceIndices) {
                if (remoteBM != null && idx < remoteBM.length && remoteBM[idx]) {
                    synchronized (localBufferMap) {
                        if (!localBufferMap[idx]) available.add(idx);
                    }
                }
            }

            if (!available.isEmpty()) {
                // 3. Télécharger par batch de 10 pièces max
                int batchSize = 10;
                for (int i = 0; i < available.size(); i += batchSize) {
                    List<Integer> batch = available.subList(i, Math.min(i + batchSize, available.size()));
                    byte[] payload = client.getpieces(fileInfos.getKey(), batch);
                    if (payload != null) {
                        writePieces(batch, payload);
                    }
                }
            }

            client.disconnect();
        } catch (IOException e) {
            if (listener != null) listener.onError("Peer " + peerAddr + " inaccessible: " + e.getMessage());
        }
    }

    /**
     * Écrit les pièces reçues sur disque aux bons offsets.
     */
    private synchronized void writePieces(List<Integer> indices, byte[] payload) throws IOException {
        int offset = 0;
        for (int idx : indices) {
            if (localBufferMap[idx]) {
                // Pièce déjà reçue (race), sauter
                int skip = (int) Math.min(fileInfos.getPieceSize(), fileInfos.getLength() - (long) idx * fileInfos.getPieceSize());
                offset += skip;
                continue;
            }

            long fileOffset = (long) idx * fileInfos.getPieceSize();
            int  pieceSize  = (int) Math.min(fileInfos.getPieceSize(), fileInfos.getLength() - fileOffset);

            if (offset + pieceSize > payload.length) break; // payload tronqué

            byte[] pieceData = Arrays.copyOfRange(payload, offset, offset + pieceSize);
            FileManager.writeFileRange(outputPath, fileOffset, pieceData);

            localBufferMap[idx] = true;
            downloadedCount++;
            offset += pieceSize;

            if (listener != null) {
                listener.onPieceReceived(idx, pieceCount, downloadedCount);
            }
        }
    }

    /**
     * Répartit les pièces manquantes entre les peers disponibles (round-robin).
     */
    private List<List<Integer>> assignPieces(List<String> peers) {
        List<List<Integer>> assignments = new ArrayList<>();
        for (int i = 0; i < peers.size(); i++) assignments.add(new ArrayList<>());

        int peerIdx = 0;
        for (int i = 0; i < pieceCount; i++) {
            if (!localBufferMap[i]) {
                assignments.get(peerIdx % peers.size()).add(i);
                peerIdx++;
            }
        }
        return assignments;
    }

    /**
     * Décode un buffer map base64 en tableau de booléens.
     * Format de la réponse "have" : "have <key> <base64>"
     */
    public static boolean[] decodeBufferMap(String haveResponse, int pieceCount) {
        if (haveResponse == null) return null;
        String[] tokens = haveResponse.split(" ");
        if (tokens.length < 3) return null;

        try {
            byte[]   bytes = Base64.getDecoder().decode(tokens[2]);
            boolean[] map  = new boolean[pieceCount];
            for (int i = 0; i < pieceCount; i++) {
                int byteIdx = i / 8;
                int bitIdx  = 7 - (i % 8);
                if (byteIdx < bytes.length) {
                    map[i] = ((bytes[byteIdx] >> bitIdx) & 1) == 1;
                }
            }
            return map;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Encode le buffer map local en base64 pour l'envoyer aux peers.
     */
    public String encodeLocalBufferMap() {
        int   byteCount = (pieceCount + 7) / 8;
        byte[] bytes    = new byte[byteCount];
        for (int i = 0; i < pieceCount; i++) {
            if (localBufferMap[i]) {
                bytes[i / 8] |= (byte) (1 << (7 - (i % 8)));
            }
        }
        return Base64.getEncoder().encodeToString(bytes);
    }

    private void checkCompletion() {
        boolean done = true;
        for (boolean b : localBufferMap) {
            if (!b) { done = false; break; }
        }
        if (done) {
            complete = true;
            // Vérification MD5 optionnelle
            try {
                String md5 = FileManager.computeMD5(outputPath);
                System.out.println("[DownloadManager] Téléchargement complet. MD5=" + md5
                        + (md5.equals(fileInfos.getKey()) ? " ✓" : " ✗ (attendu: " + fileInfos.getKey() + ")"));
            } catch (IOException e) {
                System.err.println("[DownloadManager] Impossible de vérifier MD5: " + e.getMessage());
            }
            if (listener != null) listener.onComplete(outputPath);
        }
    }
}