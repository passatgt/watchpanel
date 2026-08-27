package me.visztpeter.doorbell;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * All user-tunable state, stored as a single JSON blob in SharedPreferences.
 * One blob rather than individual keys so the whole config can be exported,
 * pasted, or backed up as text from the settings screen.
 */
public class Config {

    public static class Feed {
        public String name;
        public String url;

        /**
         * Whether this camera accepts an ONVIF audio backchannel. Declared per
         * feed rather than probed: probing costs a round-trip on every switch,
         * and you already know which of your cameras can talk back.
         */
        public boolean talk;

        public Feed(String name, String url, boolean talk) {
            this.name = name;
            this.url = url;
            this.talk = talk;
        }
    }

    private static final String PREFS = "doorbell_wall";
    private static final String KEY = "config_json";

    public final List<Feed> feeds = new ArrayList<>();
    public String dashboardUrl = "https://visztpeter.me/";

    /**
     * Share of the screen given to the camera block: height in portrait, width
     * in landscape.
     */
    public int splitPercent = 47;

    /**
     * "portrait", "landscape" or "auto". A wall-mounted tablet wants a fixed
     * orientation matching its bracket, not a sensor that flips it.
     */
    public String orientation = "portrait";

    /**
     * Camera block before the web pane (top in portrait, left in landscape) or
     * after it. Which reads better depends on how high the tablet is mounted.
     */
    public boolean cameraFirst = true;

    /** Muted by default: a wall panel should not start talking on its own. */
    public boolean audioMuted = true;

    /** "system", "en" or "hu". Applies to the on-screen overlays. */
    public String language = "system";

    /**
     * Scale video to cover the whole pane, cropping whatever overflows, rather
     * than letterboxing the full frame inside it.
     */
    public boolean videoFillPane = true;

    /**
     * Manual zoom as a percentage of the source's native size. 100 leaves it to
     * libVLC's own fitting. Needed because some RTSP sources never report their
     * geometry to libVLC, which leaves its automatic scaling with nothing to
     * compute from; an explicit factor sidesteps that entirely.
     */
    public int videoZoomPercent = 100;

    /** Window brightness while awake / while dimmed. 0..1. */
    public float activeBrightness = 1.0f;
    public float dimBrightness = 0.0f;

    /** Seconds of no presence before dimming. 0 disables dimming entirely. */
    public int dimTimeoutSec = 60;

    /** Use the front camera to wake the screen. */
    public boolean presenceEnabled = true;

    /** Per-pixel luma delta that counts as "changed". Lower = more sensitive. */
    public int motionThreshold = 14;

    /** Fraction of sampled pixels that must change to count as motion, in 1/1000. */
    public int motionAreaPerMille = 12;

    /** Stop decoding video while dimmed. Saves heat on a 24/7 device. */
    public boolean pauseStreamWhenDim = true;

    /** WebView auto-reload interval in seconds. 0 disables. */
    public int webReloadSec = 300;

    /**
     * Android 5.1's CA store predates several modern roots (the dashboard's
     * GTS Root R4 among them). Opt-in escape hatch for a LAN/self-hosted page.
     */
    public boolean ignoreSslErrors = false;

    /** RTSP jitter buffer in ms. Higher = smoother but laggier. */
    public int networkCachingMs = 1500;

    /** Force RTSP over TCP. Reolink is far more reliable this way. */
    public boolean rtspOverTcp = true;

    /** Feed button colours. Stored as #RRGGBB strings so the JSON stays readable. */
    public int buttonActiveColor = 0xFF1E88E5;
    public int buttonInactiveColor = 0xFF263238;

    public boolean kioskMode = false;

    public static String toHex(int color) {
        return String.format("#%06X", color & 0xFFFFFF);
    }

    /** Lenient #RGB / #RRGGBB parse; returns the fallback on anything unexpected. */
    public static int parseColor(String raw, int fallback) {
        if (raw == null) return fallback;
        raw = raw.trim();
        if (raw.isEmpty()) return fallback;
        if (!raw.startsWith("#")) raw = "#" + raw;
        try {
            return 0xFF000000 | (android.graphics.Color.parseColor(raw) & 0xFFFFFF);
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }

    public static Config load(Context ctx) {
        SharedPreferences sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        Config c = new Config();
        String raw = sp.getString(KEY, null);
        if (raw == null) {
            c.feeds.add(new Feed("Doorbell",
                    "rtsp://user:pass@192.168.1.50:554/h264Preview_01_main", true));
            return c;
        }
        try {
            c.fromJson(new JSONObject(raw));
        } catch (JSONException e) {
            // Corrupt config should not brick a wall-mounted device: fall back to defaults.
        }
        return c;
    }

    public void save(Context ctx) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY, toJson().toString()).apply();
    }

    private void fromJson(JSONObject o) throws JSONException {
        dashboardUrl = o.optString("dashboardUrl", dashboardUrl);
        splitPercent = o.optInt("splitPercent", splitPercent);
        orientation = o.optString("orientation", orientation);
        cameraFirst = o.optBoolean("cameraFirst", cameraFirst);
        audioMuted = o.optBoolean("audioMuted", audioMuted);
        language = o.optString("language", language);
        videoFillPane = o.optBoolean("videoFillPane", videoFillPane);
        videoZoomPercent = o.optInt("videoZoomPercent", videoZoomPercent);
        activeBrightness = (float) o.optDouble("activeBrightness", activeBrightness);
        dimBrightness = (float) o.optDouble("dimBrightness", dimBrightness);
        dimTimeoutSec = o.optInt("dimTimeoutSec", dimTimeoutSec);
        presenceEnabled = o.optBoolean("presenceEnabled", presenceEnabled);
        motionThreshold = o.optInt("motionThreshold", motionThreshold);
        motionAreaPerMille = o.optInt("motionAreaPerMille", motionAreaPerMille);
        pauseStreamWhenDim = o.optBoolean("pauseStreamWhenDim", pauseStreamWhenDim);
        webReloadSec = o.optInt("webReloadSec", webReloadSec);
        ignoreSslErrors = o.optBoolean("ignoreSslErrors", ignoreSslErrors);
        networkCachingMs = o.optInt("networkCachingMs", networkCachingMs);
        rtspOverTcp = o.optBoolean("rtspOverTcp", rtspOverTcp);
        kioskMode = o.optBoolean("kioskMode", kioskMode);
        buttonActiveColor = parseColor(o.optString("buttonActiveColor", null), buttonActiveColor);
        buttonInactiveColor = parseColor(o.optString("buttonInactiveColor", null), buttonInactiveColor);

        feeds.clear();
        JSONArray arr = o.optJSONArray("feeds");
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                JSONObject f = arr.getJSONObject(i);
                feeds.add(new Feed(f.optString("name", "Cam " + (i + 1)),
                        f.optString("url", ""), f.optBoolean("talk", true)));
            }
        }
    }

    public JSONObject toJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("dashboardUrl", dashboardUrl);
            o.put("splitPercent", splitPercent);
            o.put("orientation", orientation);
            o.put("cameraFirst", cameraFirst);
            o.put("audioMuted", audioMuted);
            o.put("language", language);
            o.put("videoFillPane", videoFillPane);
            o.put("videoZoomPercent", videoZoomPercent);
            o.put("activeBrightness", activeBrightness);
            o.put("dimBrightness", dimBrightness);
            o.put("dimTimeoutSec", dimTimeoutSec);
            o.put("presenceEnabled", presenceEnabled);
            o.put("motionThreshold", motionThreshold);
            o.put("motionAreaPerMille", motionAreaPerMille);
            o.put("pauseStreamWhenDim", pauseStreamWhenDim);
            o.put("webReloadSec", webReloadSec);
            o.put("ignoreSslErrors", ignoreSslErrors);
            o.put("networkCachingMs", networkCachingMs);
            o.put("rtspOverTcp", rtspOverTcp);
            o.put("kioskMode", kioskMode);
            o.put("buttonActiveColor", toHex(buttonActiveColor));
            o.put("buttonInactiveColor", toHex(buttonInactiveColor));

            JSONArray arr = new JSONArray();
            for (Feed f : feeds) {
                JSONObject fo = new JSONObject();
                fo.put("name", f.name);
                fo.put("url", f.url);
                fo.put("talk", f.talk);
                arr.put(fo);
            }
            o.put("feeds", arr);
        } catch (JSONException e) {
            // JSONObject.put only throws on NaN/Infinity keys, which we never produce.
        }
        return o;
    }
}
