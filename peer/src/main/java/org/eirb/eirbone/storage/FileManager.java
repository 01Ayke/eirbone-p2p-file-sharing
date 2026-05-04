package org.eirb.eirbone.storage;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

public class FileManager {

    private final String sharedDir;

    // Informations d'un fichier partagé : nom, taille, taille de pièce et empreinte MD5
    public static class FileInfo {
        public String filename;
        public long   length;
        public int    pieceSize = 1024;
        public String md5;

        @Override
        public String toString() {
            return filename + " " + length + " " + pieceSize + " " + md5;
        }
    }

    /**
     * Manifeste d'un fichier partiel.
     * Stocké sous forme de fichier texte <filename>.manifest dans shared/.
     * Permet à un peer de posséder seulement certaines pièces d'un fichier
     * tout en connaissant le MD5 et la taille du fichier complet.
     *
     * Format du fichier .manifest :
     *   md5=<md5_du_fichier_complet>
     *   filename=<nom_du_fichier>
     *   size=<taille_totale_en_octets>
     *   pieces=<idx0>,<idx1>,...
     */
    public static class Manifest {
        public String       md5;
        public String       filename;
        public long         totalSize;
        public List<Integer> pieces = new ArrayList<>();
    }

    public FileManager(String sharedDir) {
        this.sharedDir = sharedDir;
        new File(sharedDir).mkdirs();
    }

    // Retourne la liste des fichiers partagés.
    // Les fichiers .manifest sont ignorés : leur MD5 canonique vient du manifeste, pas du disque.
    public List<FileInfo> listSharedFiles() throws IOException {
        List<FileInfo> result = new ArrayList<>();
        File dir = new File(sharedDir);
        File[] files = dir.listFiles();
        if (files == null) return result;

        for (File f : files) {
            if (!f.isFile()) continue;
            if (f.getName().endsWith(".manifest")) continue; // sidecar, pas un fichier partagé

            FileInfo info = new FileInfo();
            info.filename = f.getName();

            Manifest m = readManifest(f.getName());
            if (m != null) {
                // Fichier partiel : on annonce le MD5 et la taille du fichier COMPLET
                info.md5    = m.md5;
                info.length = m.totalSize;
            } else {
                // Fichier complet : MD5 calculé depuis le contenu réel
                info.length = f.length();
                info.md5    = computeMD5(f);
            }
            result.add(info);
        }
        return result;
    }

    // -------------------------------------------------------------------------
    // Manifeste
    // -------------------------------------------------------------------------

    /** Lit le manifeste sidecar d'un fichier partiel. Retourne null si inexistant. */
    public Manifest readManifest(String filename) {
        File mf = new File(sharedDir, filename + ".manifest");
        if (!mf.exists()) return null;
        try (BufferedReader br = new BufferedReader(new FileReader(mf))) {
            Manifest m = new Manifest();
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.startsWith("md5="))       m.md5 = line.substring(4).trim();
                else if (line.startsWith("filename="))  m.filename = line.substring(9).trim();
                else if (line.startsWith("size="))      m.totalSize = Long.parseLong(line.substring(5).trim());
                else if (line.startsWith("pieces=") && line.length() > 7) {
                    for (String p : line.substring(7).split(",")) {
                        String t = p.trim();
                        if (!t.isEmpty()) m.pieces.add(Integer.parseInt(t));
                    }
                }
            }
            return m;
        } catch (Exception e) {
            System.err.println("[WARN] Manifeste illisible pour " + filename + " : " + e.getMessage());
            return null;
        }
    }

    /**
     * Écrit (ou met à jour) le manifeste sidecar d'un fichier partiel.
     *
     * @param fileKey        nom du fichier temporaire dans shared/ (aussi le nom du sidecar .manifest)
     * @param targetFilename nom définitif du fichier une fois téléchargé
     * @param md5            empreinte MD5 du fichier complet
     * @param totalSize      taille totale du fichier complet en octets
     * @param pieces         indices des pièces déjà possédées
     */
    public void writeManifest(String fileKey, String targetFilename, String md5, long totalSize,
                               List<Integer> pieces) throws IOException {
        File mf = new File(sharedDir, fileKey + ".manifest");
        try (PrintWriter pw = new PrintWriter(new FileWriter(mf))) {
            pw.println("md5=" + md5);
            pw.println("filename=" + targetFilename);
            pw.println("size=" + totalSize);
            StringBuilder sb = new StringBuilder("pieces=");
            List<Integer> sorted = new ArrayList<>(pieces);
            Collections.sort(sorted);
            for (int i = 0; i < sorted.size(); i++) {
                if (i > 0) sb.append(",");
                sb.append(sorted.get(i));
            }
            pw.println(sb);
        }
    }

    /** Supprime le manifeste sidecar (appelé à la fin d'un téléchargement réussi). */
    public void deleteManifest(String fileKey) {
        new File(sharedDir, fileKey + ".manifest").delete();
    }

    /**
     * Retourne l'ensemble des indices de pièces réellement disponibles pour un fichier.
     *   - Si un manifeste existe → seulement les pièces listées dans le manifeste.
     *   - Sinon (fichier complet) → toutes les pièces 0..nbPieces-1.
     */
    public Set<Integer> getOwnedPieces(String filename, long totalSize) {
        Manifest m = readManifest(filename);
        if (m != null) {
            return new HashSet<>(m.pieces);
        }
        // Fichier complet : toutes les pièces sont disponibles
        int n = (int) Math.ceil((double) totalSize / 1024);
        Set<Integer> all = new HashSet<>();
        for (int i = 0; i < n; i++) all.add(i);
        return all;
    }

    // -------------------------------------------------------------------------
    // I/O pièces
    // -------------------------------------------------------------------------

    public static String computeMD5(File file) throws IOException {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            try (FileInputStream fis = new FileInputStream(file)) {
                byte[] buf = new byte[4096];
                int n;
                while ((n = fis.read(buf)) != -1) {
                    md.update(buf, 0, n);
                }
            }
            StringBuilder sb = new StringBuilder();
            for (byte b : md.digest()) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("MD5 non disponible", e);
        }
    }

    // Lit une pièce (toujours 1024 octets, la dernière est complétée par des zéros)
    public byte[] readPiece(String filename, int pieceIndex) throws IOException {
        File f = new File(sharedDir, filename);
        byte[] piece = new byte[1024];
        try (RandomAccessFile raf = new RandomAccessFile(f, "r")) {
            raf.seek((long) pieceIndex * 1024);
            raf.read(piece);
        }
        return piece;
    }

    // Écrit une pièce à l'offset correct : pieceIndex * 1024
    public void writePiece(String filename, int pieceIndex, byte[] data) throws IOException {
        File f = new File(sharedDir, filename);
        try (RandomAccessFile raf = new RandomAccessFile(f, "rw")) {
            raf.seek((long) pieceIndex * 1024);
            raf.write(data, 0, 1024);
        }
    }

    // Tronque le fichier à sa vraie taille (retire le padding de la dernière pièce)
    public void truncate(String filename, long realLength) throws IOException {
        File f = new File(sharedDir, filename);
        try (RandomAccessFile raf = new RandomAccessFile(f, "rw")) {
            raf.setLength(realLength);
        }
    }

    // Renomme le fichier temporaire (nommé par MD5) vers son nom définitif
    public void renameAfterDownload(String md5, String filename) throws IOException {
        File src = new File(sharedDir, md5);
        File dst = new File(sharedDir, filename);
        if (!src.renameTo(dst)) {
            Files.copy(src.toPath(), dst.toPath(), StandardCopyOption.REPLACE_EXISTING);
            src.delete();
        }
    }

    public File getFile(String filename) {
        return new File(sharedDir, filename);
    }
}
