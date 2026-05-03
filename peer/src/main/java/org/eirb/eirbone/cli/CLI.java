package org.eirb.eirbone.cli;

import org.eirb.eirbone.config.Config;
import org.eirb.eirbone.download.DownloadManager;
import org.eirb.eirbone.tracker.TrackerClient;

import java.util.Scanner;

public class CLI {

    private final Config          config;
    private final TrackerClient   tracker;
    private final DownloadManager downloadManager;

    public CLI(Config config, TrackerClient tracker, DownloadManager downloadManager) {
        this.config          = config;
        this.tracker         = tracker;
        this.downloadManager = downloadManager;
    }

    // Boucle principale qui s'exécute dans le thread principal jusqu'à la commande "quit"
    public void run() {
        Scanner scanner = new Scanner(System.in);
        System.out.println("Commandes : download <fichier>  |  list <fichier>  |  quit");

        while (scanner.hasNextLine()) {
            String line = scanner.nextLine().trim();
            if (line.isEmpty()) continue;
            // On découpe sur le premier espace uniquement pour préserver les noms avec espaces
            String[] parts = line.split(" ", 2);
            String cmd = parts[0];

            switch (cmd) {
                case "download":
                    if (parts.length < 2) {
                        System.out.println("Usage : download <nom_fichier>");
                        break;
                    }
                    downloadManager.download(parts[1]);
                    break;

                case "list":
                    String searchName = parts.length >= 2 ? parts[1] : "";
                    try {
                        String response = tracker.look(searchName);
                        System.out.println("Résultat : " + response);
                    } catch (Exception e) {
                        System.err.println("[ERROR] " + e.getMessage());
                    }
                    break;

                case "quit":
                    System.out.println("[INFO] Au revoir.");
                    // Sort de run() — le thread principal enchaîne avec bye() et disconnect()
                    return;

                default:
                    System.out.println("Commande inconnue : " + cmd);
            }
        }
    }
}
