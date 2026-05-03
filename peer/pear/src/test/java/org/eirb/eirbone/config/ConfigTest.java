package org.eirb.eirbone.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class ConfigTest {

    @TempDir
    Path tempDir;

    // Vérifie que toutes les valeurs par défaut sont correctes quand le fichier est absent
    @Test
    void defaultsWhenFileMissing() {
        Config config = new Config("does-not-exist.ini");
        assertEquals("127.0.0.1", config.trackerAddress);
        assertEquals(9000,        config.trackerPort);
        assertEquals(6881,        config.peerPort);
        assertEquals(5,           config.maxPeers);
        assertEquals(30,          config.updateIntervalSec);
        assertEquals("./shared",  config.sharedDir);
        assertEquals(8080,        config.webPort);
    }

    // Vérifie que tous les champs sont correctement lus depuis un fichier complet
    @Test
    void loadsAllFields() throws IOException {
        Path cfg = tempDir.resolve("config.ini");
        Files.writeString(cfg,
            "tracker-address=10.0.0.1\n" +
            "tracker-port=7000\n" +
            "listen-port=4444\n" +
            "max-peers=10\n" +
            "update-interval=60\n" +
            "shared-dir=/tmp/share\n" +
            "web-port=9090\n"
        );
        Config config = new Config(cfg.toString());
        assertEquals("10.0.0.1",  config.trackerAddress);
        assertEquals(7000,         config.trackerPort);
        assertEquals(4444,         config.peerPort);
        assertEquals(10,           config.maxPeers);
        assertEquals(60,           config.updateIntervalSec);
        assertEquals("/tmp/share", config.sharedDir);
        assertEquals(9090,         config.webPort);
    }

    // La clé "listen-port" doit être reconnue comme alias du port d'écoute du peer
    @Test
    void supportsListenPortKey() throws IOException {
        Path cfg = tempDir.resolve("config.ini");
        Files.writeString(cfg, "listen-port=3333\n");
        Config config = new Config(cfg.toString());
        assertEquals(3333, config.peerPort);
    }

    // La clé "peer-port" doit également être reconnue
    @Test
    void supportsPeerPortKey() throws IOException {
        Path cfg = tempDir.resolve("config.ini");
        Files.writeString(cfg, "peer-port=5555\n");
        Config config = new Config(cfg.toString());
        assertEquals(5555, config.peerPort);
    }

    // Un fichier partiel ne doit écraser que les champs présents, les autres gardent leurs valeurs par défaut
    @Test
    void partialFileKeepsDefaults() throws IOException {
        Path cfg = tempDir.resolve("config.ini");
        Files.writeString(cfg, "tracker-address=192.168.0.1\n");
        Config config = new Config(cfg.toString());
        assertEquals("192.168.0.1", config.trackerAddress);
        assertEquals(9000, config.trackerPort);
        assertEquals(6881, config.peerPort);
        assertEquals(8080, config.webPort);
    }
}
