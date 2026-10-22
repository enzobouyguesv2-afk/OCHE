package fr.oche.flechettes;

import android.app.Activity;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.util.Base64;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;

import java.io.ByteArrayOutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Collections;

public class MainActivity extends Activity {

    static final int PORT = 8080;
    private static Server server;
    private WebView web;
    private String ip = "?";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        if (server == null) {
            try {
                server = new Server(getAssets(), PORT);
                server.start();
            } catch (Exception e) {
                server = null;
            }
        }

        ip = findIp();

        web = new WebView(this);
        setContentView(web);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        web.setWebViewClient(new WebViewClient());
        web.addJavascriptInterface(new Bridge(), "Android");
        web.loadUrl("http://127.0.0.1:" + PORT + "/?tv&ip=" + ip);
        web.requestFocus();
    }

    @Override
    protected void onDestroy() {
        if (web != null) web.destroy();
        super.onDestroy();
    }

    static String findIp() {
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (a instanceof Inet4Address && a.isSiteLocalAddress()) return a.getHostAddress();
                }
            }
        } catch (Exception ignored) { }
        return "?";
    }

    class Bridge {
        @JavascriptInterface
        public String qr() {
            try {
                String url = "http://" + ip + ":" + PORT;
                int size = 360;
                BitMatrix m = new QRCodeWriter().encode(url, BarcodeFormat.QR_CODE, size, size);
                Bitmap bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
                for (int x = 0; x < size; x++) {
                    for (int y = 0; y < size; y++) {
                        bmp.setPixel(x, y, m.get(x, y) ? 0xFF000000 : 0xFFFFFFFF);
                    }
                }
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                bmp.compress(Bitmap.CompressFormat.PNG, 100, out);
                return "data:image/png;base64," + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP);
            } catch (Exception e) {
                return "";
            }
        }
    }
}
