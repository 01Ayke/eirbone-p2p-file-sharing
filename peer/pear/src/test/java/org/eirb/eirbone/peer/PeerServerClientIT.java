package org.eirb.eirbone.peer;

import org.eirb.eirbone.config.Config;
import org.eirb.eirbone.storage.FileManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.*;
import java.net.ServerSocket;
import java.nio.file.*;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests: real PeerServer + real PeerClient, end-to-end download.
 * Verifies that a file seeded by PeerServer is downloaded correctly by PeerClient.
 */
class PeerServerClientIT {

    @TempDir Path seedDir;
    @TempDir Path downloadDir;

    private static int findFreePort() throws IOException {
        try (ServerSocket ss = new ServerSocket(0)) { return ss.getLocalPort(); }
    }

    private PeerServer startServer(FileManager fm) throws Exception {
        Config cfg = new Config("nonexistent.ini");
        cfg.peerPort = findFreePort();
        PeerServer server = new PeerServer(cfg, fm);
        Thread t = new Thread(server);
        t.setDaemon(true);
        t.start();
        Thread.sleep(200);
        return server;
    }

    /** Full cycle: create file → seed it → download it → compare bytes. */
    @Test
    void fullDownloadSmallFile() throws Exception {
        byte[] content = "Integration test: hello P2P!".getBytes();
        Path srcFile = seedDir.resolve("small.txt");
        Files.write(srcFile, content);

        FileManager seedFm = new FileManager(seedDir.toString());
        String md5  = FileManager.computeMD5(srcFile.toFile());
        int port = findFreePort();

        Config serverCfg = new Config("nonexistent.ini");
        serverCfg.peerPort = port;
        PeerServer srv = new PeerServer(serverCfg, seedFm);
        Thread st = new Thread(srv); st.setDaemon(true); st.start();
        Thread.sleep(200);

        FileManager dlFm = new FileManager(downloadDir.toString());
        new PeerClient(new Config("nonexistent.ini"), dlFm)
            .downloadFrom("127.0.0.1", port, "small.txt", md5, content.length);

        Path downloaded = downloadDir.resolve("small.txt");
        assertTrue(Files.exists(downloaded), "Downloaded file must exist");
        assertArrayEquals(content, Files.readAllBytes(downloaded), "Content must match original");
    }

    @Test
    void fullDownloadMultiplePieces() throws Exception {
        // 5 exact pieces
        byte[] content = new byte[5 * 1024];
        for (int i = 0; i < content.length; i++) content[i] = (byte) (i % 127);
        Path srcFile = seedDir.resolve("multi.bin");
        Files.write(srcFile, content);

        FileManager seedFm = new FileManager(seedDir.toString());
        String md5  = FileManager.computeMD5(srcFile.toFile());
        int port = findFreePort();

        Config serverCfg = new Config("nonexistent.ini");
        serverCfg.peerPort = port;
        PeerServer srv = new PeerServer(serverCfg, seedFm);
        Thread st = new Thread(srv); st.setDaemon(true); st.start();
        Thread.sleep(200);

        FileManager dlFm = new FileManager(downloadDir.toString());
        int[] lastProgress = {0};
        new PeerClient(new Config("nonexistent.ini"), dlFm)
            .downloadFrom("127.0.0.1", port, "multi.bin", md5, content.length,
                (done, total) -> { assertEquals(5, total); lastProgress[0] = done; });

        assertEquals(5, lastProgress[0]);
        assertArrayEquals(content, Files.readAllBytes(downloadDir.resolve("multi.bin")));
    }

    @Test
    void fullDownloadNonAlignedSize() throws Exception {
        // 2 full pieces + 1 partial piece (452 bytes)
        byte[] content = new byte[2 * 1024 + 452];
        Arrays.fill(content, (byte) 'X');
        Path srcFile = seedDir.resolve("partial.bin");
        Files.write(srcFile, content);

        FileManager seedFm = new FileManager(seedDir.toString());
        String md5  = FileManager.computeMD5(srcFile.toFile());
        int port = findFreePort();

        Config serverCfg = new Config("nonexistent.ini");
        serverCfg.peerPort = port;
        PeerServer srv = new PeerServer(serverCfg, seedFm);
        Thread st = new Thread(srv); st.setDaemon(true); st.start();
        Thread.sleep(200);

        FileManager dlFm = new FileManager(downloadDir.toString());
        new PeerClient(new Config("nonexistent.ini"), dlFm)
            .downloadFrom("127.0.0.1", port, "partial.bin", md5, content.length);

        Path downloaded = downloadDir.resolve("partial.bin");
        assertEquals(content.length, Files.size(downloaded), "File must be truncated to real size");
        assertArrayEquals(content, Files.readAllBytes(downloaded), "Content must match");
    }
}
