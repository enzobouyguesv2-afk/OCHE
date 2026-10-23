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

/**
 * Petit serveur HTTP local : sert les pages et relaie les parties entre la télé et les téléphones.
 * La télé (page locale, 127.0.0.1) fait tourner le moteur de jeu : elle seule lit les actions et publie les vues.
 * Chaque téléphone ne reçoit que la vue publique + SA vue privée (clé = jeton secret du téléphone).
 */
class Server extends Thread {

    /** Un canal de jeu (loup-garou, maître des mots). */
    static final class Channel {
        final int maxAct;
        final List<String> acts = new ArrayList<>();
        int base = 0;                 // numéro de la première action conservée
        int rev = 0;
        String pub = "null";
        final Map<String, String> priv = new HashMap<>();
        final Map<String, Long> seen = new HashMap<>();
        Channel(int maxAct) { this.maxAct = maxAct; }
    }

    private final ServerSocket socket;
    private final AssetManager assets;

    // Fléchettes : un état partagé
    private int rev = 0;
    private String data = "{\"g\":null}";

    private final Map<String, Channel> channels = new HashMap<>();
    private final Map<String, long[]> rate = new HashMap<>();

    Server(AssetManager assets, int port) throws IOException {
        this.assets = assets;
        channels.put("lg", new Channel(4096));
        channels.put("mm", new Channel(2048));
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

    /** Limite simple par adresse : au plus 40 actions par tranche de 5 secondes. */
    private synchronized boolean allow(String ip) {
        long now = System.currentTimeMillis();
        long[] r = rate.get(ip);
        if (r == null || now - r[0] > 5000) {
            if (rate.size() > 500) rate.clear();
            rate.put(ip, new long[]{now, 1});
            return true;
        }
        r[1]++;
        return r[1] <= 40;
    }

    private void act(Channel c, String body) {
        synchronized (c) {
            c.acts.add(body);
            if (c.acts.size() > 5000) {
                int drop = c.acts.size() - 1000;
                c.acts.subList(0, drop).clear();
                c.base += drop;
            }
            c.notifyAll();
        }
    }

    /** Actions depuis le numéro « since » ; attend jusqu'à waitMs s'il n'y en a pas encore. */
    private String actsSince(Channel c, int since, long waitMs) throws InterruptedException {
        synchronized (c) {
            long end = System.currentTimeMillis() + waitMs;
            while (c.base + c.acts.size() <= since && waitMs > 0) {
                long left = end - System.currentTimeMillis();
                if (left <= 0) break;
                c.wait(left);
            }
            StringBuilder b = new StringBuilder();
            int seq = c.base + c.acts.size();
            b.append("{\"seq\":").append(seq).append(",\"a\":[");
            boolean first = true;
            for (int i = Math.max(0, since - c.base); i < c.acts.size(); i++) {
                if (!first) b.append(',');
                b.append(c.acts.get(i));
                first = false;
            }
            b.append("]}");
            return b.toString();
        }
    }

    private void publish(Channel c, String body) throws Exception {
        JSONObject o = new JSONObject(body);
        String pub = o.getJSONObject("pub").toString();
        Map<String, String> priv = new HashMap<>();
        JSONObject p = o.optJSONObject("priv");
        if (p != null) {
            Iterator<String> it = p.keys();
            while (it.hasNext()) {
                String k = it.next();
                priv.put(k, p.getJSONObject(k).toString());
            }
        }
        synchronized (c) {
            c.pub = pub;
            c.priv.clear();
            c.priv.putAll(priv);
            c.rev++;
            c.notifyAll();
        }
    }

    /** Vue d'un téléphone ; si « known » = révision déjà reçue, attend un changement (max waitMs). */
    private String view(Channel c, String pid, int known, long waitMs) throws InterruptedException {
        synchronized (c) {
            if (pid != null && !pid.isEmpty()) {
                if (c.seen.size() > 300 && !c.seen.containsKey(pid)) c.seen.clear();
                c.seen.put(pid, System.currentTimeMillis());
            }
            long end = System.currentTimeMillis() + waitMs;
            while (known >= 0 && c.rev == known && waitMs > 0) {
                long left = end - System.currentTimeMillis();
                if (left <= 0) break;
                c.wait(left);
            }
            String priv = pid != null && c.priv.containsKey(pid) ? c.priv.get(pid) : "null";
            return "{\"rev\":" + c.rev + ",\"pub\":" + c.pub + ",\"priv\":" + priv + "}";
        }
    }

    /** Depuis combien de millisecondes chaque téléphone n'a pas interrogé le serveur (lu par la télé). */
    private String seen(Channel c) {
        synchronized (c) {
            long now = System.currentTimeMillis();
            StringBuilder b = new StringBuilder("{");
            boolean first = true;
            for (Map.Entry<String, Long> e : c.seen.entrySet()) {
                if (!first) b.append(',');
                b.append('"').append(e.getKey()).append("\":").append(now - e.getValue());
                first = false;
            }
            return b.append('}').toString();
        }
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

    private static int intParam(String query, String name, int def) {
        try { return Integer.parseInt(param(query, name)); } catch (Exception e) { return def; }
    }

    private void handle(Socket s) {
        try (Socket sock = s) {
            sock.setSoTimeout(10000);
            boolean local = sock.getInetAddress() != null && sock.getInetAddress().isLoopbackAddress();
            String ip = sock.getInetAddress() == null ? "?" : sock.getInetAddress().getHostAddress();
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
            int headers = 0;
            while ((h = readLine(in)) != null && !h.isEmpty()) {
                if (++headers > 100) return;
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

            // Canaux de jeu : /lg/... et /mm/...
            if (path.length() > 4 && path.charAt(0) == '/' && path.charAt(3) == '/' && channels.containsKey(path.substring(1, 3))) {
                Channel c = channels.get(path.substring(1, 3));
                String op = path.substring(4);
                switch (op) {
                    case "act":
                        if (body == null || body.length() > c.maxAct) { send(out, 400, "text/plain", bytes("bad request")); return; }
                        if (!local && !allow(ip)) { send(out, 429, "text/plain", bytes("too many requests")); return; }
                        try { new JSONObject(body); } catch (Exception e) { send(out, 400, "text/plain", bytes("bad json")); return; }
                        act(c, body);
                        send(out, 200, "application/json", bytes("{\"ok\":true}"));
                        return;
                    case "acts":
                        if (!local) { send(out, 403, "text/plain", bytes("forbidden")); return; }
                        send(out, 200, "application/json", bytes(actsSince(c, intParam(query, "since", 0), Math.min(8000, Math.max(0, intParam(query, "wait", 0))))));
                        return;
                    case "pub":
                        if (!local || body == null) { send(out, 403, "text/plain", bytes("forbidden")); return; }
                        publish(c, body);
                        send(out, 200, "application/json", bytes("{\"ok\":true}"));
                        return;
                    case "seen":
                        if (!local) { send(out, 403, "text/plain", bytes("forbidden")); return; }
                        send(out, 200, "application/json", bytes(seen(c)));
                        return;
                    case "view":
                        send(out, 200, "application/json", bytes(view(c, param(query, "pid"), intParam(query, "rev", -1), Math.min(8000, Math.max(0, intParam(query, "wait", 0))))));
                        return;
                    default:
                        send(out, 404, "text/plain", bytes("not found"));
                        return;
                }
            }

            switch (path) {
                case "/state":
                    if (body != null) {
                        if (body.length() > 65536) { send(out, 400, "text/plain", bytes("too large")); return; }
                        send(out, 200, "application/json", bytes(write(body)));
                    } else send(out, 200, "application/json", bytes(read()));
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
                case "/mots":
                    send(out, 200, "text/html; charset=utf-8", asset("mots.html"));
                    break;
                case "/mm-engine.js":
                    send(out, 200, "application/javascript; charset=utf-8", asset("mm-engine.txt"));
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
        String status = code == 200 ? "OK" : code == 404 ? "Not Found" : code == 403 ? "Forbidden" : code == 429 ? "Too Many Requests" : "Bad Request";
        String head = "HTTP/1.1 " + code + " " + status + "\r\n"
                + "Content-Type: " + type + "\r\n"
                + "Content-Length: " + body.length + "\r\n"
                + "Cache-Control: no-store\r\n"
                + "X-Content-Type-Options: nosniff\r\n"
                + "Connection: close\r\n\r\n";
        out.write(head.getBytes(StandardCharsets.UTF_8));
        out.write(body);
        out.flush();
    }
}
