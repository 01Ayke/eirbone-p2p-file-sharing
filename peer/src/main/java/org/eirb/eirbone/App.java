package org.eirb.eirbone;

import java.util.ArrayList;

import org.eirb.eirbone.client.tracker.*;
import org.eirb.eirbone.utils.FileInfos;
import org.eirb.eirbone.utils.Criterion;
import org.eirb.eirbone.utils.CriterionType;

public class App {
  public static void main(String[] args) {

    try {

      TrackerClient client = new TrackerClient("localhost", 9000);
      ArrayList<FileInfos> files = new ArrayList<>();
      ArrayList<String> leech_keys = new ArrayList<>();
      ArrayList<Criterion> criterions = new ArrayList<>();
      criterions.add(new Criterion("toto", CriterionType.FILENAME_EQUAL));
      files.add(new FileInfos("toto", 234, 4, "ajs;eoifjo;aseifj"));
      client.announce(2000, files, leech_keys);
      client.look(criterions);
      client.close();

    } catch (Exception e) {
      System.out.println("FAILED TCPCLIENT : " + e.getMessage());
    }

  }
}
