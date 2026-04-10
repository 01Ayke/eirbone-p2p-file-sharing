package org.eirb.eirbone.utils;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public class FileManager {
  private static final String SHARED_DIR = "./shared";

  static {
    File dir = new File(SHARED_DIR);
    if (!dir.exists()) {
      dir.mkdirs();
    }
  }

  /**
   * Crée un fichier vide avec des données de test dans le répertoire partagé
   */
  public static String createTestFile(String filename, long size) throws IOException {
    File file = new File(SHARED_DIR, filename);
    
    try (FileOutputStream fos = new FileOutputStream(file)) {
      byte[] buffer = new byte[4096];
      long written = 0;
      
      while (written < size) {
        int toWrite = (int) Math.min(buffer.length, size - written);
        // Remplir avec un pattern simple pour test
        for (int i = 0; i < toWrite; i++) {
          buffer[i] = (byte) ((written + i) % 256);
        }
        fos.write(buffer, 0, toWrite);
        written += toWrite;
      }
    }
    
    System.out.println("[FileManager] Fichier créé: " + file.getAbsolutePath() + " (" + size + " octets)");
    return file.getAbsolutePath();
  }

  /**
   * Lit une plage de bytes du fichier
   */
  public static byte[] readFileRange(String filePath, long offset, int length) throws IOException {
    File file = new File(filePath);
    if (!file.exists()) {
      throw new IOException("Fichier non trouvé: " + filePath);
    }

    byte[] buffer = new byte[length];
    try (FileInputStream fis = new FileInputStream(file)) {
      fis.skip(offset);
      int read = fis.read(buffer, 0, length);
      if (read < length) {
        byte[] result = new byte[read];
        System.arraycopy(buffer, 0, result, 0, read);
        return result;
      }
    }
    return buffer;
  }

  /**
   * Écrit une plage de bytes dans le fichier
   */
  public static void writeFileRange(String filePath, long offset, byte[] data) throws IOException {
    File file = new File(filePath);
    RandomAccessFile raf = new RandomAccessFile(file, "rw");
    try {
      raf.seek(offset);
      raf.write(data);
    } finally {
      raf.close();
    }
  }

  /**
   * Retourne le path complet pour un fichier dans le répertoire partagé
   */
  public static String getSharedPath(String filename) {
    return new File(SHARED_DIR, filename).getAbsolutePath();
  }

  /**
   * Supprime un fichier du répertoire partagé
   */
  public static void deleteFile(String filename) {
    File file = new File(SHARED_DIR, filename);
    if (file.exists() && file.delete()) {
      System.out.println("[FileManager] Fichier supprimé: " + filename);
    }
  }

  /**
   * Calcule le hash MD5 d'un fichier et retourne la chaîne hexadécimale.
   * C'est la clé unique du fichier dans le réseau P2P.
   */
  public static String computeMD5(String filePath) throws IOException {
    MessageDigest md;
    try {
      md = MessageDigest.getInstance("MD5");
    } catch (NoSuchAlgorithmException e) {
      throw new IOException("MD5 non disponible", e);
    }

    File file = new File(filePath);
    byte[] buffer = new byte[8192];
    try (FileInputStream fis = new FileInputStream(file)) {
      int read;
      while ((read = fis.read(buffer)) != -1) {
        md.update(buffer, 0, read);
      }
    }

    byte[] digest = md.digest();
    StringBuilder hex = new StringBuilder();
    for (byte b : digest) {
      hex.append(String.format("%02x", b));
    }
    return hex.toString();
  }

  /**
   * Crée un FileInfos complet à partir d'un fichier existant sur disque.
   * Calcule automatiquement le MD5 comme clé.
   */
  public static FileInfos buildFileInfos(String filePath, int pieceSize) throws IOException {
    File file = new File(filePath);
    if (!file.exists()) throw new IOException("Fichier introuvable: " + filePath);
    String key = computeMD5(filePath);
    return new FileInfos(file.getName(), file.length(), pieceSize, key);
  }
}
