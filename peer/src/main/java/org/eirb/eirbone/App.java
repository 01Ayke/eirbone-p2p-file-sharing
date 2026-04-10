package org.eirb.eirbone;

import java.util.ArrayList;

import org.eirb.eirbone.tracker.TrackerClient;
import org.eirb.eirbone.server.TCPServer;
import org.eirb.eirbone.utils.FileInfos;

public class App {
  public static void main(String[] args) {

    final int peerPort = 2000;

    try {
      TCPServer server = new TCPServer(peerPort);
      FileInfos file = new FileInfos("toto", 234, 4, "ajs;eoifjo;aseifj");
      server.addFile(file);
      server.start();

      TrackerClient client = new TrackerClient("localhost", 9000);
      client.connect();
      ArrayList<FileInfos> files = new ArrayList<>();
      ArrayList<String> leech_keys = new ArrayList<>();
      files.add(file);

      client.announce(peerPort, files, leech_keys);
      ArrayList<String> peers = client.getFile(file.getKey());
      System.out.println("Peers for " + file.getKey() + " => " + peers);
      client.disconnect();

      // Keep the peer server running briefly for future P2P connections
      Thread.sleep(1500);
      server.stop();

    } catch (Exception e) {
      System.out.println("FAILED TCPCLIENT : " + e.getMessage());
    }

  }
}
