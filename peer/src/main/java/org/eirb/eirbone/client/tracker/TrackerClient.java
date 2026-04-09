package org.eirb.eirbone.client.tracker;

import org.eirb.eirbone.client.TCPClient;
import org.eirb.eirbone.utils.FileInfos;
import java.io.IOException;
import java.util.ArrayList;

public class TrackerClient extends TCPClient {

  public TrackerClient(String host, int port) throws IOException {
    super(host, port);
  }

  // TODO: throws custorm error
  public void announce(int listeningPort, ArrayList<FileInfos> files, ArrayList<String> leech_keys) throws IOException {

    String toSend = "announce listen " + listeningPort;
    // Files
    if (files.size() != 0) {
      toSend += " seed [";
      for (FileInfos file : files) {
        toSend += " " + file.toString();
      }
      toSend += " ]";
    }

    // Leechs
    if (leech_keys.size() != 0) {
      toSend += " leech [";
      for (String key : leech_keys) {
        toSend += " " + key.toString();
      }
      toSend += " ]";
    }

    send(toSend);
    read();

  }

  public void bye() throws IOException {
    send("bye");
    read();
  }

  @Override
  public void close() throws IOException {
    bye();
    super.close();
  }

}
