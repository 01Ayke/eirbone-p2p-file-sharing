package org.eirb.eirbone;

import org.eirb.eirbone.cli.CLI;
import org.eirb.eirbone.config.Config;
import org.eirb.eirbone.download.DownloadManager;
import org.eirb.eirbone.peer.PeerServer;
import org.eirb.eirbone.storage.FileManager;
import org.eirb.eirbone.tracker.TrackerClient;

public class Main {

    public static void main(String[] args) throws Exception {

        // charger la config
        Config config = new Config("config.ini");
        FileManager fileManager = new FileManager(config.sharedDir);
        
        // se connecter au tracker
        TrackerClient tracker = new TrackerClient(config, fileManager);
        tracker.connect();
        tracker.announce();
        // lancer le serveur peer pour partager nos fichiers
        PeerServer server = new PeerServer(config, fileManager);
        Thread serverThread = new Thread(server, "peer-server");
        serverThread.setDaemon(true);
        serverThread.start();
        // lancer un thread qui fait un announce périodique pour mettre à jour le tracker
        Thread updateThread = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    Thread.sleep(config.updateIntervalSec * 1000L);
                    // Version minimale : on renvoie juste les fichiers qu'on a
                    tracker.announce();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (Exception e) {
                    System.err.println("[WARN] update échoué : " + e.getMessage());
                }
            }
        }, "tracker-updater");
        updateThread.setDaemon(true);
        updateThread.start();
        // lancer la CLI dans le thread main
        DownloadManager downloadManager = new DownloadManager(config, tracker, fileManager);
        CLI cli = new CLI(config, tracker, downloadManager);
        cli.run();
        // quand la CLI se termine on arrête proprement
        //updateThread.interrupt();
        tracker.bye();
        tracker.disconnect();
        System.out.println("[INFO] Peer arrêté.");
    }
}