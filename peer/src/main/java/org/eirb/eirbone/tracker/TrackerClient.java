package org.eirb.eirbone.tracker;

import org.eirb.eirbone.config.Config;
import org.eirb.eirbone.storage.FileManager;

import java.io.*;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;

public class TrackerClient {

    private final Config      config;
    private final FileManager fileManager;

    // La socket TCP reste ouverte pendant toute la durée de vie du peer
    private Socket         socket;
    private BufferedReader reader;
    private PrintWriter    writer;

    public TrackerClient(Config config, FileManager fileManager) {
        this.config      = config;
        this.fileManager = fileManager;
    }

    // connecte le client au tracker en TCP et initialise reader/writer
    public void connect() throws IOException {
        socket = new Socket(config.trackerAddress, config.trackerPort);        
        reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
        writer = new PrintWriter(new OutputStreamWriter(socket.getOutputStream()), true);
        System.out.println("[INFO] Connecté au tracker " + config.trackerAddress + ":" + config.trackerPort);    
    }

    // announce listen <port> seed [...] leech []
    public void announce() throws IOException {
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

        String response = reader.readLine(); // bloque jusqu'à recevoir "ok"
        System.out.println("[DEBUG] << " + response);
        if (!"ok".equals(response)) {
            throw new IOException("Announce refusé par le tracker : " + response);
        }
        System.out.println("[INFO] Announce OK — " + files.size() + " fichier(s) partagé(s)");
    }

    // Représente un peer retourné par getfile
    public static class PeerAddress {
        public String ip;
        public int    port;

        public PeerAddress(String ip, int port) {
            this.ip   = ip;
            this.port = port;
        }

        @Override public String toString() { return ip + ":" + port; }
    }

    // look [filename=<name>]
    // retourne la liste des fichiers
    public String look(String filename) throws IOException {
        String msg = "look [filename=" + filename + "]";
        writer.println(msg);
        System.out.println("[DEBUG] >> " + msg);
        String response = reader.readLine();
        System.out.println("[DEBUG] << " + response);
        return response;
    }

    // getfile <md5>
    // retourne la liste des peers qui ont ce fichier
    public List<PeerAddress> getFile(String md5) throws IOException {
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
                peers.add(new PeerAddress(parts[0], Integer.parseInt(parts[1])));
            }
        }
        return peers;
    }

    // update seed [...] leech [...]
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
            System.err.println("[WARN] Update tracker : réponse inattendue : " + response);
        }
    }

    // bye
    public void bye() {
        try {
            writer.println("bye");
            reader.readLine(); // attend "bye" en retour
        } catch (Exception e) {}
    }

    public void disconnect() {
        try { socket.close(); } catch (Exception e) {}
    }
}