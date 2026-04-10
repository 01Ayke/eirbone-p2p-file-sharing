package org.eirb.eirbone.server;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

import org.eirb.eirbone.utils.FileInfos;

public class TCPServer implements AutoCloseable {
  
  private final int port;
  private ServerSocket serverSocket;
  private ExecutorService pool;
  private volatile boolean running;
  private final Map<String, PeerFile> files = new ConcurrentHashMap<>();

  public TCPServer(int port) {
    this.port = port;
  }

  public void addFile(FileInfos file, String filePath) {
    PeerFile peerFile = new PeerFile(file, filePath);
    peerFile.setAllPiecesAvailable();
    files.put(file.getKey(), peerFile);
  }

  public void addFile(FileInfos file) {
    // Pour compatibilité: créer le fichier de test et l'ajouter
    try {
      String filePath = org.eirb.eirbone.utils.FileManager.createTestFile(file.getKey() + ".dat", file.getLength());
      addFile(file, filePath);
    } catch (IOException e) {
      System.err.println("[TCPServer] Erreur création fichier: " + e.getMessage());
    }
  }

  public void start() throws IOException {
    if (running) {
      return;
    }

    serverSocket = new ServerSocket(port);
    pool = Executors.newFixedThreadPool(4);
    running = true;

    Thread acceptThread = new Thread(() -> {
      while (running) {
        try {
          Socket client = serverSocket.accept();
          pool.execute(() -> handleClient(client));
        } catch (IOException e) {
          if (running) {
            System.err.println("[TCPServer] accept error: " + e.getMessage());
          }
        } catch (RejectedExecutionException e) {
          System.err.println("[TCPServer] Connection rejected: " + e.getMessage());
        }
      }
    });
    acceptThread.setDaemon(true);
    acceptThread.start();
    System.out.println("[TCPServer] Started on port " + port);
  }

  private void handleClient(Socket socket) {
    String remote = socket.getRemoteSocketAddress().toString();
    System.out.println("[TCPServer] Connected: " + remote);

    try (
      BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
      PrintWriter writer = new PrintWriter(new BufferedWriter(new OutputStreamWriter(socket.getOutputStream())), true);
      OutputStream out = socket.getOutputStream()
    ) {
      String line;
      while ((line = reader.readLine()) != null) {
        line = line.trim();
        if (line.isEmpty()) {
          continue;
        }
        System.out.println("[TCPServer] Received: " + line);

        if (line.startsWith("interested ")) {
          handleInterested(line, writer);
        } else if (line.startsWith("getpieces ")) {
          handleGetPieces(line, writer, out);
        } else if (line.startsWith("have ")) {
          // Acknowledge receipt of a remote buffer map, no reply required
          System.out.println("[TCPServer] Remote have: " + line);
        } else if (line.equals("bye") || line.equals("BYE")) {
          writer.println("bye");
          break;
        } else {
          writer.println("error unknown command");
        }
      }
    } catch (IOException e) {
      System.err.println("[TCPServer] Client error " + remote + ": " + e.getMessage());
    } finally {
      try {
        socket.close();
      } catch (IOException ignored) {
      }
      System.out.println("[TCPServer] Disconnected: " + remote);
    }
  }

  private void handleInterested(String line, PrintWriter writer) {
    String[] parts = line.split(" ", 3);
    if (parts.length < 2) {
      writer.println("error invalid interested");
      return;
    }

    String key = parts[1];
    PeerFile file = files.get(key);
    if (file == null) {
      writer.println("error unknown file");
      return;
    }

    String bufferMap = file.getBase64BufferMap();
    writer.println("have " + key + " " + bufferMap);
  }

  private void handleGetPieces(String line, PrintWriter writer, OutputStream out) throws IOException {
    int bracketStart = line.indexOf('[');
    int bracketEnd = line.indexOf(']');
    if (bracketStart < 0 || bracketEnd < 0 || bracketEnd <= bracketStart) {
      writer.println("error invalid getpieces");
      return;
    }

    String header = line.substring(0, bracketStart).trim();
    String[] headerParts = header.split(" ");
    if (headerParts.length < 2) {
      writer.println("error invalid getpieces");
      return;
    }

    String key = headerParts[1];
    PeerFile file = files.get(key);
    if (file == null) {
      writer.println("error unknown file");
      return;
    }

    String indicesPart = line.substring(bracketStart + 1, bracketEnd).trim();
    if (indicesPart.isEmpty()) {
      writer.println("error invalid getpieces");
      return;
    }

    String[] indexTokens = indicesPart.split(" ");
    List<Integer> indices = new ArrayList<>();
    for (String token : indexTokens) {
      try {
        indices.add(Integer.parseInt(token));
      } catch (NumberFormatException e) {
        writer.println("error invalid index");
        return;
      }
    }

    StringBuilder responseHeader = new StringBuilder("data ");
    responseHeader.append(key).append(" [");
    for (int i = 0; i < indices.size(); i++) {
      if (i > 0) {
        responseHeader.append(" ");
      }
      responseHeader.append(indices.get(i)).append(" 1024");
    }
    responseHeader.append("]");
    writer.println(responseHeader.toString());
    writer.flush();

    byte[] payload;
    try {
      payload = file.readPieces(indices);
    } catch (IOException e) {
      System.err.println("[TCPServer] Erreur lecture fichier: " + e.getMessage());
      payload = new byte[0];
    }
    out.write(payload);
    out.flush();
  }

  public void stop() {
    running = false;
    if (pool != null) {
      pool.shutdownNow();
    }
    if (serverSocket != null) {
      try {
        serverSocket.close();
      } catch (IOException ignored) {
      }
    }
    System.out.println("[TCPServer] Stopped");
  }

  @Override
  public void close() {
    stop();
  }

  private static class PeerFile {
    private final FileInfos file;
    private final String filePath;
    private final byte[] bufferMap;

    PeerFile(FileInfos file, String filePath) {
      this.file = file;
      this.filePath = filePath;
      int pieceCount = (int) Math.ceil(file.getLength() / (double) file.getPieceSize());
      int bytes = (pieceCount + 7) / 8;
      bufferMap = new byte[bytes];
    }

    void setAllPiecesAvailable() {
      for (int i = 0; i < bufferMap.length; i++) {
        bufferMap[i] = (byte) 0xFF;
      }
    }

    String getBase64BufferMap() {
      return java.util.Base64.getEncoder().encodeToString(bufferMap);
    }

    byte[] createDummyPayload(int size) {
      byte[] payload = new byte[size];
      for (int i = 0; i < size; i++) {
        payload[i] = 0;
      }
      return payload;
    }

    /**
     * Lit des pieces du fichier depuis le disque
     */
    byte[] readPieces(java.util.List<Integer> indices) throws IOException {
      java.util.List<byte[]> pieces = new java.util.ArrayList<>();
      int totalSize = 0;

      System.out.println("[PeerFile] Lecture " + indices.size() + " pieces du fichier: " + filePath);
      
      java.io.File f = new java.io.File(filePath);
      if (!f.exists()) {
        System.err.println("[PeerFile] ERREUR: Fichier n'existe pas: " + filePath);
        throw new IOException("Fichier n'existe pas: " + filePath);
      }

      for (int index : indices) {
        long offset = (long) index * file.getPieceSize();
        int pieceSize = file.getPieceSize();
        
        // Vérifier que nous ne dépassons pas la taille du fichier
        if (offset >= file.getLength()) {
          System.err.println("[PeerFile] Piece index " + index + " dépasse la taille du fichier");
          continue;
        }

        int bytesToRead = (int) Math.min(pieceSize, file.getLength() - offset);
        System.out.println("[PeerFile] Lecture piece " + index + " offset=" + offset + " size=" + bytesToRead);
        
        byte[] piece = org.eirb.eirbone.utils.FileManager.readFileRange(filePath, offset, bytesToRead);
        pieces.add(piece);
        totalSize += piece.length;
      }

      // Concaténer tous les pieces
      byte[] result = new byte[totalSize];
      int pos = 0;
      for (byte[] piece : pieces) {
        System.arraycopy(piece, 0, result, pos, piece.length);
        pos += piece.length;
      }
      System.out.println("[PeerFile] Retourne " + result.length + " octets au total");
      return result;
    }
  }
}
