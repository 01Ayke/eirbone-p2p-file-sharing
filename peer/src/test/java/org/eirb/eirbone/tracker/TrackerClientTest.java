package org.eirb.eirbone.tracker;

import org.eirb.eirbone.config.Config;
import org.junit.jupiter.api.Test;

import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests TrackerClient against a minimal in-process fake tracker server.
 * The fake tracker speaks the same text protocol as the real tracker.
 */
class TrackerClientTest {

    @FunctionalInterface
    interface FakeHandler {
        void handle(BufferedReader in, PrintWriter out) throws IOException;
    }

    /** Starts a one-request fake tracker server; returns its port. */
    private int startFakeTracker(FakeHandler handler) throws IOException, InterruptedException {
        ServerSocket ss = new ServerSocket(0);
        CountDownLatch ready = new CountDownLatch(1);
        ExecutorService exec = Executors.newSingleThreadExecutor();
        exec.submit(() -> {
            ready.countDown();
            try (Socket client = ss.accept()) {
                BufferedReader r = new BufferedReader(new InputStreamReader(client.getInputStream()));
                PrintWriter    w = new PrintWriter(client.getOutputStream(), true);
                handler.handle(r, w);
            } catch (IOException ignored) {
            } finally {
                try { ss.close(); } catch (IOException ignored) {}
            }
        });
        exec.shutdown();
        ready.await();
        return ss.getLocalPort();
    }

    private TrackerClient connect(int port) throws IOException {
        Config cfg = new Config("nonexistent.ini");
        cfg.trackerAddress = "127.0.0.1";
        cfg.trackerPort    = port;
        TrackerClient tc = new TrackerClient(cfg, null);
        tc.connect();
        return tc;
    }

    // --- connection state ---

    @Test
    void isConnectedAfterConnect() throws Exception {
        int port = startFakeTracker((r, w) -> {});
        TrackerClient tc = connect(port);
        assertTrue(tc.isConnected());
        tc.disconnect();
        assertFalse(tc.isConnected());
    }

    // --- lookFiles parsing ---

    @Test
    void lookFilesEmptyListResponse() throws Exception {
        int port = startFakeTracker((r, w) -> { r.readLine(); w.println("list []"); });
        TrackerClient tc = connect(port);
        assertTrue(tc.lookFiles("anything").isEmpty());
        tc.disconnect();
    }

    @Test
    void lookFilesOneFile() throws Exception {
        int port = startFakeTracker((r, w) -> {
            r.readLine();
            w.println("list [report.pdf 5120 1024 abcdef123456]");
        });
        TrackerClient tc = connect(port);
        List<TrackerClient.NetworkFile> files = tc.lookFiles("report.pdf");
        assertEquals(1, files.size());
        assertEquals("report.pdf",   files.get(0).filename);
        assertEquals(5120L,          files.get(0).size);
        assertEquals(1024,           files.get(0).pieceSize);
        assertEquals("abcdef123456", files.get(0).md5);
        tc.disconnect();
    }

    @Test
    void lookFilesDeduplicatesByMd5() throws Exception {
        int port = startFakeTracker((r, w) -> {
            r.readLine();
            // two entries with same md5, one distinct
            w.println("list [a.txt 100 1024 samehash b.txt 200 1024 samehash c.txt 300 1024 otherhash]");
        });
        TrackerClient tc = connect(port);
        List<TrackerClient.NetworkFile> files = tc.lookFiles("");
        assertEquals(2, files.size());
        tc.disconnect();
    }

    // --- getFile parsing ---

    @Test
    void getFilePeerAddresses() throws Exception {
        int port = startFakeTracker((r, w) -> {
            r.readLine();
            w.println("[192.168.1.10:6881 10.0.0.1:4000]");
        });
        TrackerClient tc = connect(port);
        List<TrackerClient.PeerAddress> peers = tc.getFile("somemd5");
        assertEquals(2, peers.size());
        assertEquals("192.168.1.10", peers.get(0).ip);
        assertEquals(6881,           peers.get(0).port);
        assertEquals("10.0.0.1",     peers.get(1).ip);
        assertEquals(4000,           peers.get(1).port);
        tc.disconnect();
    }

    @Test
    void getFileEmptyListResponse() throws Exception {
        int port = startFakeTracker((r, w) -> { r.readLine(); w.println("[]"); });
        TrackerClient tc = connect(port);
        assertTrue(tc.getFile("somemd5").isEmpty());
        tc.disconnect();
    }

    // --- PeerAddress helper ---

    @Test
    void peerAddressToString() {
        TrackerClient.PeerAddress pa = new TrackerClient.PeerAddress("1.2.3.4", 9999);
        assertEquals("1.2.3.4:9999", pa.toString());
    }
}
