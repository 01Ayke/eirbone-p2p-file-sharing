package org.eirb.eirbone.client.peer;

import java.io.IOException;

import org.eirb.eirbone.client.TCPClient;

public class PeerClient extends TCPClient {
  PeerClient(String host, int port) throws IOException {
    super(host, port);
  };

  public void interesed(String key) {
    String toSend = "interesed " + key;
    send(toSend);
  }

}
