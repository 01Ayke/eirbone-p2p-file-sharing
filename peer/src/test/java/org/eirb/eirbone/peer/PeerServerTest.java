package org.eirb.eirbone.peer;

import org.eirb.eirbone.config.Config;
import org.eirb.eirbone.storage.FileManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.*;
import java.net.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests PeerServer protocol responses by connecting a raw test client to it.
 * Uses byte-by-byte line reading to avoid BufferedReader pre-consuming binary data.
 */
class PeerServerTest {

    @TempDir
    Path tempDir;

    int serverPort;

    @BeforeEach
    void startServer() throws Exception {
        Config cfg = new Config("nonexistent.ini");
        cfg.peerPort = findFreePort();
        serverPort = cfg.peerPort;
        PeerServer server = new PeerServer(cfg, new FileManager(tempDir.toString()));
        Thread t = new Thread(server);
        t.setDaemon(true);
        t.start();
        Thread.sleep(150);
    }

    private static int findFreePort() throws IOException {
        try (ServerSocket ss = new ServerSocket(0)) { return ss.getLocalPort(); }
    }

    /** Reads one text line byte-by-byte — never pre-reads binary data. */
    private static String readLine(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') break;
            if (c != '\r') sb.append((char) c);
        }
        return sb.toString();
    }

    // --- interested ---

    @Test
    void interestedUnknownMd5_returnsHaveWithDecodableBuffermap() throws Exception {
        try (Socket s = new Socket("127.0.0.1", serverPort)) {
            PrintWriter w  = new PrintWriter(s.getOutputStream(), true);
            InputStream in = s.getInputStream();
            w.println("interested unknownmd5abc");
            String response = readLine(in);
            assertTrue(response.startsWith("have unknownmd5abc "), "Got: " + response);
            // encodedEmptyBuffermap(0) = Base64("") = "" → response ends with a space and empty string
            String[] parts  = response.split(" ", 3);
            String encoded  = parts.length == 3 ? parts[2].trim() : "";
            assertDoesNotThrow(() -> Base64.getDecoder().decode(encoded));
        }
    }

    @Test
    void interestedKnownMd5_returnsHaveWithAllBitsSet() throws Exception {
        byte[] content = new byte[1024]; Arrays.fill(content, (byte) 'A');
        Path file = tempDir.resolve("testfile.bin");
        Files.write(file, content);
        String md5 = FileManager.computeMD5(file.toFile());

        try (Socket s = new Socket("127.0.0.1", serverPort)) {
            PrintWriter w  = new PrintWriter(s.getOutputStream(), true);
            InputStream in = s.getInputStream();
            w.println("interested " + md5);
            String response = readLine(in);
            assertTrue(response.startsWith("have " + md5 + " "), "Got: " + response);
            String encoded = response.split(" ")[2];
            byte[] buffermap = Base64.getDecoder().decode(encoded);
            assertEquals(1, buffermap.length);
            // piece 0 → bit 7 of byte 0 must be set
            assertEquals((byte) 0x80, buffermap[0]);
        }
    }

    @Test
    void interestedTwoPieces_buffermapHasTwoBitsSet() throws Exception {
        byte[] content = new byte[2048]; Arrays.fill(content, (byte) 'B');
        Path file = tempDir.resolve("two_pieces.bin");
        Files.write(file, content);
        String md5 = FileManager.computeMD5(file.toFile());

        try (Socket s = new Socket("127.0.0.1", serverPort)) {
            PrintWriter w  = new PrintWriter(s.getOutputStream(), true);
            InputStream in = s.getInputStream();
            w.println("interested " + md5);
            String response = readLine(in);
            String encoded = response.split(" ")[2];
            byte[] buffermap = Base64.getDecoder().decode(encoded);
            assertEquals(1, buffermap.length);
            // pieces 0 and 1 → bits 7 and 6 set → 0xC0
            assertEquals((byte) 0xC0, buffermap[0]);
        }
    }

    // --- getpieces ---

    @Test
    void getPieces_unknownMd5_returnsErrorLine() throws Exception {
        try (Socket s = new Socket("127.0.0.1", serverPort)) {
            PrintWriter w  = new PrintWriter(s.getOutputStream(), true);
            InputStream in = s.getInputStream();
            w.println("getpieces unknownmd5 [0]");
            String response = readLine(in);
            assertTrue(response.startsWith("error"), "Got: " + response);
        }
    }

    @Test
    void getPieces_returnsCorrectHeader_andBinaryData() throws Exception {
        byte[] content = new byte[2048];
        for (int i = 0; i < content.length; i++) content[i] = (byte) (i % 200);
        Path file = tempDir.resolve("data.bin");
        Files.write(file, content);
        String md5 = FileManager.computeMD5(file.toFile());

        try (Socket s = new Socket("127.0.0.1", serverPort)) {
            PrintWriter w  = new PrintWriter(s.getOutputStream(), true);
            InputStream in = s.getInputStream();

            // announce interest first (not strictly required by server but realistic)
            w.println("interested " + md5);
            readLine(in); // consume "have ..."

            // request both pieces
            w.println("getpieces " + md5 + " [0 1]");
            String header = readLine(in);
            assertTrue(header.startsWith("data " + md5), "Got: " + header);
            assertTrue(header.contains("[0 1024 1 1024]"), "Got: " + header);

            // read 2048 bytes of binary data
            byte[] received = new byte[2048];
            int offset = 0;
            while (offset < 2048) {
                int n = in.read(received, offset, 2048 - offset);
                assertTrue(n >= 0, "Connection closed early");
                offset += n;
            }
            assertArrayEquals(content, received);
        }
    }
}
