package fr.oche.flechettes;

import android.content.res.AssetManager;

import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/** Petit serveur HTTP local : sert les pages et relaie les parties entre la télé et les téléphones. */
class Server extends Thread {

    private final ServerSocket socket;
    private final AssetManager assets;

    // Fléchettes : un état partagé
    private int rev = 0;
    private String data = "{\"g\":null}";

    // Loup-garou : actions des joueurs (lues par la télé) et vues publiques/privées (écrites par la télé)
    private final List<String> lgActs = new ArrayList<>();
    private int lgRev = 0;
    private String lgPub = "null";
    private final Map<String, String> lgPriv = new HashMap<>();

    Server(AssetManager assets, int port) throws IOException {
        this.assets = assets;
        socket = new ServerSocket();
        socket.setReuseAddress(true);
        socket.bind(new InetSocketAddress(port));
        setDaemon(true);
    }

    @Override
    public void run() {
        while (true) {
            try {
                final Socket s = socket.accept();
                new Thread(() -> handle(s)).start();
            } catch (IOException e) {
                return;
            }
        }
    }

    private synchronized String read() {
        return "{\"rev\":" + rev + ",\"d\":" + data + "}";
    }

    private synchronized String write(String body) {
        data = body;
        rev++;
        return read();
    }

    private synchronized void lgAct(String body) {
        if (lgActs.size() > 20000) lgActs.clear();
        lgActs.add(body);
    }

    private synchronized String lgActsSince(int since) {
        StringBuilder b = new StringBuilder();
        b.append("{\"seq\":").append(lgActs.size()).append(",\"a\":[");
        boolean first = true;
        for (int i = Math.max(0, since); i < lgActs.size(); i++) {
            if (!first) b.append(',');
            b.append(lgActs.get(i));
            first = false;
        }
        b.append("]}");
        return b.toString();
    }

    private synchronized void lgPublish(String body) throws Exception {
        JSONObject o = new JSONObject(body);
        lgPub = o.getJSONObject("pub").toString();
        lgPriv.clear();
        JSONObject p = o.optJSONObject("priv");
        if (p != null) {
            Iterator<String> it = p.keys();
            while (it.hasNext()) {
                String k = it.next();
                lgPriv.put(k, p.getJSONObject(k).toString());
            }
        }
        lgRev++;
    }

    private synchronized String lgView(String pid) {
        String priv = pid != null && lgPriv.containsKey(pid) ? lgPriv.get(pid) : "null";
        return "{\"rev\":" + lgRev + ",\"pub\":" + lgPub + ",\"priv\":" + priv + "}";
    }

    private static String param(String query, String name) {
        if (query == null) return null;
        for (String kv : query.split("&")) {
            int e = kv.indexOf('=');
            String k = e < 0 ? kv : kv.substring(0, e);
            if (k.equals(name)) return e < 0 ? "" : kv.substring(e + 1).replaceAll("[^A-Za-z0-9_-]", "");
        }
        return null;
    }

    private void handle(Socket s) {
        try (Socket sock = s) {
            sock.setSoTimeout(10000);
            boolean local = sock.getInetAddress() != null && sock.getInetAddress().isLoopbackAddress();
            InputStream in = new BufferedInputStream(sock.getInputStream());
            String line = readLine(in);
            if (line == null) return;
            String[] parts = line.split(" ");
            if (parts.length < 2) return;
            String method = parts[0];
            String path = parts[1];
            String query = null;
            int q = path.indexOf('?');
            if (q >= 0) { query = path.substring(q + 1); path = path.substring(0, q); }

            int length = 0;
            String h;
            while ((h = readLine(in)) != null && !h.isEmpty()) {
                int c = h.indexOf(':');
                if (c > 0 && h.substring(0, c).trim().equalsIgnoreCase("content-length")) {
                    try { length = Integer.parseInt(h.substring(c + 1).trim()); } catch (NumberFormatException ignored) { }
                }
            }

            OutputStream out = sock.getOutputStream();
            String body = null;
            if (method.equals("POST")) {
                if (length <= 0 || length > 1048576) { send(out, 400, "text/plain", bytes("bad request")); return; }
                byte[] b = new byte[length];
                int r = 0;
                while (r < length) {
                    int n = in.read(b, r, length - r);
                    if (n < 0) break;
                    r += n;
                }
                body = new String(b, 0, r, StandardCharsets.UTF_8).trim();
                if (!body.startsWith("{") || !body.endsWith("}")) { send(out, 400, "text/plain", bytes("bad request")); return; }
            }

            switch (path) {
                case "/state":
                    if (body != null) {
                        if (body.length() > 65536) { send(out, 400, "text/plain", bytes("too large")); return; }
                        send(out, 200, "application/json", bytes(write(body)));
                    } else send(out, 200, "application/json", bytes(read()));
                    break;
                case "/lg/act":
                    if (body == null || body.length() > 4096) { send(out, 400, "text/plain", bytes("bad request")); return; }
                    lgAct(body);
                    send(out, 200, "application/json", bytes("{\"ok\":true}"));
                    break;
                case "/lg/acts":
                    if (!local) { send(out, 403, "text/plain", bytes("forbidden")); return; }
                    int since = 0;
                    try { since = Integer.parseInt(param(query, "since")); } catch (Exception ignored) { }
                    send(out, 200, "application/json", bytes(lgActsSince(since)));
                    break;
                case "/lg/pub":
                    if (!local || body == null) { send(out, 403, "text/plain", bytes("forbidden")); return; }
                    lgPublish(body);
                    send(out, 200, "application/json", bytes("{\"ok\":true}"));
                    break;
                case "/lg/view":
                    send(out, 200, "application/json", bytes(lgView(param(query, "pid"))));
                    break;
                case "/":
                case "/index.html":
                    send(out, 200, "text/html; charset=utf-8", asset("index.html"));
                    break;
                case "/menu":
                    send(out, 200, "text/html; charset=utf-8", asset("menu.html"));
                    break;
                case "/loup":
                    send(out, 200, "text/html; charset=utf-8", asset("loup.html"));
                    break;
                case "/lg-engine.js":
                    send(out, 200, "application/javascript; charset=utf-8", asset("lg-engine.txt"));
                    break;
                default:
                    send(out, 404, "text/plain", bytes("not found"));
            }
        } catch (Exception ignored) { }
    }

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private byte[] asset(String name) throws IOException {
        try (InputStream in = assets.open(name)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toByteArray();
        }
    }

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') break;
            if (c != '\r') b.write(c);
            if (b.size() > 8192) break;
        }
        if (c == -1 && b.size() == 0) return null;
        return new String(b.toByteArray(), StandardCharsets.UTF_8);
    }

    private static void send(OutputStream out, int code, String type, byte[] body) throws IOException {
        String status = code == 200 ? "OK" : code == 404 ? "Not Found" : code == 403 ? "Forbidden" : "Bad Request";
        String head = "HTTP/1.1 " + code + " " + status + "\r\n"
                + "Content-Type: " + type + "\r\n"
                + "Content-Length: " + body.length + "\r\n"
                + "Cache-Control: no-store\r\n"
                + "Connection: close\r\n\r\n";
        out.write(head.getBytes(StandardCharsets.UTF_8));
        out.write(body);
        out.flush();
    }
}
