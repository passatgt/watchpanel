package me.visztpeter.doorbell;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.content.res.ColorStateList;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.graphics.Bitmap;
import android.net.http.SslError;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.widget.ImageButton;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.SslErrorHandler;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.videolan.libvlc.util.VLCVideoLayout;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class MainActivity extends Activity {

    private VLCVideoLayout videoLayout;
    private TextView statusText;
    private TextView clockText;
    private LinearLayout buttonRow;
    private WebView webView;
    private View dimOverlay;
    private LinearLayout content;
    private LinearLayout cameraBlock;
    private View videoContainer;
    private TextView muteHint;
    private ImageButton talkBtn;
    private AudioBackchannel talk;
    private ToneGenerator tones;
    private View buttonBar;

    private Config config;
    private String appliedConfigHash;
    private String appliedLanguage;
    private VideoController video;
    private PresenceDetector presence;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private boolean dimmed;
    private int activeFeedIndex;

    private final SimpleDateFormat clockFormat =
            new SimpleDateFormat("HH:mm", Locale.getDefault());

    private final Runnable dimTask = new Runnable() {
        @Override public void run() { setDimmed(true); }
    };

    private final Runnable clockTask = new Runnable() {
        @Override public void run() {
            clockText.setText(clockFormat.format(new Date()));
            ui.postDelayed(this, 10_000L);
        }
    };

    private final Runnable webReloadTask = new Runnable() {
        @Override public void run() {
            if (!dimmed) webView.reload();
            if (config.webReloadSec > 0) ui.postDelayed(this, config.webReloadSec * 1000L);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        applyLocale(this, Config.load(this).language);
        appliedLanguage = Config.load(this).language;
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
                | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
        setContentView(R.layout.activity_main);

        videoLayout = findViewById(R.id.videoLayout);
        statusText = findViewById(R.id.statusText);
        clockText = findViewById(R.id.clockText);
        buttonRow = findViewById(R.id.buttonRow);
        webView = findViewById(R.id.webView);
        dimOverlay = findViewById(R.id.dimOverlay);
        content = findViewById(R.id.content);
        cameraBlock = findViewById(R.id.cameraBlock);
        videoContainer = findViewById(R.id.videoContainer);
        muteHint = findViewById(R.id.muteHint);
        talkBtn = findViewById(R.id.talkBtn);
        talk = new AudioBackchannel(talkListener);
        try {
            // Synthesised rather than a bundled sound file: no asset, no decoding.
            tones = new ToneGenerator(AudioManager.STREAM_MUSIC, 80);
        } catch (RuntimeException e) {
            tones = null;                     // some devices refuse; not fatal
        }
        // Push-to-talk rather than a latch: an open mic on a hallway wall is not
        // something to leave running by accident.
        talkBtn.setOnTouchListener(new View.OnTouchListener() {
            @Override public boolean onTouch(View v, MotionEvent e) {
                switch (e.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        v.setPressed(true);
                        startTalking();
                        return true;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        v.setPressed(false);
                        talk.stop();          // graceful: tail plays out
                        return true;
                    default:
                        return false;
                }
            }
        });
        // Tap the picture to toggle sound. The gear consumes its own taps, and a
        // tap while dimmed is swallowed by dispatchTouchEvent as a wake instead.
        videoContainer.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { toggleMute(); }
        });
        buttonBar = findViewById(R.id.buttonBar);

        findViewById(R.id.settingsBtn).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                startActivity(new Intent(MainActivity.this, SettingsActivity.class));
            }
        });

        config = Config.load(this);
        video = new VideoController(this, videoLayout, config, statusListener);
        presence = new PresenceDetector(config, presenceListener);

        setupWebView();
        videoLayout.post(new Runnable() {
            @Override public void run() { video.onViewReady(); }
        });

        applyConfig(true);
        ui.post(clockTask);
    }

    // ---------------------------------------------------------------- config

    private void applyConfig(boolean force) {
        Config fresh = Config.load(this);
        String hash = fresh.toJson().toString();
        if (!force && hash.equals(appliedConfigHash)) return;

        boolean feedsOrUrlChanged = appliedConfigHash == null
                || !sameStreamingSetup(config, fresh);

        if (appliedLanguage != null && !appliedLanguage.equals(fresh.language)) {
            appliedLanguage = fresh.language;
            recreate();                       // strings are baked in at inflate time
            return;
        }
        config = fresh;
        appliedConfigHash = hash;
        video.setConfig(config);

        applyOrientation();
        applyLayout();
        buildFeedButtons();

        if (feedsOrUrlChanged) {
            webView.loadUrl(config.dashboardUrl);
            if (!config.feeds.isEmpty()) {
                activeFeedIndex = Math.min(activeFeedIndex, config.feeds.size() - 1);
                selectFeed(activeFeedIndex);
            }
        }

        ui.removeCallbacks(webReloadTask);
        if (config.webReloadSec > 0) ui.postDelayed(webReloadTask, config.webReloadSec * 1000L);

        applyKioskComponent(config.kioskMode);
        setDimmed(false);
    }

    private static boolean sameStreamingSetup(Config a, Config b) {
        if (a == null) return false;
        if (!a.dashboardUrl.equals(b.dashboardUrl)) return false;
        if (a.feeds.size() != b.feeds.size()) return false;
        for (int i = 0; i < a.feeds.size(); i++) {
            if (!a.feeds.get(i).url.equals(b.feeds.get(i).url)) return false;
            if (!a.feeds.get(i).name.equals(b.feeds.get(i).name)) return false;
        }
        return a.rtspOverTcp == b.rtspOverTcp && a.networkCachingMs == b.networkCachingMs;
    }

    /** A wall bracket has one orientation; follow the setting, not the sensor. */
    /**
     * Overrides the device locale for this app only. Deprecated since API 25 but
     * this targets 22, where it is the supported route.
     */
    static void applyLocale(android.content.Context ctx, String language) {
        if (language == null || "system".equals(language)) return;
        Locale locale = new Locale(language);
        Locale.setDefault(locale);
        Resources res = ctx.getResources();
        Configuration cfg = res.getConfiguration();
        cfg.locale = locale;
        res.updateConfiguration(cfg, res.getDisplayMetrics());
    }

    private void applyOrientation() {
        String o = config.orientation == null ? "portrait" : config.orientation;
        if ("landscape".equals(o)) {
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
        } else if ("auto".equals(o)) {
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR);
        } else {
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        }
    }

    /**
     * Portrait stacks the camera block above the web pane; landscape puts it to
     * the left. Same view tree either way - only the container's axis and which
     * dimension carries the weight change.
     */
    private void applyLayout() {
        boolean landscape = getResources().getConfiguration().orientation
                == Configuration.ORIENTATION_LANDSCAPE;
        int percent = Math.max(20, Math.min(80, config.splitPercent));

        content.setOrientation(landscape ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        applyPaneOrder();
        setPaneWeight(cameraBlock, percent, landscape);
        setPaneWeight(webView, 100 - percent, landscape);
        content.requestLayout();
        videoContainer.post(new Runnable() {
            @Override public void run() { video.onPaneResized(); }
        });
    }

    /**
     * Reorders the two panes only when the order is actually wrong - detaching
     * the camera block destroys its surface and restarts the stream, so it is
     * not something to do on every layout pass.
     */
    private void applyPaneOrder() {
        boolean camFirst = config.cameraFirst;
        if ((content.indexOfChild(cameraBlock) == 0) == camFirst) return;
        content.removeView(cameraBlock);
        content.addView(cameraBlock, camFirst ? 0 : content.getChildCount());
    }

    private static void setPaneWeight(View v, int weight, boolean landscape) {
        LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) v.getLayoutParams();
        lp.width  = landscape ? 0 : ViewGroup.LayoutParams.MATCH_PARENT;
        lp.height = landscape ? ViewGroup.LayoutParams.MATCH_PARENT : 0;
        lp.weight = weight;
        v.setLayoutParams(lp);
    }

    private void applyKioskComponent(boolean enabled) {
        KioskMode.setHomeAliasEnabled(this, enabled);
    }

    // ----------------------------------------------------------------- feeds

    private void buildFeedButtons() {
        // Nothing to switch between with a single camera - reclaim the strip.
        buttonBar.setVisibility(config.feeds.size() > 1 ? View.VISIBLE : View.GONE);

        buttonRow.removeAllViews();
        int pad = dp(10);
        for (int i = 0; i < config.feeds.size(); i++) {
            final int index = i;
            Button b = new Button(this);
            b.setText(config.feeds.get(i).name);
            b.setAllCaps(false);
            b.setTextColor(feedTextColors());
            b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            b.setBackgroundDrawable(feedBackground());
            b.setPadding(pad, pad, pad, pad);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            lp.setMargins(dp(4), 0, dp(4), 0);
            b.setLayoutParams(lp);
            b.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { selectFeed(index); }
            });
            buttonRow.addView(b);
        }
        highlightActiveButton();
    }

    /** Selected / pressed / normal states built from the two configured colours. */
    private StateListDrawable feedBackground() {
        int active = config.buttonActiveColor;
        int inactive = config.buttonInactiveColor;
        StateListDrawable sld = new StateListDrawable();
        sld.addState(new int[]{android.R.attr.state_selected}, roundRect(active));
        sld.addState(new int[]{android.R.attr.state_pressed}, roundRect(shade(active, 0.75f)));
        sld.addState(new int[]{}, roundRect(inactive));
        return sld;
    }

    private GradientDrawable roundRect(int color) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(6));
        return g;
    }

    /** Pick black or white per state so light button colours stay readable. */
    private ColorStateList feedTextColors() {
        return new ColorStateList(
                new int[][]{new int[]{android.R.attr.state_selected}, new int[]{}},
                new int[]{contrastOn(config.buttonActiveColor),
                          contrastOn(config.buttonInactiveColor)});
    }

    private static int contrastOn(int bg) {
        // Rec. 601 luma; the threshold is where white text stops being legible.
        int r = (bg >> 16) & 0xFF, g = (bg >> 8) & 0xFF, b = bg & 0xFF;
        double luma = (0.299 * r + 0.587 * g + 0.114 * b) / 255.0;
        return luma > 0.6 ? 0xFF111111 : 0xFFFFFFFF;
    }

    private static int shade(int color, float factor) {
        int r = Math.round(((color >> 16) & 0xFF) * factor);
        int g = Math.round(((color >> 8) & 0xFF) * factor);
        int b = Math.round((color & 0xFF) * factor);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    /**
     * Settings has no visible control. Five taps in the top-right corner of the
     * web pane opens it - deliberately awkward, because on a wall panel the only
     * person who should reach settings is someone who knows the gesture.
     */
    private static final int SECRET_TAPS = 5;
    private static final long SECRET_WINDOW_MS = 3000L;

    private int secretTaps;
    private long secretFirstTapAt;

    private boolean handleSecretCorner(MotionEvent ev) {
        if (ev.getAction() != MotionEvent.ACTION_DOWN) return false;

        Rect r = new Rect();
        if (!webView.getGlobalVisibleRect(r)) return false;

        // Top-right quarter-width, fifth-height of the web pane.
        int cornerLeft = r.right - Math.max(dp(80), r.width() / 4);
        int cornerBottom = r.top + Math.max(dp(80), r.height() / 5);
        if (ev.getRawX() < cornerLeft || ev.getRawY() > cornerBottom) return false;

        long now = System.currentTimeMillis();
        if (now - secretFirstTapAt > SECRET_WINDOW_MS) {
            secretTaps = 0;
            secretFirstTapAt = now;
        }
        if (++secretTaps >= SECRET_TAPS) {
            secretTaps = 0;
            startActivity(new Intent(MainActivity.this, SettingsActivity.class));
            return true;
        }
        return false;
    }

    private final Runnable hideMuteHint = new Runnable() {
        @Override public void run() { muteHint.setVisibility(View.GONE); }
    };

    /**
     * There is no persistent speaker icon, so the state is announced briefly on
     * every toggle - otherwise there would be no way to tell sound was on.
     */
    private void toggleMute() {
        config.audioMuted = !config.audioMuted;
        config.save(this);
        appliedConfigHash = config.toJson().toString();   // do not re-apply on resume
        video.applyVolume();

        showHint(getString(config.audioMuted ? R.string.hint_muted : R.string.hint_sound_on), 1500L);
    }

    private void selectFeed(int index) {
        if (index < 0 || index >= config.feeds.size()) return;
        activeFeedIndex = index;
        highlightActiveButton();
        Config.Feed feed = config.feeds.get(index);
        String url = feed.url;

        // Talk-back needs RTSP and has to be enabled for this particular camera.
        talk.cancel();
        boolean canTalk = feed.talk && url.toLowerCase().startsWith("rtsp://");
        talkBtn.setVisibility(canTalk ? View.VISIBLE : View.GONE);

        video.play(url);
    }

    private void startTalking() {
        if (config.feeds.isEmpty()) return;
        resetIdleTimer();
        talk.start(config.feeds.get(activeFeedIndex).url);
    }

    private final AudioBackchannel.Listener talkListener = new AudioBackchannel.Listener() {
        @Override public void onTalkState(final boolean active, final int errorResId) {
            ui.post(new Runnable() {
                @Override public void run() {
                    if (errorResId != 0) {
                        showHint(getString(errorResId), 2500L);
                    } else if (active) {
                        playReadyCue();
                        showHint(getString(R.string.hint_talking), 0L);
                    } else {
                        ui.removeCallbacks(hideMuteHint);
                        muteHint.setVisibility(View.GONE);
                    }
                }
            });
        }
    };

    /** Short beep marking the moment the channel is open and the mic goes live. */
    private void playReadyCue() {
        if (tones == null) return;
        try {
            tones.startTone(ToneGenerator.TONE_PROP_BEEP, 120);
        } catch (RuntimeException ignored) {
        }
    }

    /** Shared transient overlay; timeout of 0 leaves it up until replaced. */
    private void showHint(String text, long timeoutMs) {
        muteHint.setText(text);
        muteHint.setVisibility(View.VISIBLE);
        ui.removeCallbacks(hideMuteHint);
        if (timeoutMs > 0) ui.postDelayed(hideMuteHint, timeoutMs);
    }

    private void highlightActiveButton() {
        for (int i = 0; i < buttonRow.getChildCount(); i++) {
            buttonRow.getChildAt(i).setSelected(i == activeFeedIndex);
        }
    }

    // -------------------------------------------------------------- dim/wake

    private void setDimmed(boolean dim) {
        if (dim && config.dimTimeoutSec <= 0) return;
        dimmed = dim;

        WindowManager.LayoutParams lp = getWindow().getAttributes();
        lp.screenBrightness = dim
                ? clamp01(config.dimBrightness)
                : clamp01(config.activeBrightness);
        getWindow().setAttributes(lp);
        dimOverlay.setVisibility(dim ? View.VISIBLE : View.GONE);

        ui.removeCallbacks(dimTask);
        if (dim) {
            if (config.pauseStreamWhenDim) video.pause();
            if (config.presenceEnabled) presence.start();
        } else {
            presence.stop();
            if (config.pauseStreamWhenDim) video.resume();
            if (config.dimTimeoutSec > 0) {
                ui.postDelayed(dimTask, config.dimTimeoutSec * 1000L);
            }
        }
    }

    private void resetIdleTimer() {
        if (config.dimTimeoutSec <= 0) return;
        ui.removeCallbacks(dimTask);
        ui.postDelayed(dimTask, config.dimTimeoutSec * 1000L);
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        if (dimmed) {
            // Swallow the wake tap so it does not also press whatever is underneath.
            if (ev.getAction() == MotionEvent.ACTION_DOWN) setDimmed(false);
            return true;
        }
        resetIdleTimer();
        // Counted first, but still passed through, so the page underneath keeps
        // working normally for anyone not performing the gesture.
        if (handleSecretCorner(ev)) return true;
        return super.dispatchTouchEvent(ev);
    }

    private final PresenceDetector.Listener presenceListener = new PresenceDetector.Listener() {
        @Override public void onPresence() {
            ui.post(new Runnable() {
                @Override public void run() { if (dimmed) setDimmed(false); }
            });
        }
    };

    // ---------------------------------------------------------------- webview

    @SuppressLint("SetJavaScriptEnabled")
    private void setupWebView() {
        WebSettings ws = webView.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setUseWideViewPort(true);
        ws.setLoadWithOverviewMode(true);
        ws.setBuiltInZoomControls(false);
        ws.setDisplayZoomControls(false);
        ws.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        ws.setCacheMode(WebSettings.LOAD_DEFAULT);
        // Defaults to true, which would leave an autoplaying <video> (the weather
        // radar loop) frozen on its first frame with nobody around to tap it.
        ws.setMediaPlaybackRequiresUserGesture(false);
        webView.setBackgroundColor(0xFF000000);
        webView.setVerticalScrollBarEnabled(false);

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return false;   // keep every navigation inside the panel
            }

            @Override
            public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
                // Android 5.1 ships a 2015-era CA store, so certificates that chain
                // through newer roots (GTS Root R4, ISRG Root X1) fail here even though
                // they are perfectly valid. Opt-in override for a known-good own site.
                if (config.ignoreSslErrors) {
                    handler.proceed();
                } else {
                    handler.cancel();
                    toast(getString(R.string.ssl_rejected));
                }
            }

            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                super.onPageStarted(view, url, favicon);
            }
        });
    }

    // ------------------------------------------------------------- lifecycle

    @Override
    protected void onResume() {
        super.onResume();
        goImmersive();
        applyConfig(false);
        if (!dimmed) {
            video.resume();
            resetIdleTimer();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        talk.cancel();
        presence.stop();
        video.pause();
    }

    @Override
    protected void onDestroy() {
        ui.removeCallbacksAndMessages(null);
        talk.cancel();
        if (tones != null) {
            tones.release();
            tones = null;
        }
        presence.stop();
        video.release();
        super.onDestroy();
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        applyLayout();
        goImmersive();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) goImmersive();
    }

    @Override
    public void onBackPressed() {
        // In kiosk mode the tablet has no other UI to fall back to.
        if (!config.kioskMode) super.onBackPressed();
    }

    private void goImmersive() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }

    // ----------------------------------------------------------------- misc

    private final VideoController.StatusListener statusListener =
            new VideoController.StatusListener() {
                @Override public void onStatus(int resId, boolean isError) {
                    if (resId == 0) {
                        statusText.setVisibility(View.GONE);
                    } else {
                        statusText.setText(resId);
                        statusText.setGravity(Gravity.CENTER);
                        statusText.setVisibility(View.VISIBLE);
                    }
                }
            };

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private static float clamp01(float v) {
        return v < 0f ? 0f : (v > 1f ? 1f : v);
    }
}
