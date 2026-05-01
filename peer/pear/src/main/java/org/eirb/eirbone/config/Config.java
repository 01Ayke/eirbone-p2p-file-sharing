// etape 1, fichier responsable de lire la config.ini et d'exposer les paramÃ¨tres de configuration
// src/main/java/org/eirb/eirbone/config/Config.java
package org.eirb.eirbone.config;

import java.io.*;
import java.util.*;

public class Config {
    // valeurs par dÃ©faut au cas oÃ¹ config.ini est absent ? 
    public String trackerAddress = "127.0.0.1";
    public int trackerPort = 9000;
    public int peerPort = 6881;
    public int maxPeers = 5;
    public int updateIntervalSec = 30;
    public String sharedDir = "./shared";
    public int webPort = 8080;

    public Config(String configFilePath) {
        Properties props = new Properties();
        try (FileInputStream fis = new FileInputStream(configFilePath)) {
            props.load(fis);
            trackerAddress = props.getProperty("tracker-address", trackerAddress);
            trackerPort = Integer.parseInt(props.getProperty("tracker-port", String.valueOf(trackerPort)));
            // support both "peer-port" and "listen-port" keys
            String pp = props.getProperty("peer-port", props.getProperty("listen-port", String.valueOf(peerPort)));
            peerPort = Integer.parseInt(pp.trim());
            maxPeers = Integer.parseInt(props.getProperty("max-peers", String.valueOf(maxPeers)));
            updateIntervalSec = Integer.parseInt(props.getProperty("update-interval", String.valueOf(updateIntervalSec)));
            sharedDir = props.getProperty("shared-dir", sharedDir);
            webPort = Integer.parseInt(props.getProperty("web-port", String.valueOf(webPort)));
        } catch (IOException e) {
            System.err.println("config.ini non trouvÃ©, utilisation des valeurs par dÃ©faut");
        }
    }
}