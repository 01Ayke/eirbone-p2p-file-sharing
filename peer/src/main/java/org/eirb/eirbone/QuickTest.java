package org.eirb.eirbone;

import org.eirb.eirbone.tracker.TrackerClient;
import org.eirb.eirbone.server.TCPServer;
import org.eirb.eirbone.utils.FileInfos;
import org.eirb.eirbone.utils.FileManager;
import java.util.*;

/**
 * Quick test to verify core P2P components are working.
 */
public class QuickTest {
    public static void main(String[] args) throws Exception {
        System.out.println("=== Quick System Test ===\n");

        try {
            // Test 1: FileManager MD5 calculation
            System.out.println("1. Testing FileManager...");
            FileInfos fileInfo = FileManager.buildFileInfos("./shared/ajs;eoifjo;aseifj.dat", 32);
            System.out.println("   ✓ File key computed: " + fileInfo.getKey().substring(0, 16) + "...");
            System.out.println("   ✓ File size: " + fileInfo.getLength() + " bytes");

            // Test 2: Tracker connection and announce
            System.out.println("\n2. Testing Tracker Communication...");
            TrackerClient tracker = new TrackerClient("localhost", 9000);
            tracker.connect();
            System.out.println("   ✓ Connected to tracker");

            ArrayList<FileInfos> files = new ArrayList<>();
            files.add(fileInfo);
            ArrayList<String> leeches = new ArrayList<>();
            tracker.announce(5555, files, leeches);
            System.out.println("   ✓ Announced file to tracker");

            // Test 3: Get peers from tracker
            System.out.println("\n3. Testing Peer Discovery...");
            ArrayList<String> peers = tracker.getFile(fileInfo.getKey());
            System.out.println("   ✓ Found " + peers.size() + " peer(s)");
            for (String peer : peers) {
                System.out.println("     - " + peer);
            }

            tracker.disconnect();
            System.out.println("   ✓ Disconnected from tracker");

            // Test 4: TCPServer
            System.out.println("\n4. Testing TCP Server...");
            TCPServer server = new TCPServer(6666);
            server.addFile(fileInfo);
            server.start();
            System.out.println("   ✓ Server started on port 6666");
            System.out.println("   ✓ File added to server");

            Thread.sleep(500);
            server.stop();
            System.out.println("   ✓ Server stopped");

            System.out.println("\n=== All Core Components Working ===");

        } catch (Exception e) {
            System.err.println("\n✗ ERROR: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
