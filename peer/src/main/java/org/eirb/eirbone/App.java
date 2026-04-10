package org.eirb.eirbone;

import java.util.ArrayList;
import java.util.Scanner;

<<<<<<< Updated upstream
import org.eirb.eirbone.tracker.TrackerClient;
import org.eirb.eirbone.server.TCPServer;
=======
import org.eirb.eirbone.Core;
import org.eirb.eirbone.client.tracker.*;
import org.eirb.eirbone.parser.CLIParser;
>>>>>>> Stashed changes
import org.eirb.eirbone.utils.FileInfos;

public class App {
  public static void main(String[] args) {

<<<<<<< Updated upstream
    final int peerPort = 2000;

=======
    // try {
    //
    // TrackerClient client = new TrackerClient("localhost", 9000);
    // ArrayList<FileInfos> files = new ArrayList<>();
    // ArrayList<String> leech_keys = new ArrayList<>();
    // ArrayList<Criterion> criterions = new ArrayList<>();
    //
    // criterions.add(new Criterion("toto", CriterionType.FILENAME_EQUAL));
    // files.add(new FileInfos("toto", 234, 4, "ajs;eoifjo;aseifj"));
    // client.announce(2000, files, leech_keys);
    // client.look(criterions);
    // client.close();
    //
    // } catch (Exception e) {
    // System.out.println("FAILED TCPCLIENT : " + e.getMessage());
    // }

    Scanner scanner = null;
>>>>>>> Stashed changes
    try {
      TCPServer server = new TCPServer(peerPort);
      FileInfos file = new FileInfos("toto", 234, 4, "ajs;eoifjo;aseifj");
      server.addFile(file);
      server.start();

<<<<<<< Updated upstream
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
=======
      // TODO: change avec user input
      Core core = new Core("localhost", 9000);
      scanner = new Scanner(System.in);
>>>>>>> Stashed changes

      while (true) {
        String line = scanner.nextLine();
        core.getTracker().send(line);
        core.readMessageTracker();
      }
    } catch (Exception e) {
      System.out.println(e.getMessage());
      // TODO: handle exception
    } finally {
      if (scanner != null)
        scanner.close();
    }

  }
}
