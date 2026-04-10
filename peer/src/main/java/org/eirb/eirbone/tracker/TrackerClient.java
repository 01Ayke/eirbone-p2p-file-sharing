// src/main/java/org/eirb/eirbone/tracker/TrackerClient.java
package org.eirb.eirbone.tracker;

import java.io.*;
import java.net.*;
import java.util.*;
import org.eirb.eirbone.utils.FileInfos;


public class TrackerClient {

    private final String trackerHost;
    private final int trackerPort;
    private Socket socket;
    private BufferedReader reader;
    private PrintWriter writer;

    public TrackerClient(String trackerHost, int trackerPort) {
        this.trackerHost = trackerHost;
        this.trackerPort = trackerPort;
    }

    // Ouvre la connexion TCP au tracker
    public void connect() throws IOException {
        socket = new Socket(trackerHost, trackerPort);
        reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
        writer = new PrintWriter(new OutputStreamWriter(socket.getOutputStream()), true);
        System.out.println("[TrackerClient] Connecté à " + trackerHost + ":" + trackerPort);
    }

    // annonce note présence au tracker, avec les fichiers que nous partageons et ceux que nous cherchons
    public void announce(int listenPort, ArrayList<FileInfos> files, ArrayList<String> leechKeys) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("announce listen ").append(listenPort).append(" seed [");
        for (int i = 0; i < files.size(); i++) {
            if (i > 0) sb.append(" ");
            sb.append(files.get(i).toString());
        }
        sb.append("] leech [");
        for (int i = 0; i < leechKeys.size(); i++) {
            if (i > 0) sb.append(" ");
            sb.append(leechKeys.get(i));
        }
        sb.append("]");
        writer.println(sb.toString());
        String response = reader.readLine();
        // Note: méthode changée pour ne pas retourner boolean, juste envoyer
    }

    // cherche des fichiers disponibles sur le tracker, filtrer par nom
    public List<FileInfo> look(String filename) throws IOException {
        if (filename != null && !filename.isEmpty()) {
            writer.println("look [filename=" + filename + "]");
        } else {
            writer.println("look []");
        }

        String response = reader.readLine();
        return parseFileList(response);
    }

    // retourne liste de ip:port des peers qui ont un fichier
    public ArrayList<String> getFile(String key) throws IOException {
        writer.println("getfile " + key);
        String response = reader.readLine();
        // Format : peers <key> [ip:port ip:port ...]
        return parsePeerList(response);
    }

    // met à jour nos listes seed/leech auprès du tracker.
    public boolean update(List<String> seedKeys, List<String> leechKeys) throws IOException {
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
        return "ok".equals(response);
    }

    // se déconnecte du tracker
    public void disconnect() {
        try {
            if (writer != null) writer.println("bye");
            if (socket != null && !socket.isClosed()) socket.close();
        } catch (IOException e) {
            System.err.println("[TrackerClient] Erreur déconnexion : " + e.getMessage());
        }
    }

    // parseur de réponse "list [filename length pieceSize key ...]"
    private List<FileInfo> parseFileList(String response) {
        List<FileInfo> result = new ArrayList<>();
        if (response == null || !response.startsWith("list [")) return result;

        int start = response.indexOf('[') + 1;
        int end = response.lastIndexOf(']');
        if (start <= 0 || end <= start) return result;
        String content = response.substring(start, end).trim();
        if (content.isEmpty()) return result;
        String[] tokens = content.split(" ");
        for (int i = 0; i + 3 < tokens.length; i += 4) {
            FileInfo f = new FileInfo();
            f.filename = tokens[i];
            f.length = Long.parseLong(tokens[i + 1]);
            f.pieceSize = Integer.parseInt(tokens[i + 2]);
            f.key = tokens[i + 3];
            result.add(f);
        }
        return result;
    }

    // parseur de réponse "peers <key> [ip:port ip:port ...]"
    private ArrayList<String> parsePeerList(String response) {
        ArrayList<String> result = new ArrayList<>();
        if (response == null || !response.startsWith("peers ")) return result;

        int start = response.indexOf('[') + 1;
        int end   = response.lastIndexOf(']');
        if (start <= 0 || end <= start) return result;

        String content = response.substring(start, end).trim();
        if (content.isEmpty()) return result;

        for (String peer : content.split(" ")) {
            if (!peer.isEmpty()) result.add(peer);
        }
        return result;
    }

    // classe pour représenter les infos d'un fichier
    public static class FileInfo {
        public String filename;
        public long   length;
        public int    pieceSize;
        public String key;

        @Override
        public String toString() {
            return filename + " (" + length + " octets, key=" + key + ")";
        }
    }
}