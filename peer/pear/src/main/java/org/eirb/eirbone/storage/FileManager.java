package org.eirb.eirbone.storage;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;

public class FileManager {

    private final String sharedDir;

    // utile pour stocker les infos d'un fichier partagÃ© (nom, taille, hash)
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
        // crÃ©e le rÃ©pertoire s'il n'existe pas
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
            // Convertit le tableau d'octets en chaÃ®ne hexadÃ©cimale
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

    // Lit une piÃ¨ce d'un fichier (index commence Ã  0)
    // Retourne toujours 1024 octets (bourrÃ© de zÃ©ros si derniÃ¨re piÃ¨ce)
    public byte[] readPiece(String filename, int pieceIndex) throws IOException {
        File f = new File(sharedDir, filename);
        byte[] piece = new byte[1024];

        try (RandomAccessFile raf = new RandomAccessFile(f, "r")) {
            raf.seek((long) pieceIndex * 1024);
            raf.read(piece); // si fin de fichier, le reste reste Ã  0
        }
        return piece;
    }

    // Ã‰crit une piÃ¨ce dans un fichier en cours de tÃ©lÃ©chargement
    public void writePiece(String filename, int pieceIndex, byte[] data) throws IOException {
        File f = new File(sharedDir, filename);
        try (RandomAccessFile raf = new RandomAccessFile(f, "rw")) {
            raf.seek((long) pieceIndex * 1024);
            raf.write(data, 0, 1024);
        }
    }

    // Tronque le fichier Ã  sa vraie taille (retire le bourrage de la derniÃ¨re piÃ¨ce)
    public void truncate(String filename, long realLength) throws IOException {
        File f = new File(sharedDir, filename);
        try (RandomAccessFile raf = new RandomAccessFile(f, "rw")) {
            raf.setLength(realLength);
        }
    }

    // Renomme le fichier temporaire (identifiÃ© par hash) vers son vrai nom
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