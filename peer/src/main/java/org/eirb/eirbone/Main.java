package org.eirb.eirbone;

import org.eirb.eirbone.cli.CLI;
import org.eirb.eirbone.config.Config;
import org.eirb.eirbone.download.DownloadManager;
import org.eirb.eirbone.peer.PeerServer;
import org.eirb.eirbone.storage.FileManager;
import org.eirb.eirbone.tracker.TrackerClient;
import org.eirb.eirbone.web.WebServer;

public class Main {

    public static void main(String[] args) throws Exception {

        Config config = new Config("config.ini");
        // arguments optionnels : [port-peer] [ip-tracker] [port-tracker]
        if (args.length > 0) config.peerPort        = Integer.parseInt(args[0]);
        if (args.length > 1) config.trackerAddress   = args[1];
        if (args.length > 2) config.trackerPort      = Integer.parseInt(args[2]);

        FileManager fileManager = new FileManager(config.sharedDir);
        TrackerClient tracker = new TrackerClient(config, fileManager);
        DownloadManager downloadManager = new DownloadManager(config, tracker, fileManager);

        // Le serveur web démarre en premier — accessible même si le tracker est indisponible
        WebServer webServer = new WebServer(config, tracker, downloadManager, fileManager);
        webServer.start();

        // Connexion au tracker (non fatale : l'interface web reste accessible)
        try {
            tracker.connect();
            tracker.announce();
        } catch (Exception e) {
            System.err.println("[WARN] Tracker indisponible : " + e.getMessage());
            System.err.println("[WARN] L'interface web est accessible. Relancez le tracker puis reconnectez.");
        }

        // Démarrage du serveur P2P en tâche de fond
        PeerServer server = new PeerServer(config, fileManager);
        Thread serverThread = new Thread(server, "peer-server");
        serverThread.setDaemon(true);
        serverThread.start();

        // Annonces périodiques au tracker (toutes les updateIntervalSec secondes)
        Thread updateThread = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    Thread.sleep(config.updateIntervalSec * 1000L);
                    if (tracker.isConnected()) tracker.sendUpdate();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (Exception e) {
                    System.err.println("[WARN] Mise à jour du tracker échouée : " + e.getMessage());
                }
            }
        }, "tracker-updater");
        updateThread.setDaemon(true);
        updateThread.start();

        // Boucle CLI — bloque le thread principal jusqu'à la commande "quit"
        CLI cli = new CLI(config, tracker, downloadManager);
        cli.run();

        // Arrêt propre
        webServer.stop();
        if (tracker.isConnected()) {
            tracker.bye();
            tracker.disconnect();
        }
        System.out.println("[INFO] Peer arrêté.");
    }
}
