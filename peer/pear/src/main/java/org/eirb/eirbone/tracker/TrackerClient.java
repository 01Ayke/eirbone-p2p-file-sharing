package org.eirb.eirbone.tracker;

import org.eirb.eirbone.config.Config;
import org.eirb.eirbone.storage.FileManager;

import java.io.*;
import java.net.Socket;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class TrackerClient {

    private final Config      config;
    private final FileManager fileManager;

    private Socket         socket;
    private BufferedReader reader;
    private PrintWriter    writer;

    public TrackerClient(Config config, FileManager fileManager) {
        this.config      = config;
        this.fileManager = fileManager;
    }

    public void connect() throws IOException {
        socket = new Socket(config.trackerAddress, config.trackerPort);
        reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
        writer = new PrintWriter(new OutputStreamWriter(socket.getOutputStream()), true);
        connected = true;
        System.out.println("[INFO] ConnectÃ© au tracker " + config.trackerAddress + ":" + config.trackerPort);
    }

    private volatile boolean connected = false;

    public boolean isConnected() { return connected; }

    public synchronized void announce() throws IOException {
        if (!connected) throw new IOException("Non connectÃ© au tracker");
        List<FileManager.FileInfo> files = fileManager.listSharedFiles();
        StringBuilder sb = new StringBuilder();
        sb.append("announce listen ").append(config.peerPort);
        sb.append(" seed [");
        for (int i = 0; i < files.size(); i++) {
            if (i > 0) sb.append(" ");
            sb.append(files.get(i).toString());
        }
        sb.append("] leech []");
        String msg = sb.toString();
        System.out.println("[DEBUG] >> " + msg);
        writer.println(msg);
        String response = reader.readLine();
        System.out.println("[DEBUG] << " + response);
        if (!"ok".equals(response)) {
            throw new IOException("Announce refusÃ© par le tracker : " + response);
        }
        System.out.println("[INFO] Announce OK â€” " + files.size() + " fichier(s) partagÃ©(s)");
    }

    // ReprÃ©sente un peer retournÃ© par getfile
    public static class PeerAddress {
        public String ip;
        public int    port;

        public PeerAddress(String ip, int port) {
            this.ip   = ip;
            this.port = port;
        }

        @Override public String toString() { return ip + ":" + port; }
    }

    // ReprÃ©sente un fichier retournÃ© par look
    public static class NetworkFile {
        public String filename;
        public long   size;
        public int    pieceSize;
        public String md5;
    }

    public synchronized String look(String filename) throws IOException {
        if (!connected) throw new IOException("Non connectÃ© au tracker");
        String msg = "look [filename=" + filename + "]";
        writer.println(msg);
        System.out.println("[DEBUG] >> " + msg);
        String response = reader.readLine();
        System.out.println("[DEBUG] << " + response);
        return response;
    }

    // look et parse â€” retourne une liste de fichiers disponibles sur le rÃ©seau
    public List<NetworkFile> lookFiles(String search) throws IOException {
        String response = look(search);
        return parseNetworkFiles(response);
    }

    private List<NetworkFile> parseNetworkFiles(String response) {
        List<NetworkFile> files = new ArrayList<>();
        Set<String> seenMd5 = new HashSet<>();
        if (response == null || !response.startsWith("list [")) return files;
        int start = response.indexOf('[') + 1;
        int end   = response.lastIndexOf(']');
        if (start >= end) return files;
        String inner = response.substring(start, end).trim();
        if (inner.isEmpty()) return files;
        String[] tokens = inner.split(" ");
        for (int i = 0; i + 3 < tokens.length; i += 4) {
            NetworkFile f = new NetworkFile();
            f.filename  = tokens[i];
            try {
                f.size      = Long.parseLong(tokens[i + 1]);
                f.pieceSize = Integer.parseInt(tokens[i + 2]);
            } catch (NumberFormatException ignored) { continue; }
            f.md5 = tokens[i + 3];
            if (seenMd5.add(f.md5)) {
                files.add(f);
            }
        }
        return files;
    }

    public synchronized List<PeerAddress> getFile(String md5) throws IOException {
        if (!connected) throw new IOException("Non connectÃ© au tracker");
        String msg = "getfile " + md5;
        writer.println(msg);
        System.out.println("[DEBUG] >> " + msg);
        String response = reader.readLine();
        System.out.println("[DEBUG] << " + response);

        List<PeerAddress> peers = new ArrayList<>();
        int start = response.indexOf('[');
        int end   = response.indexOf(']');
        if (start == -1 || end == -1 || end <= start + 1) return peers;
        String res = response.substring(start + 1, end).trim();
        if (res.isEmpty()) return peers;
        for (String token : res.split(" ")) {
            String[] parts = token.split(":");
            if (parts.length == 2) {
                try {
                    peers.add(new PeerAddress(parts[0], Integer.parseInt(parts[1])));
                } catch (NumberFormatException ignored) {}
            }
        }
        return peers;
    }

    public synchronized void update(List<String> seedKeys, List<String> leechKeys) throws IOException {
        StringBuilder sb = new StringBuilder("update seed [");
        for (int i = 0; i < seedKeys.size(); i++) {
            if (i > 0) sb.append(" ");
            sb.append(seedKeys.get(i));
        }
        sb.append("] leech [");
        for (int i = 0; i < leechKeys.size(); i++) {
            if (i > 0) sb.append(" ");
            sb.append(leechKeys.get(i));
        }
        sb.append("]");
        writer.println(sb.toString());
        String response = reader.readLine();
        if (!"ok".equals(response)) {
            System.err.println("[WARN] Update tracker : rÃ©ponse inattendue : " + response);
        }
    }

    public synchronized void bye() {
        if (!connected) return;
        try {
            writer.println("bye");
            reader.readLine();
        } catch (Exception e) {}
    }

    public void disconnect() {
        connected = false;
        try { if (socket != null) socket.close(); } catch (Exception e) {}
    }
}
