package fr.oche.flechettes;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.speech.tts.Voice;
import android.util.Base64;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
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
import java.util.Locale;

public class MainActivity extends Activity {

    static final int PORT = 8080;
    private static Server server;
    private WebView web;
    private TextToSpeech tts;
    private boolean ttsReady = false;
    private String ip = "?";
    private ValueCallback<Uri[]> fileCallback;          // « Mes fichiers » du Blind Test
    private static final int PICK_FILES = 42;
    private volatile String btsUtterance = "";          // dernière phrase du présentateur du Blind Test
    private int btsSeq = 0;

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

        tts = new TextToSpeech(this, status -> {
            if (status == TextToSpeech.SUCCESS) {
                tts.setLanguage(Locale.FRANCE);
                tts.setSpeechRate(0.92f);
                tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                    @Override public void onStart(String id) { }
                    @Override public void onDone(String id) { btsEnd(id); }
                    @Override public void onError(String id) { btsEnd(id); }
                });
                ttsReady = true;
            }
        });

        web = new WebView(this);
        setContentView(web);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        web.setWebViewClient(new WebViewClient());
        web.setWebChromeClient(new WebChromeClient() {
            /* <input type="file"> : choix de fichiers audio (Blind Test, « Mes fichiers ») */
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
                if (fileCallback != null) fileCallback.onReceiveValue(null);
                fileCallback = callback;
                try {
                    Intent i = params.createIntent();
                    if (params.getMode() == FileChooserParams.MODE_OPEN_MULTIPLE) i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                    startActivityForResult(i, PICK_FILES);
                } catch (ActivityNotFoundException e) {
                    fileCallback = null;
                    callback.onReceiveValue(null);
                    web.evaluateJavascript("window.BTS&&BTS.Show&&BTS.Show.toast('Aucun explorateur de fichiers sur cette TV')", null);
                }
                return true;
            }
        });
        web.addJavascriptInterface(new Bridge(), "Android");
        web.loadUrl("http://127.0.0.1:" + PORT + "/menu?tv&ip=" + ip);
        web.requestFocus();
    }

    @Override
    public void onBackPressed() {
        if (web != null && web.canGoBack()) web.goBack();
        else super.onBackPressed();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == PICK_FILES && fileCallback != null) {
            Uri[] res = null;
            if (resultCode == RESULT_OK && data != null) {
                ClipData clip = data.getClipData();
                if (clip != null && clip.getItemCount() > 0) {
                    res = new Uri[clip.getItemCount()];
                    for (int k = 0; k < res.length; k++) res[k] = clip.getItemAt(k).getUri();
                } else if (data.getData() != null) res = new Uri[]{data.getData()};
            }
            fileCallback.onReceiveValue(res);
            fileCallback = null;
            return;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    /** Fin d'une phrase du Blind Test : on prévient la page (seulement pour la phrase en cours). */
    private void btsEnd(String id) {
        if (id == null || !id.equals(btsUtterance)) return;
        runOnUiThread(() -> { if (web != null) web.evaluateJavascript("window.BTS_onSpeechEnd&&BTS_onSpeechEnd()", null); });
    }

    private static double optNum(String json, String key, double def) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"" + key + "\"\\s*:\\s*(-?[0-9.]+)").matcher(json == null ? "" : json);
        try { return m.find() ? Double.parseDouble(m.group(1)) : def; } catch (Exception e) { return def; }
    }

    /** Voix française proche du personnage demandé (Max : voix masculine, Léa : voix féminine), si la TV en propose. */
    private void pickVoice(String persona) {
        try {
            String[] want = "lea".equals(persona) ? new String[]{"fr-fr-x-fra", "fr-fr-x-frc", "female", "femme"} : new String[]{"fr-fr-x-frd", "fr-fr-x-frb", "male", "homme"};
            Voice best = null;
            for (Voice v : tts.getVoices()) {
                if (v.getLocale() == null || !"fr".equals(v.getLocale().getLanguage()) || v.isNetworkConnectionRequired()) continue;
                String n = v.getName().toLowerCase(Locale.ROOT);
                for (String w : want) if (n.contains(w)) { best = v; break; }
                if (best != null) break;
            }
            if (best != null) tts.setVoice(best); else tts.setLanguage(Locale.FRANCE);
        } catch (Exception ignored) { }
    }

    @Override
    protected void onDestroy() {
        if (tts != null) tts.shutdown();
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

    private String qrFor(String path) {
        try {
            String url = "http://" + ip + ":" + PORT + path;
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

    class Bridge {
        @JavascriptInterface
        public String qr() { return qrFor(""); }

        @JavascriptInterface
        public String qrPath(String path) { return qrFor(path == null ? "" : path); }

        @JavascriptInterface
        public void say(String text) {
            if (ttsReady && text != null) {
                btsUtterance = "";
                tts.setLanguage(Locale.FRANCE);
                tts.setSpeechRate(0.92f);
                tts.setPitch(1f);
                tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "narration");
            }
        }

        /** Présentateur du Blind Test : optionsJson = {rate, pitch, volume, persona}. La page est prévenue à la fin (BTS_onSpeechEnd). */
        @JavascriptInterface
        public void speak(String text, String optionsJson) {
            String id = "bts" + (++btsSeq);
            btsUtterance = id;
            if (!ttsReady || text == null) { btsEnd(id); return; }
            String persona = optionsJson != null && optionsJson.contains("\"lea\"") ? "lea" : "max";
            pickVoice(persona);
            tts.setSpeechRate((float) Math.max(0.5, Math.min(2.0, optNum(optionsJson, "rate", 1.0) * 0.95)));
            tts.setPitch((float) Math.max(0.5, Math.min(2.0, optNum(optionsJson, "pitch", 1.0))));
            Bundle b = new Bundle();
            b.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, (float) Math.max(0, Math.min(1, optNum(optionsJson, "volume", 1.0))));
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, b, id);
        }

        @JavascriptInterface
        public void speak(String text) { speak(text, "{}"); }

        @JavascriptInterface
        public void stop() {
            btsUtterance = "";
            if (ttsReady) tts.stop();
        }

        @JavascriptInterface
        public void hush() {
            if (ttsReady) tts.stop();
        }
    }
}
