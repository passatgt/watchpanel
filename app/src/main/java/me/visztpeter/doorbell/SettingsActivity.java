package me.visztpeter.doorbell;

import android.app.Activity;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/**
 * Built in code rather than XML: the feed list is dynamic, and a handful of
 * rows did not justify a second layout file plus a PreferenceScreen.
 */
public class SettingsActivity extends Activity {

    private Config config;

    private LinearLayout feedList;
    private final List<EditText[]> feedRows = new ArrayList<>();

    private EditText dashboardUrl, splitPercent, dimTimeout, activeBrightness, dimBrightness;
    private RadioGroup orientationGroup, cameraPosGroup;
    private EditText activeColor, inactiveColor;
    private EditText motionThreshold, motionArea, webReload, networkCaching, videoZoom;
    private CheckBox presenceEnabled, pauseWhenDim, ignoreSsl, rtspTcp, kioskMode, videoFill;

    private boolean kioskWasEnabled;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        config = Config.load(this);
        kioskWasEnabled = config.kioskMode;

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(0xFF11141A);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        root.setPadding(pad, pad, pad, pad);
        scroll.addView(root);

        header(root, "Camera feeds");
        feedList = new LinearLayout(this);
        feedList.setOrientation(LinearLayout.VERTICAL);
        root.addView(feedList);
        for (Config.Feed f : config.feeds) addFeedRow(f.name, f.url);

        Button addFeed = new Button(this);
        addFeed.setText("+ Add feed");
        addFeed.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { addFeedRow("", "rtsp://"); }
        });
        root.addView(addFeed);

        header(root, "Dashboard");
        dashboardUrl = textRow(root, "Web page URL", config.dashboardUrl, InputType.TYPE_TEXT_VARIATION_URI);
        webReload = numberRow(root, "Auto-reload interval (seconds, 0 = never)", config.webReloadSec);
        ignoreSsl = checkRow(root, "Ignore SSL certificate errors", config.ignoreSslErrors,
                "Android 5.1 rejects certificates chaining through modern roots such as "
                        + "GTS Root R4. Enable only for a site you control.");

        header(root, "Layout");
        orientationGroup = radioRow(root, "Orientation",
                new String[]{"portrait", "landscape", "auto"},
                new String[]{"Portrait", "Landscape", "Auto"},
                config.orientation,
                "Portrait stacks camera over web; landscape puts camera left, web right.");
        cameraPosGroup = radioRow(root, "Camera position",
                new String[]{"first", "last"},
                new String[]{"Top / left", "Bottom / right"},
                config.cameraFirst ? "first" : "last",
                "Where the camera sits relative to the web pane.");
        splitPercent = numberRow(root, "Camera pane size (% of screen, 20-80)", config.splitPercent);
        videoFill = checkRow(root, "Scale video to fill the pane", config.videoFillPane,
                "Crops whatever overflows. Uncheck to letterbox the whole frame.");
        videoZoom = numberRow(root, "Video zoom (%, 100 = automatic)", config.videoZoomPercent);
        label(root, "", "Some cameras never tell libVLC their resolution, which leaves "
                + "the automatic fit with nothing to work from. Raise this until the "
                + "picture covers the pane.");

        header(root, "Button colours");
        activeColor = colorRow(root, "Selected camera", config.buttonActiveColor);
        inactiveColor = colorRow(root, "Other cameras", config.buttonInactiveColor);

        header(root, "Screen & presence");
        dimTimeout = numberRow(root, "Dim after (seconds, 0 = always on)", config.dimTimeoutSec);
        activeBrightness = numberRow(root, "Awake brightness (0-100)", pct(config.activeBrightness));
        dimBrightness = numberRow(root, "Dimmed brightness (0-100)", pct(config.dimBrightness));
        presenceEnabled = checkRow(root, "Wake on front-camera motion", config.presenceEnabled,
                "Camera is opened only while the screen is dimmed.");
        motionThreshold = numberRow(root, "Motion pixel threshold (lower = more sensitive)", config.motionThreshold);
        motionArea = numberRow(root, "Motion area trigger (per mille of frame)", config.motionAreaPerMille);
        pauseWhenDim = checkRow(root, "Stop the stream while dimmed", config.pauseStreamWhenDim,
                "Saves heat and bandwidth on a 24/7 device; costs 1-2s on wake.");

        header(root, "Stream tuning");
        networkCaching = numberRow(root, "Network caching (ms)", config.networkCachingMs);
        rtspTcp = checkRow(root, "RTSP over TCP", config.rtspOverTcp,
                "Recommended for Reolink; UDP drops frames on busy Wi-Fi.");

        header(root, "Kiosk");
        kioskMode = checkRow(root, "Run as home screen (kiosk)", config.kioskMode,
                "Makes this app the launcher and disables the Back button. "
                        + "Turn off here to get the normal home screen back.");

        Button save = new Button(this);
        save.setText("Save");
        save.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.topMargin = dp(24);
        save.setLayoutParams(slp);
        save.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { save(); }
        });
        root.addView(save);

        setContentView(scroll);
    }

    private void save() {
        config.feeds.clear();
        for (EditText[] row : feedRows) {
            String name = row[0].getText().toString().trim();
            String url = row[1].getText().toString().trim();
            if (url.isEmpty() || "rtsp://".equals(url)) continue;
            config.feeds.add(new Config.Feed(name.isEmpty() ? "Camera" : name, url));
        }

        config.dashboardUrl = dashboardUrl.getText().toString().trim();
        config.webReloadSec = readInt(webReload, config.webReloadSec, 0, 86400);
        config.ignoreSslErrors = ignoreSsl.isChecked();

        config.splitPercent = readInt(splitPercent, config.splitPercent, 20, 80);
        config.orientation = readRadio(orientationGroup, config.orientation);
        config.cameraFirst = "first".equals(
                readRadio(cameraPosGroup, config.cameraFirst ? "first" : "last"));
        config.videoFillPane = videoFill.isChecked();
        config.videoZoomPercent = readInt(videoZoom, config.videoZoomPercent, 100, 400);
        config.buttonActiveColor = Config.parseColor(
                activeColor.getText().toString(), config.buttonActiveColor);
        config.buttonInactiveColor = Config.parseColor(
                inactiveColor.getText().toString(), config.buttonInactiveColor);
        config.dimTimeoutSec = readInt(dimTimeout, config.dimTimeoutSec, 0, 86400);
        config.activeBrightness = readInt(activeBrightness, pct(config.activeBrightness), 0, 100) / 100f;
        config.dimBrightness = readInt(dimBrightness, pct(config.dimBrightness), 0, 100) / 100f;
        config.presenceEnabled = presenceEnabled.isChecked();
        config.motionThreshold = readInt(motionThreshold, config.motionThreshold, 1, 128);
        config.motionAreaPerMille = readInt(motionArea, config.motionAreaPerMille, 1, 1000);
        config.pauseStreamWhenDim = pauseWhenDim.isChecked();

        config.networkCachingMs = readInt(networkCaching, config.networkCachingMs, 100, 10000);
        config.rtspOverTcp = rtspTcp.isChecked();
        config.kioskMode = kioskMode.isChecked();

        config.save(this);
        KioskMode.setHomeAliasEnabled(this, config.kioskMode);

        if (config.kioskMode && !kioskWasEnabled) {
            Toast.makeText(this, "Pick Doorbell Wall as the home app", Toast.LENGTH_LONG).show();
            KioskMode.promptForHomeChooser(this);
        }
        finish();
    }

    // ------------------------------------------------------------ row builders

    private void addFeedRow(String name, String url) {
        final LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(4), 0, dp(4));

        final EditText nameField = new EditText(this);
        nameField.setHint("Name");
        nameField.setText(name);
        nameField.setSingleLine(true);
        nameField.setTextColor(0xFFFFFFFF);
        nameField.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        final EditText urlField = new EditText(this);
        urlField.setHint("rtsp://user:pass@host:554/h264Preview_01_main");
        urlField.setText(url);
        urlField.setSingleLine(true);
        urlField.setTextColor(0xFFFFFFFF);
        urlField.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        urlField.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 2.5f));

        Button remove = new Button(this);
        remove.setText("X");
        final EditText[] entry = new EditText[]{nameField, urlField};
        remove.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                feedRows.remove(entry);
                feedList.removeView(row);
            }
        });

        row.addView(nameField);
        row.addView(urlField);
        row.addView(remove);
        feedList.addView(row);
        feedRows.add(entry);
    }

    private static final int[] SWATCHES = {
            0xFF1E88E5, 0xFF00ACC1, 0xFF43A047, 0xFFFDD835, 0xFFFB8C00,
            0xFFE53935, 0xFF8E24AA, 0xFF6D4C41, 0xFF546E7A, 0xFF263238,
    };

    /**
     * Swatch strip plus a hex field. API 22 has no stock colour picker, and the
     * hex field keeps arbitrary colours reachable without a custom colour wheel.
     */
    private EditText colorRow(LinearLayout parent, String labelText, int current) {
        label(parent, labelText, null);

        final EditText hex = new EditText(this);
        hex.setText(Config.toHex(current));
        hex.setSingleLine(true);
        hex.setTextColor(0xFFFFFFFF);

        final View preview = new View(this);
        preview.setBackgroundDrawable(swatch(current));

        LinearLayout strip = new LinearLayout(this);
        strip.setOrientation(LinearLayout.HORIZONTAL);
        for (int i = 0; i < SWATCHES.length; i++) {
            final int c = SWATCHES[i];
            View sw = new View(this);
            sw.setBackgroundDrawable(swatch(c));
            LinearLayout.LayoutParams lp =
                    new LinearLayout.LayoutParams(0, dp(34), 1f);
            lp.setMargins(dp(2), dp(4), dp(2), dp(4));
            sw.setLayoutParams(lp);
            sw.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    hex.setText(Config.toHex(c));
                    preview.setBackgroundDrawable(swatch(c));
                }
            });
            strip.addView(sw);
        }
        parent.addView(strip);

        // Hex field with a live preview of whatever is typed.
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams pv = new LinearLayout.LayoutParams(dp(34), dp(34));
        pv.setMargins(0, 0, dp(8), 0);
        preview.setLayoutParams(pv);
        row.addView(preview);
        hex.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(hex);
        parent.addView(row);

        hex.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(android.text.Editable e) {
                preview.setBackgroundDrawable(
                        swatch(Config.parseColor(e.toString(), 0xFF000000)));
            }
        });
        return hex;
    }

    private GradientDrawable swatch(int color) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(4));
        g.setStroke(dp(1), 0x66FFFFFF);
        return g;
    }

    /** Horizontal radio group; each button carries its config value as its tag. */
    private RadioGroup radioRow(LinearLayout parent, String labelText,
                                String[] values, String[] labels,
                                String selected, String hint) {
        label(parent, labelText, hint);
        RadioGroup g = new RadioGroup(this);
        g.setOrientation(RadioGroup.HORIZONTAL);
        for (int i = 0; i < values.length; i++) {
            RadioButton b = new RadioButton(this);
            b.setId(View.generateViewId());
            b.setText(labels[i]);
            b.setTag(values[i]);
            b.setTextColor(0xFFDDDDDD);
            b.setPadding(0, dp(6), dp(18), dp(6));
            g.addView(b);
            if (values[i].equals(selected)) {
                g.check(b.getId());
            }
        }
        if (g.getCheckedRadioButtonId() == -1 && g.getChildCount() > 0) {
            g.check(g.getChildAt(0).getId());
        }
        parent.addView(g);
        return g;
    }

    private static String readRadio(RadioGroup g, String fallback) {
        int id = g.getCheckedRadioButtonId();
        if (id == -1) return fallback;
        View v = g.findViewById(id);
        return (v != null && v.getTag() != null) ? v.getTag().toString() : fallback;
    }

    private void header(LinearLayout parent, String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(0xFF4FC3F7);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        tv.setPadding(0, dp(20), 0, dp(6));
        tv.setGravity(Gravity.START);
        parent.addView(tv);
    }

    private void label(LinearLayout parent, String text, String hint) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(0xFFDDDDDD);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        tv.setPadding(0, dp(8), 0, 0);
        parent.addView(tv);
        if (hint != null) {
            TextView h = new TextView(this);
            h.setText(hint);
            h.setTextColor(0xFF8899AA);
            h.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            parent.addView(h);
        }
    }

    private EditText textRow(LinearLayout parent, String labelText, String value, int inputType) {
        label(parent, labelText, null);
        EditText et = new EditText(this);
        et.setText(value);
        et.setSingleLine(true);
        et.setTextColor(0xFFFFFFFF);
        et.setInputType(InputType.TYPE_CLASS_TEXT | inputType);
        parent.addView(et);
        return et;
    }

    private EditText numberRow(LinearLayout parent, String labelText, int value) {
        label(parent, labelText, null);
        EditText et = new EditText(this);
        et.setText(String.valueOf(value));
        et.setSingleLine(true);
        et.setTextColor(0xFFFFFFFF);
        et.setInputType(InputType.TYPE_CLASS_NUMBER);
        parent.addView(et);
        return et;
    }

    private CheckBox checkRow(LinearLayout parent, String labelText, boolean value, String hint) {
        CheckBox cb = new CheckBox(this);
        cb.setText(labelText);
        cb.setChecked(value);
        cb.setTextColor(0xFFDDDDDD);
        cb.setPadding(0, dp(10), 0, 0);
        parent.addView(cb);
        if (hint != null) {
            TextView h = new TextView(this);
            h.setText(hint);
            h.setTextColor(0xFF8899AA);
            h.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            h.setPadding(dp(32), 0, 0, 0);
            parent.addView(h);
        }
        return cb;
    }

    private static int readInt(EditText et, int fallback, int min, int max) {
        try {
            int v = Integer.parseInt(et.getText().toString().trim());
            return Math.max(min, Math.min(max, v));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static int pct(float v) {
        return Math.round(v * 100f);
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }
}
