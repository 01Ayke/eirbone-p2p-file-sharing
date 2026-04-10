// src/main/java/org/eirb/eirbone/App.java
package org.eirb.eirbone;

import org.eirb.eirbone.tracker.TrackerClient;
import org.eirb.eirbone.tracker.TrackerClient.FileInfo;
import org.eirb.eirbone.utils.FileInfos;
import java.util.*;

// ce fichier est un simple test de connexion au tracker
public class App2 {
    public static void main(String[] args) throws Exception {
        // test de connexion au tracker au port 9000 et des commandes announce/look/getfile
        TrackerClient tracker = new TrackerClient("127.0.0.1", 9000);
        tracker.connect();

        // test announce avec un fichier fictif
        FileInfo f = new FileInfo();
        f.filename = "test.dat";
        f.length = 2048;
        f.pieceSize = 1024;
        f.key = "aabbccddeeff00112233445566778899";

        // Créer une ArrayList<FileInfos> pour announce
        ArrayList<FileInfos> trackerFiles = new ArrayList<>();
        FileInfos fi = new FileInfos(f.filename, f.length, f.pieceSize, f.key);
        trackerFiles.add(fi);

        tracker.announce(5000, trackerFiles, new ArrayList<>());
        System.out.println("Announce : OK");

        // test de look
        List<FileInfo> files = tracker.look("test.dat");
        System.out.println("Look résultat : " + files);

        // test de getfile
        List<String> peers = tracker.getFile(f.key);
        System.out.println("Peers pour le fichier : " + peers);

        tracker.disconnect();
    }
}