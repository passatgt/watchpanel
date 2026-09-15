package me.visztpeter.doorbell;

import android.content.Context;
import android.util.Base64;
import android.util.Log;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.util.HashMap;
import java.util.Map;

/**
 * A tiny LAN admin page, so feeds can be typed on a laptop instead of a 7"
 * touchscreen.
 *
 * Hand-rolled on ServerSocket rather than pulling in a HTTP library: the whole
 * surface is two routes, and the project otherwise has one dependency.
 *
 * SECURITY: feed URLs embed camera credentials, and this serves them over plain
 * HTTP. It is therefore off by default and refuses to start without a PIN. Even
 * with one, treat it as "anyone already on my Wi-Fi could read this" - it is a
 * convenience for a home network, not an authentication boundary.
 */
public class AdminServer {

    public interface Listener {
        /** Config was rewritten from the browser; re-read and re-apply it. */
        void onConfigChanged();
    }

    private static final String TAG = "AdminServer";

    private final Context ctx;
    private final Listener listener;
    private ServerSocket server;
    private Thread thread;
    private volatile boolean running;

    public AdminServer(Context ctx, Listener listener) {
        this.ctx = ctx.getApplicationContext();
        this.listener = listener;
    }

    public boolean isRunning() {
        return running;
    }

    public void start(final int port, final String pin) {
        if (running || pin == null || pin.trim().isEmpty()) return;
        running = true;
        thread = new Thread(new Runnable() {
            @Override public void run() { serve(port, pin.trim()); }
        }, "admin-server");
        thread.setDaemon(true);
        thread.start();
    }

    public void stop() {
        running = false;
        try {
            if (server != null) server.close();     // unblocks accept()
        } catch (Exception ignored) {
        }
        server = null;
        thread = null;
    }

    private void serve(int port, String pin) {
        try {
            server = new ServerSocket(port);
            Log.i(TAG, "admin server listening on " + port);
            while (running) {
                Socket sock = server.accept();
                try {
                    handle(sock, pin);
                } catch (Exception e) {
                    Log.w(TAG, "request failed", e);
                } finally {
                    try { sock.close(); } catch (Exception ignored) { }
                }
            }
        } catch (Exception e) {
            if (running) Log.w(TAG, "admin server stopped", e);
        } finally {
            running = false;
        }
    }

    private void handle(Socket sock, String pin) throws Exception {
        sock.setSoTimeout(8000);
        BufferedReader in = new BufferedReader(new InputStreamReader(sock.getInputStream(), "UTF-8"));
        OutputStream out = sock.getOutputStream();

        String request = in.readLine();
        if (request == null) return;
        String[] parts = request.split(" ");
        String method = parts.length > 0 ? parts[0] : "GET";
        String path = parts.length > 1 ? parts[1] : "/";

        String auth = null;
        int contentLength = 0;
        String line;
        while ((line = in.readLine()) != null && !line.isEmpty()) {
            String lower = line.toLowerCase();
            if (lower.startsWith("authorization:")) auth = line.substring(14).trim();
            else if (lower.startsWith("content-length:")) {
                contentLength = Integer.parseInt(line.substring(15).trim());
            }
        }

        if (!authorised(auth, pin)) {
            send(out, "401 Unauthorized",
                    "WWW-Authenticate: Basic realm=\"WatchPanel\"\r\n",
                    "text/plain", "Authentication required");
            return;
        }

        if ("POST".equals(method)) {
            char[] buf = new char[contentLength];
            int read = 0;
            while (read < contentLength) {
                int n = in.read(buf, read, contentLength - read);
                if (n < 0) break;
                read += n;
            }
            applyForm(new String(buf, 0, read));
            send(out, "303 See Other", "Location: /\r\n", "text/plain", "Saved");
            if (listener != null) listener.onConfigChanged();
            return;
        }

        send(out, "200 OK", "", "text/html; charset=utf-8", page());
    }

    /** HTTP Basic; any username, the PIN as the password. */
    private boolean authorised(String header, String pin) {
        if (header == null || !header.toLowerCase().startsWith("basic ")) return false;
        try {
            String decoded = new String(Base64.decode(header.substring(6).trim(), Base64.DEFAULT), "UTF-8");
            int colon = decoded.indexOf(':');
            return colon >= 0 && pin.equals(decoded.substring(colon + 1));
        } catch (Exception e) {
            return false;
        }
    }

    // ------------------------------------------------------------------ form

    private void applyForm(String body) {
        Map<String, String> f = parseForm(body);
        Config c = Config.load(ctx);

        String dash = f.get("dashboard");
        if (dash != null) c.dashboardUrl = dash.trim();

        c.feeds.clear();
        for (int i = 0; i < 12; i++) {
            String url = f.get("url" + i);
            if (url == null || url.trim().isEmpty()) continue;
            String name = f.get("name" + i);
            Config.Feed feed = new Config.Feed(
                    name == null || name.trim().isEmpty() ? "Camera" : name.trim(),
                    url.trim(),
                    f.containsKey("talk" + i));
            // Without these the browser form would silently wipe the lock settings.
            feed.lockHost = trimOrEmpty(f.get("lockhost" + i));
            feed.lockPassword = f.get("lockpw" + i) == null ? "" : f.get("lockpw" + i);
            try {
                feed.lockSeconds = Math.max(1, Math.min(60,
                        Integer.parseInt(trimOrEmpty(f.get("locksec" + i)))));
            } catch (NumberFormatException e) {
                feed.lockSeconds = 5;
            }
            c.feeds.add(feed);
        }
        c.save(ctx);
        Log.i(TAG, "config updated from browser: " + c.feeds.size() + " feed(s)");
    }

    private static Map<String, String> parseForm(String body) {
        Map<String, String> map = new HashMap<>();
        for (String pair : body.split("&")) {
            int eq = pair.indexOf('=');
            if (eq < 0) continue;
            try {
                map.put(URLDecoder.decode(pair.substring(0, eq), "UTF-8"),
                        URLDecoder.decode(pair.substring(eq + 1), "UTF-8"));
            } catch (Exception ignored) {
            }
        }
        return map;
    }

    private String page() {
        Config c = Config.load(ctx);
        StringBuilder b = new StringBuilder();
        b.append("<!doctype html><meta charset=utf-8>")
         .append("<meta name=viewport content='width=device-width,initial-scale=1'>")
         .append("<title>WatchPanel</title><style>")
         .append("body{font:15px/1.5 system-ui,sans-serif;max-width:820px;margin:24px auto;")
         .append("padding:0 16px;background:#11151a;color:#e8eef4}")
         .append("h1{font-size:20px}h2{font-size:15px;color:#7fb2d8;margin:26px 0 8px}")
         .append("input[type=text]{width:100%;padding:9px;margin:3px 0;background:#1a2129;")
         .append("border:1px solid #2b3742;border-radius:6px;color:#e8eef4;font:inherit}")
         .append("fieldset{border:1px solid #2b3742;border-radius:8px;margin:0 0 12px;padding:12px}")
         .append("label{font-size:13px;color:#9fb0c0}")
         .append("button{padding:11px 22px;font:inherit;background:#1e88e5;color:#fff;")
         .append("border:0;border-radius:6px;cursor:pointer}")
         .append(".hint{font-size:12px;color:#7f8b98;margin-top:2px}")
         .append("</style><h1>WatchPanel</h1><form method=post>");

        b.append("<h2>Camera feeds</h2>");
        int n = Math.max(c.feeds.size() + 1, 2);       // always one spare row
        for (int i = 0; i < n; i++) {
            Config.Feed f = i < c.feeds.size() ? c.feeds.get(i) : null;
            b.append("<fieldset><label>Name</label>")
             .append("<input type=text name=name").append(i).append(" value='")
             .append(esc(f == null ? "" : f.name)).append("'>")
             .append("<label>RTSP URL</label>")
             .append("<input type=text name=url").append(i).append(" value='")
             .append(esc(f == null ? "" : f.url)).append("' placeholder='rtsp://user:pass@host:554/path'>")
             .append("<div><label><input type=checkbox name=talk").append(i)
             .append(f != null && f.talk ? " checked" : "").append("> Two-way audio</label></div>")
             .append("<label>Door lock — Shelly IP (optional)</label>")
             .append("<input type=text name=lockhost").append(i).append(" value='")
             .append(esc(f == null ? "" : f.lockHost)).append("' placeholder='192.168.0.207'>")
             .append("<label>Unlock seconds</label>")
             .append("<input type=text name=locksec").append(i).append(" value='")
             .append(f == null ? 5 : f.lockSeconds).append("'>")
             .append("<label>Shelly password (only if auth is enabled)</label>")
             .append("<input type=text name=lockpw").append(i).append(" value='")
             .append(esc(f == null ? "" : f.lockPassword)).append("'>")
             .append("<div class=hint>Leave the URL empty to remove this feed.</div>")
             .append("</fieldset>");
        }

        b.append("<h2>Dashboard</h2><input type=text name=dashboard value='")
         .append(esc(c.dashboardUrl)).append("'>")
         .append("<p><button type=submit>Save</button></p>")
         .append("<p class=hint>Saving applies immediately on the tablet. "
               + "Everything else stays in the on-device settings.</p>")
         .append("</form>");
        return b.toString();
    }

    private static String trimOrEmpty(String s) {
        return s == null ? "" : s.trim();
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;")
                .replace(">", "&gt;").replace("'", "&#39;").replace("\"", "&quot;");
    }

    private static void send(OutputStream out, String status, String extraHeaders,
                             String type, String body) throws Exception {
        byte[] data = body.getBytes("UTF-8");
        String head = "HTTP/1.1 " + status + "\r\n"
                + "Content-Type: " + type + "\r\n"
                + "Content-Length: " + data.length + "\r\n"
                + "Connection: close\r\n"
                + extraHeaders + "\r\n";
        out.write(head.getBytes("UTF-8"));
        out.write(data);
        out.flush();
    }
}
