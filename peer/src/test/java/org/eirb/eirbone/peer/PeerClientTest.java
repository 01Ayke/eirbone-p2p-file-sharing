package org.eirb.eirbone.peer;

import org.eirb.eirbone.config.Config;
import org.eirb.eirbone.storage.FileManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests PeerClient against a minimal fake PeerServer.
 * The fake server sends header and binary data as separate flushes
 * to avoid them being merged into a single TCP segment.
 */
class PeerClientTest {

    @TempDir
    Path tempDir;

    PeerClient client;

    @BeforeEach
    void setUp() {
        client = new PeerClient(new Config("nonexistent.ini"), new FileManager(tempDir.toString()));
    }

    /**
     * Starts a fake peer server that serves the given content.
     * Binary data is sent in a separate flush from the text header so that
     * PeerClient's BufferedReader does not consume piece bytes.
     */
    private int startFakePeer(byte[] fileContent, String md5) throws IOException, InterruptedException {
        int nbPieces = (int) Math.ceil((double) fileContent.length / 1024);
        byte[] padded = Arrays.copyOf(fileContent, nbPieces * 1024);

        ServerSocket ss = new ServerSocket(0);
        int port = ss.getLocalPort();
        CountDownLatch ready = new CountDownLatch(1);

        Thread t = new Thread(() -> {
            ready.countDown();
            try (Socket sock = ss.accept()) {
                BufferedReader reader = new BufferedReader(new InputStreamReader(sock.getInputStream()));
                PrintWriter    writer = new PrintWriter(sock.getOutputStream(), true);
                OutputStream   out    = sock.getOutputStream();

                reader.readLine(); // "interested <md5>"

                byte[] bm = new byte[(nbPieces + 7) / 8];
                Arrays.fill(bm, (byte) 0xFF);
                writer.println("have " + md5 + " " + Base64.getEncoder().encodeToString(bm));

                reader.readLine(); // "getpieces <md5> [0 1 ...]"

                // Build and flush the text header separately from the binary data
                StringBuilder hdr = new StringBuilder("data " + md5 + " [");
                for (int i = 0; i < nbPieces; i++) {
                    if (i > 0) hdr.append(" ");
                    hdr.append(i).append(" 1024");
                }
                hdr.append("]");
                writer.println(hdr.toString()); // auto-flush via PrintWriter
                // Small pause so the header arrives before binary data
                Thread.sleep(20);
                // Send binary pieces
                out.write(padded);
                out.flush();
            } catch (Exception ignored) {
            } finally {
                try { ss.close(); } catch (IOException ignored) {}
            }
        });
        t.setDaemon(true);
        t.start();
        ready.await();
        return port;
    }

    // --- tests ---

    @Test
    void downloadSmallFile() throws Exception {
        byte[] content = "Hello, P2P!".getBytes();
        Path tmp = tempDir.resolve("_md5src");
        Files.write(tmp, content);
        String md5 = FileManager.computeMD5(tmp.toFile());
        Files.delete(tmp);

        int port = startFakePeer(content, md5);
        client.downloadFrom("127.0.0.1", port, "hello.txt", md5, content.length);

        assertTrue(Files.exists(tempDir.resolve("hello.txt")));
        assertArrayEquals(content, Files.readAllBytes(tempDir.resolve("hello.txt")));
    }

    @Test
    void downloadExactlyOnePiece() throws Exception {
        byte[] content = new byte[1024]; Arrays.fill(content, (byte) 'Z');
        Path tmp = tempDir.resolve("_md5src2");
        Files.write(tmp, content);
        String md5 = FileManager.computeMD5(tmp.toFile());
        Files.delete(tmp);

        int port = startFakePeer(content, md5);
        client.downloadFrom("127.0.0.1", port, "exact.bin", md5, content.length);

        assertArrayEquals(content, Files.readAllBytes(tempDir.resolve("exact.bin")));
    }

    @Test
    void downloadCallsProgressListenerForEachPiece() throws Exception {
        byte[] content = new byte[3 * 1024]; Arrays.fill(content, (byte) 'P');
        Path tmp = tempDir.resolve("_md5src3");
        Files.write(tmp, content);
        String md5 = FileManager.computeMD5(tmp.toFile());
        Files.delete(tmp);

        int port = startFakePeer(content, md5);

        AtomicInteger calls    = new AtomicInteger();
        AtomicInteger lastDone = new AtomicInteger();
        client.downloadFrom("127.0.0.1", port, "prog.bin", md5, content.length,
            (done, total) -> { calls.incrementAndGet(); lastDone.set(done); assertEquals(3, total); });

        assertEquals(3, calls.get(),    "Listener should be called once per piece");
        assertEquals(3, lastDone.get(), "Last reported done should equal totalPieces");
    }
}
