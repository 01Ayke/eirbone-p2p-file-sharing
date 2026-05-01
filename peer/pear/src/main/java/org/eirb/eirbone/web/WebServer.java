package org.eirb.eirbone.web;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.eirb.eirbone.config.Config;
import org.eirb.eirbone.download.DownloadManager;
import org.eirb.eirbone.storage.FileManager;
import org.eirb.eirbone.tracker.TrackerClient;

import java.io.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.Locale;
import java.util.concurrent.Executors;

public class WebServer {

    private final Config          config;
    private final TrackerClient   tracker;
    private final DownloadManager downloadManager;
    private final FileManager     fileManager;
    private HttpServer            server;

    public WebServer(Config config, TrackerClient tracker,
                     DownloadManager downloadManager, FileManager fileManager) {
        this.config          = config;
        this.tracker         = tracker;
        this.downloadManager = downloadManager;
        this.fileManager     = fileManager;
    }

    public void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(config.webPort), 0);
        server.setExecutor(Executors.newCachedThreadPool());

        server.createContext("/", this::handleStatic);
        server.createContext("/api/files",          this::handleApiFiles);
        server.createContext("/api/peers",           this::handleApiPeers);
        server.createContext("/api/download",        this::handleApiDownload);
        server.createContext("/api/downloads",       this::handleApiDownloads);
        server.createContext("/api/shared",          this::handleApiShared);
        server.createContext("/api/status",          this::handleApiStatus);
        server.createContext("/api/tracker/connect", this::handleApiTrackerConnect);

        server.start();
        System.out.println("[WEB] Interface disponible sur http://localhost:" + config.webPort);
    }

    public void stop() {
        if (server != null) server.stop(0);
    }

    // -------------------------------------------------------------------------
    // Static assets
    // -------------------------------------------------------------------------

    private void handleStatic(HttpExchange ex) throws IOException {
        if (!ex.getRequestMethod().equals("GET")) { send(ex, 405, "text/plain", "Method Not Allowed"); return; }
        InputStream is = getClass().getResourceAsStream("/web/index.html");
        if (is == null) { send(ex, 404, "text/plain", "UI not found"); return; }
        byte[] body = is.readAllBytes();
        ex.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
        ex.sendResponseHeaders(200, body.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(body); }
    }

    // -------------------------------------------------------------------------
    // API: GET /api/files?search=...
    // -------------------------------------------------------------------------

    private void handleApiFiles(HttpExchange ex) throws IOException {
        if (!ex.getRequestMethod().equals("GET")) { send(ex, 405, "application/json", "{}"); return; }
        String query  = ex.getRequestURI().getQuery();
        String search = queryParam(query, "search");
        try {
            List<TrackerClient.NetworkFile> files = tracker.lookFiles(search == null ? "" : search);
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < files.size(); i++) {
                TrackerClient.NetworkFile f = files.get(i);
                if (i > 0) sb.append(",");
                sb.append("{")
                  .append(jStr("filename", f.filename)).append(",")
                  .append(jNum("size", f.size)).append(",")
                  .append(jNum("pieceSize", f.pieceSize)).append(",")
                  .append(jStr("md5", f.md5))
                  .append("}");
            }
            sb.append("]");
            sendJson(ex, 200, sb.toString());
        } catch (Exception e) {
            sendJson(ex, 500, err(e.getMessage()));
        }
    }

    // -------------------------------------------------------------------------
    // API: GET /api/peers?md5=...
    // -------------------------------------------------------------------------

    private void handleApiPeers(HttpExchange ex) throws IOException {
        if (!ex.getRequestMethod().equals("GET")) { send(ex, 405, "application/json", "{}"); return; }
        String md5 = queryParam(ex.getRequestURI().getQuery(), "md5");
        if (md5 == null || md5.isEmpty()) { sendJson(ex, 400, err("md5 manquant")); return; }
        try {
            List<TrackerClient.PeerAddress> peers = tracker.getFile(md5);
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < peers.size(); i++) {
                if (i > 0) sb.append(",");
                sb.append("{")
                  .append(jStr("ip", peers.get(i).ip)).append(",")
                  .append(jNum("port", peers.get(i).port))
                  .append("}");
            }
            sb.append("]");
            sendJson(ex, 200, sb.toString());
        } catch (Exception e) {
            sendJson(ex, 500, err(e.getMessage()));
        }
    }

    // -------------------------------------------------------------------------
    // API: POST /api/download
    // body: {"filename":"...","md5":"...","size":N,"peerIp":"...","peerPort":N}
    // -------------------------------------------------------------------------

    private void handleApiDownload(HttpExchange ex) throws IOException {
        if (!ex.getRequestMethod().equals("POST")) { send(ex, 405, "application/json", "{}"); return; }
        String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        try {
            String filename = jsonStr(body, "filename");
            String md5      = jsonStr(body, "md5");
            long   size     = jsonLong(body, "size");
            String peerIp   = jsonStr(body, "peerIp");
            int    peerPort = (int) jsonLong(body, "peerPort");
            if (filename == null || md5 == null || peerIp == null) {
                sendJson(ex, 400, err("Champs manquants : filename, md5, peerIp requis")); return;
            }
            String taskId = downloadManager.downloadWithPeer(filename, md5, size, peerIp, peerPort);
            sendJson(ex, 200, "{" + jStr("taskId", taskId) + "}");
        } catch (Exception e) {
            sendJson(ex, 500, err(e.getMessage()));
        }
    }

    // -------------------------------------------------------------------------
    // API: GET /api/downloads
    // -------------------------------------------------------------------------

    private void handleApiDownloads(HttpExchange ex) throws IOException {
        if (!ex.getRequestMethod().equals("GET")) { send(ex, 405, "application/json", "{}"); return; }
        Collection<DownloadManager.DownloadTask> tasks = downloadManager.getTasks();
        // sort by startTime descending
        List<DownloadManager.DownloadTask> sorted = new ArrayList<>(tasks);
        sorted.sort((a, b) -> Long.compare(b.startTime, a.startTime));
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < sorted.size(); i++) {
            DownloadManager.DownloadTask t = sorted.get(i);
            if (i > 0) sb.append(",");
            sb.append("{")
              .append(jStr("id", t.id)).append(",")
              .append(jStr("filename", t.filename)).append(",")
              .append(jStr("md5", t.md5)).append(",")
              .append(jNum("totalSize", t.totalSize)).append(",")
              .append(jStr("peerIp", t.peerIp)).append(",")
              .append(jNum("peerPort", t.peerPort)).append(",")
              .append(jNum("downloadedPieces", t.downloadedPieces)).append(",")
              .append(jNum("totalPieces", t.totalPieces)).append(",")
              .append("\"progress\":").append(String.format(Locale.ROOT, "%.1f", t.progressPercent())).append(",")
              .append(jStr("status", t.status)).append(",")
              .append(jStr("error", t.errorMessage == null ? "" : t.errorMessage))
              .append("}");
        }
        sb.append("]");
        sendJson(ex, 200, sb.toString());
    }

    // -------------------------------------------------------------------------
    // API: GET /api/shared
    // -------------------------------------------------------------------------

    private void handleApiShared(HttpExchange ex) throws IOException {
        if (!ex.getRequestMethod().equals("GET")) { send(ex, 405, "application/json", "{}"); return; }
        try {
            List<FileManager.FileInfo> files = fileManager.listSharedFiles();
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < files.size(); i++) {
                FileManager.FileInfo f = files.get(i);
                if (i > 0) sb.append(",");
                sb.append("{")
                  .append(jStr("filename", f.filename)).append(",")
                  .append(jNum("size", f.length)).append(",")
                  .append(jStr("md5", f.md5))
                  .append("}");
            }
            sb.append("]");
            sendJson(ex, 200, sb.toString());
        } catch (Exception e) {
            sendJson(ex, 500, err(e.getMessage()));
        }
    }

    // -------------------------------------------------------------------------
    // API: GET /api/status
    // -------------------------------------------------------------------------

    private void handleApiStatus(HttpExchange ex) throws IOException {
        if (!ex.getRequestMethod().equals("GET")) { send(ex, 405, "application/json", "{}"); return; }
        String json = "{" + jStr("trackerAddress", config.trackerAddress) + ","
                         + jNum("trackerPort", config.trackerPort) + ","
                         + jNum("peerPort", config.peerPort) + ","
                         + jStr("sharedDir", config.sharedDir) + ","
                         + "\"trackerConnected\":" + tracker.isConnected() + "}";
        sendJson(ex, 200, json);
    }

    // -------------------------------------------------------------------------
    // API: POST /api/tracker/connect
    // body: {"address":"192.168.1.5","port":9000}
    // -------------------------------------------------------------------------

    private void handleApiTrackerConnect(HttpExchange ex) throws IOException {
        if (!ex.getRequestMethod().equals("POST")) { send(ex, 405, "application/json", "{}"); return; }
        String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        try {
            String address = jsonStr(body, "address");
            long   port    = jsonLong(body, "port");
            if (address == null || address.isEmpty()) { sendJson(ex, 400, err("address manquant")); return; }
            if (port == 0) port = config.trackerPort;

            // déconnecter proprement si déjà connecté
            if (tracker.isConnected()) {
                tracker.bye();
                tracker.disconnect();
            }
            config.trackerAddress = address;
            config.trackerPort    = (int) port;
            tracker.connect();
            tracker.announce();
            sendJson(ex, 200, "{\"ok\":true," + jStr("address", address) + "," + jNum("port", port) + "}");
        } catch (Exception e) {
            sendJson(ex, 500, err(e.getMessage()));
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private void sendJson(HttpExchange ex, int code, String json) throws IOException {
        send(ex, code, "application/json; charset=utf-8", json);
    }

    private void send(HttpExchange ex, int code, String contentType, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", contentType);
        ex.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        ex.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(bytes); }
    }

    private static String queryParam(String query, String key) {
        if (query == null) return null;
        for (String pair : query.split("&")) {
            String[] kv = pair.split("=", 2);
            if (kv.length == 2 && kv[0].equals(key)) {
                return java.net.URLDecoder.decode(kv[1], StandardCharsets.UTF_8);
            }
        }
        return null;
    }

    private static String err(String msg) {
        return "{" + jStr("error", msg == null ? "unknown" : msg) + "}";
    }

    private static String jStr(String k, String v) {
        return "\"" + escJson(k) + "\":\"" + escJson(v == null ? "" : v) + "\"";
    }

    private static String jNum(String k, long v) {
        return "\"" + escJson(k) + "\":" + v;
    }

    private static String escJson(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }

    // minimal JSON field extractor for POST bodies
    private static String jsonStr(String json, String key) {
        String search = "\"" + key + "\"";
        int idx = json.indexOf(search);
        if (idx == -1) return null;
        int colon = json.indexOf(':', idx + search.length());
        if (colon == -1) return null;
        int q1 = json.indexOf('"', colon + 1);
        if (q1 == -1) return null;
        int q2 = json.indexOf('"', q1 + 1);
        if (q2 == -1) return null;
        return json.substring(q1 + 1, q2);
    }

    private static long jsonLong(String json, String key) {
        String search = "\"" + key + "\"";
        int idx = json.indexOf(search);
        if (idx == -1) return 0;
        int colon = json.indexOf(':', idx + search.length());
        if (colon == -1) return 0;
        int s = colon + 1;
        while (s < json.length() && (json.charAt(s) == ' ' || json.charAt(s) == '\t')) s++;
        int e = s;
        while (e < json.length() && (Character.isDigit(json.charAt(e)) || json.charAt(e) == '-')) e++;
        try { return Long.parseLong(json.substring(s, e)); } catch (NumberFormatException ex) { return 0; }
    }
}
