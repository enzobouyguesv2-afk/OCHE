package fr.oche.flechettes;

import android.content.res.AssetManager;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/** Petit serveur HTTP local : sert la page et garde l'état de la partie partagé entre la télé et le téléphone. */
class Server extends Thread {

    private final ServerSocket socket;
    private final AssetManager assets;
    private int rev = 0;
    private String data = "{\"g\":null}";

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

    private void handle(Socket s) {
        try (Socket sock = s) {
            sock.setSoTimeout(10000);
            InputStream in = new BufferedInputStream(sock.getInputStream());
            String line = readLine(in);
            if (line == null) return;
            String[] parts = line.split(" ");
            if (parts.length < 2) return;
            String method = parts[0];
            String path = parts[1];
            int q = path.indexOf('?');
            if (q >= 0) path = path.substring(0, q);

            int length = 0;
            String h;
            while ((h = readLine(in)) != null && !h.isEmpty()) {
                int c = h.indexOf(':');
                if (c > 0 && h.substring(0, c).trim().equalsIgnoreCase("content-length")) {
                    try { length = Integer.parseInt(h.substring(c + 1).trim()); } catch (NumberFormatException ignored) { }
                }
            }

            OutputStream out = sock.getOutputStream();
            if (path.equals("/state")) {
                if (method.equals("POST")) {
                    if (length <= 0 || length > 65536) { send(out, 400, "text/plain", bytes("bad request")); return; }
                    byte[] b = new byte[length];
                    int r = 0;
                    while (r < length) {
                        int n = in.read(b, r, length - r);
                        if (n < 0) break;
                        r += n;
                    }
                    String body = new String(b, 0, r, StandardCharsets.UTF_8).trim();
                    if (!body.startsWith("{") || !body.endsWith("}")) { send(out, 400, "text/plain", bytes("bad request")); return; }
                    send(out, 200, "application/json", bytes(write(body)));
                } else {
                    send(out, 200, "application/json", bytes(read()));
                }
            } else if (path.equals("/") || path.equals("/index.html")) {
                send(out, 200, "text/html; charset=utf-8", asset("index.html"));
            } else {
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
        String status = code == 200 ? "OK" : code == 404 ? "Not Found" : "Bad Request";
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
