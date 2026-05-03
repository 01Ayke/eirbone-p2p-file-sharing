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

    // Ouvre la connexion TCP vers le tracker
    public void connect() throws IOException {
        socket = new Socket(config.trackerAddress, config.trackerPort);
        reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
        writer = new PrintWriter(new OutputStreamWriter(socket.getOutputStream()), true);
        connected = true;
        System.out.println("[INFO] Connecté au tracker " + config.trackerAddress + ":" + config.trackerPort);
    }

    private volatile boolean connected = false;

    public boolean isConnected() { return connected; }

    // Annonce ce peer au tracker avec la liste des fichiers partagés
    public synchronized void announce() throws IOException {
        if (!connected) throw new IOException("Non connecté au tracker");
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
            throw new IOException("Announce refusé par le tracker : " + response);
        }
        System.out.println("[INFO] Announce OK — " + files.size() + " fichier(s) partagé(s)");
    }

    // Représente l'adresse réseau d'un peer retournée par getfile
    public static class PeerAddress {
        public String ip;
        public int    port;

        public PeerAddress(String ip, int port) {
            this.ip   = ip;
            this.port = port;
        }

        @Override public String toString() { return ip + ":" + port; }
    }

    // Représente un fichier disponible sur le réseau, retourné par look
    public static class NetworkFile {
        public String filename;
        public long   size;
        public int    pieceSize;
        public String md5;
    }

    // Envoie une requête "look" au tracker et retourne la réponse brute
    public synchronized String look(String filename) throws IOException {
        if (!connected) throw new IOException("Non connecté au tracker");
        String msg = "look [filename=" + filename + "]";
        writer.println(msg);
        System.out.println("[DEBUG] >> " + msg);
        String response = reader.readLine();
        System.out.println("[DEBUG] << " + response);
        return response;
    }

    // Interroge le tracker et retourne la liste des fichiers correspondant à la recherche
    public List<NetworkFile> lookFiles(String search) throws IOException {
        String response = look(search);
        return parseNetworkFiles(response);
    }

    // Analyse la réponse "list [...]" du tracker et construit la liste de NetworkFile
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
            f.filename = tokens[i];
            try {
                f.size      = Long.parseLong(tokens[i + 1]);
                f.pieceSize = Integer.parseInt(tokens[i + 2]);
            } catch (NumberFormatException ignored) { continue; }
            f.md5 = tokens[i + 3];
            // dédoublonnage par MD5 : un seul fichier par empreinte
            if (seenMd5.add(f.md5)) {
                files.add(f);
            }
        }
        return files;
    }

    // Demande au tracker la liste des peers qui possèdent le fichier identifié par son MD5
    public synchronized List<PeerAddress> getFile(String md5) throws IOException {
        if (!connected) throw new IOException("Non connecté au tracker");
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

    // Met à jour la liste des fichiers seed et leech auprès du tracker
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
            System.err.println("[WARN] Mise à jour tracker : réponse inattendue : " + response);
        }
    }

    // Envoie "bye" pour fermer proprement la session côté tracker
    public synchronized void bye() {
        if (!connected) return;
        try {
            writer.println("bye");
            reader.readLine();
        } catch (Exception e) {}
    }

    // Ferme la connexion TCP sans envoyer "bye"
    public void disconnect() {
        connected = false;
        try { if (socket != null) socket.close(); } catch (Exception e) {}
    }
}
