package org.eirb.eirbone;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;

import org.eirb.eirbone.client.peer.PeerClient;
import org.eirb.eirbone.client.tracker.TrackerClient;
import org.eirb.eirbone.parser.CLIParser;
import org.eirb.eirbone.utils.FileInfos;

public class Core {
  private TrackerClient tracker;
  private ArrayList<PeerClient> peers;
  private HashMap<String, FileInfos> seeds;
  private HashMap<String, FileInfos> leeds;

  public Core(String host, int port) throws IOException {
    tracker = new TrackerClient(host, port);
    peers = new ArrayList<>();
    seeds = new HashMap<>();
    leeds = new HashMap<>();
  }

  public TrackerClient getTracker() {
    return tracker;
  }

  public String readMessageTracker() throws IOException {
    StringBuilder buffer = new StringBuilder();
    while (true) {

      int newlineIndex = buffer.indexOf("\n");
      if (newlineIndex != -1) {
        String message = buffer.substring(0, newlineIndex).trim();
        buffer.delete(0, newlineIndex + 1);
        System.out.println("testo");
        return message;
      }

      String chunk = tracker.read();

      // WARNING: pas sur de ca
      if (chunk.length() == 0) {
        System.out.println("test");
        return buffer.toString();
      }

      buffer.append(chunk);
    }
  }

}
