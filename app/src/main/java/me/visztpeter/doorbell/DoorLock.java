package me.visztpeter.doorbell;

import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Opens a door lock wired through a Shelly Gen 2/3 relay, over its local RPC API.
 *
 * The request turns the relay on with {@code toggle_after}, so the Shelly itself
 * switches it back off. Relocking must never depend on this tablet sending a
 * second request: if the tablet drops off Wi-Fi mid-unlock, a design that relies
 * on it to relock leaves the gate open. It also does not trust the device's own
 * auto-off setting, which is easy to lose to a reset or a replacement unit.
 */
public final class DoorLock {

    public interface Callback {
        /** Called on a background thread. */
        void onResult(boolean ok, String detail);
    }

    private static final String TAG = "DoorLock";

    private DoorLock() { }

    public static void unlock(final String host, final int seconds, final String password,
                              final Callback cb) {
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    String detail = request(host, seconds, password);
                    Log.i(TAG, "unlocked " + host + " for " + seconds + "s: " + detail);
                    cb.onResult(true, detail);
                } catch (Exception e) {
                    Log.w(TAG, "unlock failed for " + host, e);
                    cb.onResult(false, e.getMessage());
                }
            }
        }, "door-lock").start();
    }

    private static String request(String host, int seconds, String password) throws Exception {
        String base = host.trim().replaceFirst("^https?://", "");
        while (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        int secs = Math.max(1, Math.min(60, seconds));
        String path = "/rpc/Switch.Set?id=0&on=true&toggle_after=" + secs;
        URL url = new URL("http://" + base + path);

        HttpURLConnection c = open(url);
        int code = c.getResponseCode();

        // Shelly authentication is HTTP digest with SHA-256 (RFC 7616), username "admin".
        if (code == 401 && password != null && !password.isEmpty()) {
            String challenge = c.getHeaderField("WWW-Authenticate");
            c.disconnect();
            c = open(url);
            c.setRequestProperty("Authorization", digest(challenge, path, password));
            code = c.getResponseCode();
        }

        String body = read(code < 400 ? c.getInputStream() : c.getErrorStream());
        c.disconnect();
        if (code != 200) {
            throw new Exception("HTTP " + code + (code == 401 ? " (password needed)" : "") + " " + body);
        }
        return body;
    }

    private static HttpURLConnection open(URL url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) url.openConnection();
        c.setConnectTimeout(4000);
        c.setReadTimeout(4000);
        c.setUseCaches(false);
        return c;
    }

    static String digest(String challenge, String uri, String password) throws Exception {
        if (challenge == null) throw new Exception("401 without a digest challenge");
        String realm = param(challenge, "realm");
        String nonce = param(challenge, "nonce");

        byte[] raw = new byte[8];
        new SecureRandom().nextBytes(raw);
        String cnonce = hex(raw);
        String nc = "00000001";

        String ha1 = sha256("admin:" + realm + ":" + password);
        String ha2 = sha256("GET:" + uri);
        String response = sha256(ha1 + ":" + nonce + ":" + nc + ":" + cnonce + ":auth:" + ha2);

        return String.format(Locale.US,
                "Digest username=\"admin\", realm=\"%s\", nonce=\"%s\", uri=\"%s\", "
                        + "algorithm=SHA-256, response=\"%s\", qop=auth, nc=%s, cnonce=\"%s\"",
                realm, nonce, uri, response, nc, cnonce);
    }

    private static String param(String header, String name) {
        Matcher m = Pattern.compile(name + "=\"?([^\",]+)\"?").matcher(header);
        return m.find() ? m.group(1) : "";
    }

    private static String sha256(String s) throws Exception {
        return hex(MessageDigest.getInstance("SHA-256").digest(s.getBytes("UTF-8")));
    }

    private static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    private static String read(InputStream in) throws Exception {
        if (in == null) return "";
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[1024];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        in.close();
        return out.toString("UTF-8");
    }
}
