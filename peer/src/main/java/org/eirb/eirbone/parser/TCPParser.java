package org.eirb.eirbone.parser;

import java.util.ArrayList;

//Parser TCPResponse
public class TCPParser {

  public void parse(String data) {
    String trimmed = data.trim();
    if (trimmed.isEmpty()) {
      return;
    }
    String normalized = trimmed.replace("[", " [ ").replace("]", " ] ");
    String[] tokens = normalized.trim().split("\\s+");
    String command = tokens[0];

    switch (command) {
      case "list":
        // TODO:parseList(data);
        break;
      case "peers":
        // TODO: parsePeers(data);
        break;
      default:
        // TODO: throw error
        break;
    }
  }

  public ArrayList<String> parseBracketList(String data) {

    ArrayList<String> items = new ArrayList<>();
    if (data == null) {
      return items;
    }

    int start = data.indexOf('[');
    int end = data.lastIndexOf(']');
    // TODO:throw error
    if (start == -1 || end == -1 || end <= start) {
      return items;
    }

    String inside = data.substring(start + 1, end).trim();
    if (inside.isEmpty()) {
      return items;
    }

    String[] tokens = inside.split("\\s+");
    for (String token : tokens) {
      if (!token.isBlank()) {
        items.add(token);
      }
    }
    return items;
  }
}
