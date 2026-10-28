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
import java.security.MessageDigest;
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

    // IMPACT ARENA : liaison temps réel (WebSocket) entre la télé (hôte) et les manettes
    static final class WsConn {
        final Socket sock; final OutputStream out; final int id; final boolean host;
        long winStart = 0; int winCount = 0;
        WsConn(Socket s, OutputStream o, int id, boolean host) { this.sock = s; this.out = o; this.id = id; this.host = host; }
        synchronized void send(String text) {
            try {
                byte[] p = text.getBytes(StandardCharsets.UTF_8);
                ByteArrayOutputStream b = new ByteArrayOutputStream(p.length + 10);
                b.write(0x81);
                if (p.length < 126) b.write(p.length);
                else if (p.length < 65536) { b.write(126); b.write(p.length >> 8); b.write(p.length & 255); }
                else { b.write(127); for (int i = 7; i >= 0; i--) b.write((int) (((long) p.length >> (8 * i)) & 255)); }
                b.write(p);
                out.write(b.toByteArray()); out.flush();
            } catch (IOException e) { try { sock.close(); } catch (IOException ignored) { } }
        }
        synchronized void raw(int opcode, byte[] p) {
            try { out.write(0x80 | opcode); out.write(p.length); out.write(p); out.flush(); } catch (IOException ignored) { }
        }
    }
    private final Object wsLock = new Object();
    private WsConn wsHost = null;
    private final Map<Integer, WsConn> wsPads = new HashMap<>();
    private int wsSeq = 0;

    /* BLIND TEST SHOW : relais WebSocket par salle (/bts-ws). La page de la télé est l'arbitre ; le relais ne fait que transmettre. */
    static final class BtsConn {
        final WsConn ws; final String room, id; final boolean tv;
        long winStart = 0; int winCount = 0;
        BtsConn(WsConn ws, String room, String id, boolean tv) { this.ws = ws; this.room = room; this.id = id; this.tv = tv; }
    }
    private final Object btsLock = new Object();
    private final Map<String, List<BtsConn>> btsRooms = new HashMap<>();
    private final int port;

    Server(AssetManager assets, int port) throws IOException {
        this.assets = assets;
        this.port = port;
        channels.put("lg", new Channel(4096));
        channels.put("mm", new Channel(2048));
        channels.put("bn", new Channel(1024));
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
            String h, wsKey = null, upgrade = null;
            int headers = 0;
            while ((h = readLine(in)) != null && !h.isEmpty()) {
                if (++headers > 100) return;
                int c = h.indexOf(':');
                if (c <= 0) continue;
                String name = h.substring(0, c).trim().toLowerCase(), value = h.substring(c + 1).trim();
                if (name.equals("content-length")) {
                    try { length = Integer.parseInt(value); } catch (NumberFormatException ignored) { }
                } else if (name.equals("sec-websocket-key")) wsKey = value;
                else if (name.equals("upgrade")) upgrade = value;
            }
            if (path.equals("/ws") && method.equals("GET") && wsKey != null && upgrade != null && upgrade.equalsIgnoreCase("websocket")) {
                websocket(sock, in, sock.getOutputStream(), wsKey, local, query);
                return;
            }
            if (path.equals("/bts-ws") && method.equals("GET") && wsKey != null && upgrade != null && upgrade.equalsIgnoreCase("websocket")) {
                btsRelay(sock, in, sock.getOutputStream(), wsKey, local, query);
                return;
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
                case "/arena":
                    send(out, 200, "text/html; charset=utf-8", asset("arena.html"));
                    break;
                case "/ia-engine.js":
                    send(out, 200, "application/javascript; charset=utf-8", asset("ia-engine.txt"));
                    break;
                case "/ia-qr.js":
                    send(out, 200, "application/javascript; charset=utf-8", asset("ia-qr.txt"));
                    break;
                case "/mots":
                    send(out, 200, "text/html; charset=utf-8", asset("mots.html"));
                    break;
                case "/mm-engine.js":
                    send(out, 200, "application/javascript; charset=utf-8", asset("mm-engine.txt"));
                    break;
                case "/blind-test-show.html":
                case "/blindtest":
                    send(out, 200, "text/html; charset=utf-8", local ? btsPage() : asset("blind-test-show.html"));
                    break;
                case "/bataille":
                    send(out, 200, "text/html; charset=utf-8", asset("bataille.html"));
                    break;
                case "/bn-engine.js":
                    send(out, 200, "application/javascript; charset=utf-8", asset("bn-engine.txt"));
                    break;
                default:
                    send(out, 404, "text/plain", bytes("not found"));
            }
        } catch (Exception ignored) { }
    }

    /* ---------------- WebSocket (RFC 6455, messages texte) ---------------- */
    private void websocket(Socket sock, InputStream in, OutputStream out, String key, boolean local, String query) throws Exception {
        boolean isHost = "host".equals(param(query, "role"));
        if (isHost && !local) { send(out, 403, "text/plain", bytes("forbidden")); return; }
        MessageDigest sha = MessageDigest.getInstance("SHA-1");
        String accept = b64(sha.digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").getBytes(StandardCharsets.US_ASCII)));
        out.write(("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: " + accept + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        out.flush();
        sock.setSoTimeout(isHost ? 60000 : 20000);
        try { sock.setTcpNoDelay(true); } catch (Exception ignored) { }
        WsConn c;
        WsConn old = null;
        synchronized (wsLock) {
            c = new WsConn(sock, out, ++wsSeq, isHost);
            if (isHost) { old = wsHost; wsHost = c; }
            else {
                if (wsPads.size() >= 16) { c.send("{\"t\":\"busy\"}"); return; }
                wsPads.put(c.id, c);
            }
        }
        if (old != null) try { old.sock.close(); } catch (IOException ignored) { }
        if (!isHost) toHost("{\"t\":\"open\",\"id\":" + c.id + "}");
        else c.send("{\"t\":\"hello\",\"pads\":" + padIds() + "}");
        if (!isHost && hostConn() == null) c.send("{\"t\":\"nohost\"}");
        StringBuilder msg = new StringBuilder();
        try {
            while (true) {
                int b0 = in.read(), b1 = in.read();
                if (b0 < 0 || b1 < 0) break;
                boolean fin = (b0 & 0x80) != 0;
                int op = b0 & 0x0f;
                long len = b1 & 0x7f;
                if (len == 126) len = ((in.read() & 255) << 8) | (in.read() & 255);
                else if (len == 127) { len = 0; for (int i = 0; i < 8; i++) len = (len << 8) | (in.read() & 255); }
                if (len > 65536 || len < 0) break;
                byte[] mask = new byte[4];
                boolean masked = (b1 & 0x80) != 0;
                if (masked) readFully(in, mask);
                byte[] p = new byte[(int) len];
                readFully(in, p);
                if (masked) for (int i = 0; i < p.length; i++) p[i] ^= mask[i & 3];
                if (op == 8) { c.raw(8, new byte[0]); break; }
                if (op == 9) { c.raw(10, p.length < 126 ? p : new byte[0]); continue; }
                if (op == 10 || op == 2) continue;
                msg.append(new String(p, StandardCharsets.UTF_8));
                if (msg.length() > 65536) break;
                if (!fin) continue;
                String text = msg.toString();
                msg.setLength(0);
                if (isHost) fromHost(text); else fromPad(c, text);
            }
        } catch (IOException ignored) {
        } finally {
            synchronized (wsLock) {
                if (isHost) { if (wsHost == c) wsHost = null; }
                else wsPads.remove(c.id);
            }
            try { sock.close(); } catch (IOException ignored) { }
            if (!isHost) toHost("{\"t\":\"close\",\"id\":" + c.id + "}");
            else if (hostConn() == null) for (WsConn p : padList()) p.send("{\"t\":\"nohost\"}");
        }
    }

    private WsConn hostConn() { synchronized (wsLock) { return wsHost; } }
    private List<WsConn> padList() { synchronized (wsLock) { return new ArrayList<>(wsPads.values()); } }
    private String padIds() { StringBuilder b = new StringBuilder("["); boolean f = true; for (WsConn p : padList()) { if (!f) b.append(','); b.append(p.id); f = false; } return b.append(']').toString(); }
    private void toHost(String s) { WsConn h = hostConn(); if (h != null) h.send(s); }

    /* ---------------- BLIND TEST SHOW ---------------- */
    /** Page du Blind Test pour la télé : on ajoute en tête la déclaration de l'hôte (adresse pour le QR code, relais, voix de la TV). */
    private byte[] btsPage() throws IOException {
        String html = new String(asset("blind-test-show.html"), StandardCharsets.UTF_8);
        String ip = lanIp();
        StringBuilder js = new StringBuilder("<script>window.BTS_HOST={");
        if (ip != null) js.append("publicUrl:'http://").append(ip).append(':').append(port).append("/blind-test-show.html',");
        js.append("wsUrl:'ws://127.0.0.1:").append(port).append("/bts-ws'};")
          .append("if(window.Android&&Android.speak){window.BTS_HOST.speak=function(t,o){Android.speak(String(t),String(o||'{}'))};")
          .append("window.BTS_HOST.stop=function(){Android.stop()};window.BTS_HOST.hasEndCallback=true;}</script>");
        int h = html.indexOf("<head>");
        html = h < 0 ? js + html : html.substring(0, h + 6) + js + html.substring(h + 6);
        return html.getBytes(StandardCharsets.UTF_8);
    }

    /** Adresse de la télé sur le Wi-Fi (pour le QR code des téléphones). */
    static String lanIp() {
        String other = null;
        try {
            for (java.net.NetworkInterface ni : java.util.Collections.list(java.net.NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (java.net.InetAddress a : java.util.Collections.list(ni.getInetAddresses())) {
                    if (!(a instanceof java.net.Inet4Address) || a.isLoopbackAddress() || a.isLinkLocalAddress()) continue;
                    if (a.isSiteLocalAddress()) return a.getHostAddress();
                    if (other == null) other = a.getHostAddress();
                }
            }
        } catch (Exception ignored) { }
        return other;
    }

    /**
     * Relais /bts-ws?room=CODE&id=ID&role=tv|player : chaque message est renvoyé aux autres clients de la salle.
     * Protections ajoutées au protocole de référence (compatibles avec la page) :
     *  - le rôle « tv » n'est accepté que depuis la télé elle-même ;
     *  - un message doit porter l'identifiant de son expéditeur (« from ») : impossible de se faire passer pour la télé ;
     *  - un message adressé à un joueur (« to ») ne part qu'à ce joueur : les autres téléphones ne le voient pas ;
     *  - taille et débit limités, nombre de salles et de connexions limité.
     */
    private void btsRelay(Socket sock, InputStream in, OutputStream out, String key, boolean local, String query) throws Exception {
        String room = param(query, "room"), id = param(query, "id"), role = param(query, "role");
        room = room == null ? "" : room.toUpperCase();
        if (room.length() > 12) room = room.substring(0, 12);
        boolean tv = "tv".equals(role) || "tv".equals(id);
        if (room.isEmpty() || id == null || id.isEmpty() || id.length() > 40 || (tv && !local)) { send(out, 403, "text/plain", bytes("forbidden")); return; }
        MessageDigest sha = MessageDigest.getInstance("SHA-1");
        String accept = b64(sha.digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").getBytes(StandardCharsets.US_ASCII)));
        out.write(("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: " + accept + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        out.flush();
        sock.setSoTimeout(local ? 0 : 60000);
        try { sock.setTcpNoDelay(true); } catch (Exception ignored) { }
        BtsConn c = new BtsConn(new WsConn(sock, out, 0, tv), room, id, tv);
        List<BtsConn> replaced = new ArrayList<>();
        synchronized (btsLock) {
            List<BtsConn> list = btsRooms.get(room);
            if (list == null) {
                if (btsRooms.size() >= 20) { try { sock.close(); } catch (IOException ignored) { } return; }
                list = new ArrayList<>(); btsRooms.put(room, list);
            }
            for (BtsConn o : list) if (o.id.equals(id)) replaced.add(o);       // même joueur qui se reconnecte
            list.removeAll(replaced);
            if (list.size() >= 40) { try { sock.close(); } catch (IOException ignored) { } return; }
            list.add(c);
        }
        for (BtsConn o : replaced) try { o.ws.sock.close(); } catch (IOException ignored) { }
        StringBuilder msg = new StringBuilder();
        try {
            while (true) {
                int b0 = in.read(), b1 = in.read();
                if (b0 < 0 || b1 < 0) break;
                boolean fin = (b0 & 0x80) != 0;
                int op = b0 & 0x0f;
                long len = b1 & 0x7f;
                if (len == 126) len = ((in.read() & 255) << 8) | (in.read() & 255);
                else if (len == 127) { len = 0; for (int i = 0; i < 8; i++) len = (len << 8) | (in.read() & 255); }
                if (len > 262144 || len < 0) break;
                byte[] mask = new byte[4];
                boolean masked = (b1 & 0x80) != 0;
                if (masked) readFully(in, mask);
                byte[] p = new byte[(int) len];
                readFully(in, p);
                if (masked) for (int i = 0; i < p.length; i++) p[i] ^= mask[i & 3];
                if (op == 8) { c.ws.raw(8, new byte[0]); break; }
                if (op == 9) { c.ws.raw(10, p.length < 126 ? p : new byte[0]); continue; }
                if (op == 10 || op == 2) continue;
                msg.append(new String(p, StandardCharsets.UTF_8));
                if (msg.length() > 262144) break;
                if (!fin) continue;
                String text = msg.toString();
                msg.setLength(0);
                btsForward(c, text);
            }
        } catch (IOException ignored) {
        } finally {
            boolean wasMember;
            synchronized (btsLock) {
                List<BtsConn> list = btsRooms.get(room);
                wasMember = list != null && list.remove(c);
                if (list != null && list.isEmpty()) btsRooms.remove(room);
            }
            try { sock.close(); } catch (IOException ignored) { }
            if (wasMember) btsBroadcast(c, "{\"from\":\"server\",\"type\":\"peer-left\",\"id\":\"" + id + "\"}", null);
        }
    }

    private static final java.util.regex.Pattern BTS_FROM = java.util.regex.Pattern.compile("\"from\"\\s*:\\s*\"([^\"]*)\"");
    private static final java.util.regex.Pattern BTS_TO = java.util.regex.Pattern.compile("\"to\"\\s*:\\s*\"([^\"]*)\"");

    private void btsForward(BtsConn c, String text) {
        long now = System.currentTimeMillis();
        if (!c.tv) {                                    // un téléphone : au plus 40 messages par seconde
            if (now - c.winStart > 1000) { c.winStart = now; c.winCount = 0; }
            if (++c.winCount > 40) return;
            if (text.length() > 8192) return;
        }
        java.util.regex.Matcher f = BTS_FROM.matcher(text);
        if (!f.find() || !f.group(1).equals(c.id)) return;          // expéditeur usurpé : ignoré
        java.util.regex.Matcher t = BTS_TO.matcher(text);
        String to = t.find() ? t.group(1) : null;
        btsBroadcast(c, text, to == null || to.equals("*") ? null : to);
    }

    private void btsBroadcast(BtsConn from, String text, String onlyTo) {
        List<BtsConn> targets = new ArrayList<>();
        synchronized (btsLock) {
            List<BtsConn> list = btsRooms.get(from.room);
            if (list != null) for (BtsConn o : list) if (o != from && (onlyTo == null || o.id.equals(onlyTo))) targets.add(o);
        }
        for (BtsConn o : targets) o.ws.send(text);
    }

    /** Message d'une manette : limité en débit, vérifié (objet JSON), puis transmis à la télé avec l'identifiant de la connexion. */
    private void fromPad(WsConn c, String text) {
        long now = System.currentTimeMillis();
        if (now - c.winStart > 1000) { c.winStart = now; c.winCount = 0; }
        if (++c.winCount > 90) return;
        if (text.length() > 2048) return;
        String t = text.trim();
        if (!t.startsWith("{") || !t.endsWith("}")) return;
        try { new JSONObject(t); } catch (Exception e) { return; }
        WsConn h = hostConn();
        if (h == null) { c.send("{\"t\":\"nohost\"}"); return; }
        h.send("{\"t\":\"msg\",\"id\":" + c.id + ",\"m\":" + t + "}");
    }

    /** Message de la télé : {"to": id | "all", "m": {...}} ou {"kick": id}. */
    private void fromHost(String text) {
        try {
            JSONObject o = new JSONObject(text);
            String kick = o.optString("kick");
            if (kick != null && !kick.isEmpty()) {
                WsConn p; synchronized (wsLock) { p = wsPads.get(Integer.parseInt(kick)); }
                if (p != null) try { p.sock.close(); } catch (IOException ignored) { }
                return;
            }
            String to = o.optString("to");
            String m = o.getJSONObject("m").toString();
            if ("all".equals(to)) { for (WsConn p : padList()) p.send(m); return; }
            WsConn p; synchronized (wsLock) { p = wsPads.get(Integer.parseInt(to)); }
            if (p != null) p.send(m);
        } catch (Exception ignored) { }
    }

    private static void readFully(InputStream in, byte[] b) throws IOException {
        int r = 0;
        while (r < b.length) { int n = in.read(b, r, b.length - r); if (n < 0) throw new IOException("eof"); r += n; }
    }

    private static String b64(byte[] d) {
        String a = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
        StringBuilder s = new StringBuilder();
        for (int i = 0; i < d.length; i += 3) {
            int n = (d[i] & 255) << 16 | (i + 1 < d.length ? (d[i + 1] & 255) << 8 : 0) | (i + 2 < d.length ? d[i + 2] & 255 : 0);
            s.append(a.charAt(n >> 18 & 63)).append(a.charAt(n >> 12 & 63)).append(i + 1 < d.length ? a.charAt(n >> 6 & 63) : '=').append(i + 2 < d.length ? a.charAt(n & 63) : '=');
        }
        return s.toString();
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
