package org.eirb.eirbone;

import java.util.ArrayList;

import org.eirb.eirbone.client.tracker.*;
import org.eirb.eirbone.utils.FileInfos;

public class App {
  public static void main(String[] args) {

    try {

      TrackerClient client = new TrackerClient("localhost", 9000);
      ArrayList<FileInfos> files = new ArrayList<>();
      ArrayList<String> leech_keys = new ArrayList<>();
      files.add(new FileInfos("toto", 234, 4, "ajs;eoifjo;aseifj"));
      client.announce(2000, files, leech_keys);
      client.close();

    } catch (Exception e) {
      System.out.println("FAILED TCPCLIENT : " + e.getMessage());
    }

  }
}
