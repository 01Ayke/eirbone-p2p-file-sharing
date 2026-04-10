package org.eirb.eirbone.peer;

import java.io.*;
import java.net.*;
import java.util.List;

/**
 * Client P2P : se connecte à un peer serveur pour télécharger des pièces.
 * Gère la déconnexion propre et les timeouts.
 */
public class PeerClient {

    private final String host;
    private final int    port;
    private Socket       socket;
    private BufferedReader reader;
    private PrintWriter    writer;
    private InputStream    rawIn;

    public PeerClient(String host, int port) {
        this.host = host;
        this.port = port;
    }

    public void connect() throws IOException {
        socket = new Socket();
        socket.connect(new InetSocketAddress(host, port), 5000); // timeout 5s
        socket.setSoTimeout(10000); // lecture : 10s max
        rawIn  = socket.getInputStream();
        reader = new BufferedReader(new InputStreamReader(rawIn));
        writer = new PrintWriter(new BufferedWriter(new OutputStreamWriter(socket.getOutputStream())), true);
        System.out.println("[PeerClient] Connecté à " + host + ":" + port);
    }

    /**
     * Envoie "interested <key>" et retourne la réponse "have <key> <buffermap>".
     */
    public String interested(String key) throws IOException {
        writer.println("interested " + key);
        return reader.readLine();
    }

    /**
     * Envoie "getpieces <key> [i1 i2 ...]" et lit le header + payload binaire.
     * Retourne le payload brut (toutes les pièces concaténées).
     */
    public byte[] getpieces(String key, List<Integer> indices) throws IOException {
        if (indices.isEmpty()) return new byte[0];

        StringBuilder cmd = new StringBuilder("getpieces ").append(key).append(" [");
        for (int i = 0; i < indices.size(); i++) {
            if (i > 0) cmd.append(" ");
            cmd.append(indices.get(i));
        }
        cmd.append("]");
        writer.println(cmd.toString());

        // Lire le header "data <key> [i1 size1 i2 size2 ...]"
        String header = reader.readLine();
        if (header == null || !header.startsWith("data ")) {
            throw new IOException("Réponse inattendue: " + header);
        }

        // Calculer la taille totale à lire depuis le header
        int totalBytes = parseTotalBytes(header);
        if (totalBytes <= 0) return new byte[0];

        // Lire exactement totalBytes octets (framing TCP)
        byte[] payload = new byte[totalBytes];
        int    received = 0;
        while (received < totalBytes) {
            int read = rawIn.read(payload, received, totalBytes - received);
            if (read < 0) throw new IOException("Connexion fermée avant fin du payload");
            received += read;
        }
        return payload;
    }

    /**
     * Parse "data <key> [i1 size1 i2 size2 ...]" et somme les tailles.
     */
    private int parseTotalBytes(String header) {
        int start = header.indexOf('[');
        int end   = header.lastIndexOf(']');
        if (start < 0 || end < 0) return 0;

        String content = header.substring(start + 1, end).trim();
        if (content.isEmpty()) return 0;

        String[] tokens = content.split(" ");
        int total = 0;
        for (int i = 1; i < tokens.length; i += 2) { // index size index size ...
            try { total += Integer.parseInt(tokens[i]); }
            catch (NumberFormatException ignored) {}
        }
        return total;
    }

    public void disconnect() {
        try {
            if (writer != null) writer.println("bye");
            if (socket != null && !socket.isClosed()) socket.close();
        } catch (IOException ignored) {}
        System.out.println("[PeerClient] Déconnecté de " + host + ":" + port);
    }
}
