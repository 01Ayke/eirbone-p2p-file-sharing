// Responsable de la lecture de config.ini et de l'exposition des paramètres de configuration
package org.eirb.eirbone.config;

import java.io.*;
import java.util.*;

public class Config {

    // Valeurs par défaut utilisées quand config.ini est absent ou incomplet
    public String trackerAddress  = "127.0.0.1";
    public int    trackerPort     = 9000;
    public int    peerPort        = 6881;
    public int    maxPeers        = 5;
    public int    updateIntervalSec = 30;
    public String sharedDir       = "./shared";
    public int    webPort         = 8080;

    public Config(String configFilePath) {
        Properties props = new Properties();
        try (FileInputStream fis = new FileInputStream(configFilePath)) {
            props.load(fis);
            trackerAddress    = props.getProperty("tracker-address", trackerAddress);
            trackerPort       = Integer.parseInt(props.getProperty("tracker-port", String.valueOf(trackerPort)));
            // accepte "peer-port" ou "listen-port" comme clé pour le port d'écoute
            String pp = props.getProperty("peer-port", props.getProperty("listen-port", String.valueOf(peerPort)));
            peerPort          = Integer.parseInt(pp.trim());
            maxPeers          = Integer.parseInt(props.getProperty("max-peers", String.valueOf(maxPeers)));
            updateIntervalSec = Integer.parseInt(props.getProperty("update-interval", String.valueOf(updateIntervalSec)));
            sharedDir         = props.getProperty("shared-dir", sharedDir);
            webPort           = Integer.parseInt(props.getProperty("web-port", String.valueOf(webPort)));
        } catch (IOException e) {
            System.err.println("config.ini non trouvé, utilisation des valeurs par défaut");
        }
    }
}
