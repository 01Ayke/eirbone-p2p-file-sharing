package org.eirb.eirbone.peer;

import org.eirb.eirbone.utils.FileInfos;

import java.io.*;
import java.util.Properties;

/**
 * Sauvegarde et recharge l'état d'un téléchargement en cours.
 * Format : fichier .properties à côté du fichier en cours de téléchargement.
 *
 * Permet de reprendre un téléchargement interrompu.
 */
public class DownloadManifest {

    private static final String MANIFEST_EXT = ".manifest";

    private final String manifestPath;

    public DownloadManifest(String outputFilePath) {
        this.manifestPath = outputFilePath + MANIFEST_EXT;
    }

    /** Sauvegarde l'état courant du DownloadManager sur disque. */
    public void save(DownloadManager dm, FileInfos fi) throws IOException {
        Properties p = new Properties();
        p.setProperty("key",       fi.getKey());
        p.setProperty("filename",  fi.getFilename());
        p.setProperty("length",    String.valueOf(fi.getLength()));
        p.setProperty("pieceSize", String.valueOf(fi.getPieceSize()));
        p.setProperty("bufferMap", dm.encodeLocalBufferMap());
        p.setProperty("downloaded",String.valueOf(countDownloaded(dm)));

        try (OutputStream os = new FileOutputStream(manifestPath)) {
            p.store(os, "Eirbone download manifest");
        }
    }

    /** Charge un manifest existant. Retourne null s'il n'existe pas. */
    public Properties load() throws IOException {
        File f = new File(manifestPath);
        if (!f.exists()) return null;

        Properties p = new Properties();
        try (InputStream is = new FileInputStream(f)) {
            p.load(is);
        }
        return p;
    }

    /** Supprime le manifest une fois le téléchargement terminé. */
    public void delete() {
        new File(manifestPath).delete();
    }

    /** Reconstruit un FileInfos depuis un manifest chargé. */
    public static FileInfos toFileInfos(Properties p) {
        return new FileInfos(
            p.getProperty("filename"),
            Long.parseLong(p.getProperty("length")),
            Integer.parseInt(p.getProperty("pieceSize")),
            p.getProperty("key")
        );
    }

    private int countDownloaded(DownloadManager dm) {
        int c = 0;
        for (boolean b : dm.getLocalBufferMap()) if (b) c++;
        return c;
    }
}