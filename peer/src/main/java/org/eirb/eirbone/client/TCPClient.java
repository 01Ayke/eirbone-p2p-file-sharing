package org.eirb.eirbone.client;

import java.io.*;
import java.net.*;

public abstract class TCPClient {
  private Socket socket;
  private BufferedReader plec;
  private PrintWriter pred;

  public TCPClient(String host, int port) throws IOException {

    socket = new Socket(host, port);
    System.out.println("SOCKET = " + socket);
    plec = new BufferedReader(new InputStreamReader(socket.getInputStream()));
    pred = new PrintWriter(new BufferedWriter(new OutputStreamWriter(socket.getOutputStream())), true);

  }

  public void close() throws IOException {
    pred.close();
    plec.close();
    socket.close();
  }

  public void send(String data) {
    System.out.println("> " + data);
    pred.println(data);
  }

  public String read() throws IOException {
    String data = plec.readLine();
    System.out.println("< " + data);
    return data;
  }

}
