package org.eirb.eirbone.parser;

import org.eirb.eirbone.Core;
import org.eirb.eirbone.utils.Criterion;
import org.eirb.eirbone.utils.CriterionType;
import org.eirb.eirbone.utils.FileInfos;

import java.util.ArrayList;

public final class CLIParser {

  public static void parse(Core core, String line) {
    if (core == null || line == null) {
      return;
    }

    String trimmed = line.trim();
    if (trimmed.isEmpty()) {
      return;
    }

    String normalized = trimmed.replace("[", " [ ").replace("]", " ] ");
    String[] tokens = normalized.trim().split("\\s+");
    String command = tokens[0].toLowerCase();

    try {
      switch (command) {
        case "announce":
          parseAnnounce(core, tokens);
          break;
        case "look":
          parseLook(core, tokens);
          break;
        case "bye":
          core.getTracker().bye();
          break;
        default:
          System.out.println("Unknown command: " + tokens[0]);
      }
    } catch (RuntimeException e) {
      System.out.println("Invalid command format: " + e.getMessage());
    }
  }

  private static void parseAnnounce(Core core, String[] tokens) {
    if (tokens.length < 3) {
      throw new IllegalArgumentException("announce listen <listeningPort> ...");
    }
    if (!tokens[1].equals("listen")) {
      throw new IllegalArgumentException("announce listen <listeningPort> ...");
    }

    int listeningPort = Integer.parseInt(tokens[2]);
    ArrayList<FileInfos> files = new ArrayList<>();
    ArrayList<String> leechKeys = new ArrayList<>();

    int i = 3;
    while (i < tokens.length) {
      String section = tokens[i].toLowerCase();
      i++;

      if (i >= tokens.length || !tokens[i].equals("[")) {
        throw new IllegalArgumentException(section + " section must start with [");
      }
      i++;

      if ("seed".equals(section)) {
        int values = 0;
        while (i < tokens.length && !tokens[i].equals("]")) {
          if (i + 3 >= tokens.length || tokens[i + 3].equals("]")) {
            throw new IllegalArgumentException("seed must contain groups of 4 values");
          }
          String filename = tokens[i];
          int length = Integer.parseInt(tokens[i + 1]);
          int pieceSize = Integer.parseInt(tokens[i + 2]);
          String key = tokens[i + 3];
          files.add(new FileInfos(filename, length, pieceSize, key));
          i += 4;
          values += 4;
        }
        if (values == 0) {
          throw new IllegalArgumentException("seed section is empty");
        }
      } else if ("leech".equals(section)) {
        while (i < tokens.length && !tokens[i].equals("]")) {
          leechKeys.add(tokens[i]);
          i++;
        }
      } else {
        throw new IllegalArgumentException("unknown announce section: " + section);
      }

      if (i >= tokens.length || !tokens[i].equals("]")) {
        throw new IllegalArgumentException(section + " section must end with ]");
      }
      i++;
    }

    core.getTracker().announce(listeningPort, files, leechKeys);
  }

  private static void parseLook(Core core, String[] tokens) {
    ArrayList<Criterion> criterions = new ArrayList<>();

    for (int i = 1; i < tokens.length; i++) {
      String token = tokens[i];
      if (token.startsWith("filename=")) {
        criterions.add(new Criterion(token.substring("filename=".length()), CriterionType.FILENAME_EQUAL));
      } else if (token.startsWith("filelength>")) {
        criterions.add(new Criterion(token.substring("filelength>".length()), CriterionType.FILE_LENGTH_GT));
      } else {
        throw new IllegalArgumentException("unknown look criterion: " + token);
      }
    }

    core.getTracker().look(criterions);
  }
}
