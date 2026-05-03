package org.eirb.eirbone.storage;

import java.io.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;

public class FileManager {

    private final String sharedDir;

    // utile pour stocker les infos d'un fichier partagé (nom, taille, hash)
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

    public FileManager(String sharedDir) {
        this.sharedDir = sharedDir;
        // crée le répertoire s'il n'existe pas
        new File(sharedDir).mkdirs();
    }

    // Retourne la liste de tous les fichiers dans shared/ avec leur MD5
    public List<FileInfo> listSharedFiles() throws IOException {
        List<FileInfo> result = new ArrayList<>();
        File dir = new File(sharedDir);
        File[] files = dir.listFiles();
        if (files == null) return result;

        for (File f : files) {
            if (!f.isFile()) continue;
            FileInfo info = new FileInfo();
            info.filename = f.getName();
            info.length = f.length();
            info.md5 = computeMD5(f);
            result.add(info);
        }
        return result;
    }

    // Calcule le hash MD5 d'un fichier entier
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
            // Convertit le tableau d'octets en chaîne hexadécimale
            StringBuilder sb = new StringBuilder();
            for (byte b : md.digest()) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();

        } catch (NoSuchAlgorithmException e) {
            // MD5 est toujours disponible en Java standard
            throw new RuntimeException("MD5 non disponible", e);
        }
    }

    // Lit une pièce d'un fichier (index commence à 0)
    // Retourne toujours 1024 octets (bourré de zéros si dernière pièce)
    public byte[] readPiece(String filename, int pieceIndex) throws IOException {
        File f = new File(sharedDir, filename);
        byte[] piece = new byte[1024];

        try (RandomAccessFile raf = new RandomAccessFile(f, "r")) {
            raf.seek((long) pieceIndex * 1024);
            raf.read(piece); // si fin de fichier, le reste reste à 0
        }
        return piece;
    }

    // Écrit une pièce dans un fichier en cours de téléchargement
    public void writePiece(String filename, int pieceIndex, byte[] data) throws IOException {
        File f = new File(sharedDir, filename);
        try (RandomAccessFile raf = new RandomAccessFile(f, "rw")) {
            raf.seek((long) pieceIndex * 1024);
            raf.write(data, 0, 1024);
        }
    }

    // Tronque le fichier à sa vraie taille (retire le bourrage de la dernière pièce)
    public void truncate(String filename, long realLength) throws IOException {
        File f = new File(sharedDir, filename);
        try (RandomAccessFile raf = new RandomAccessFile(f, "rw")) {
            raf.setLength(realLength);
        }
    }

    public File getFile(String filename) {
        return new File(sharedDir, filename);
    }
}