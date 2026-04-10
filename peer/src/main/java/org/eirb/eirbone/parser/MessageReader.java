package org.eirb.eirbone.parser;

import java.io.InputStream;
import java.io.IOException;

public class MessageReader {

  private final InputStream in;
  private final StringBuilder buffer = new StringBuilder();

  public MessageReader(InputStream in) {
    this.in = in;
  }

  public String readMessage() throws IOException {
    while (true) {

      int newlineIndex = buffer.indexOf("\n");
      if (newlineIndex != -1) {
        String message = buffer.substring(0, newlineIndex).trim();
        buffer.delete(0, newlineIndex + 1);
        return message;
      }

      byte[] chunk = new byte[1024];
      int bytesRead = in.read(chunk);

      if (bytesRead == -1) {
        return null;
      }

      // 3. Ajouter au buffer
      buffer.append(new String(chunk, 0, bytesRead));
    }
  }
}
