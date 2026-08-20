package me.visztpeter.doorbell;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.videolan.libvlc.LibVLC;
import org.videolan.libvlc.Media;
import org.videolan.libvlc.MediaPlayer;
import org.videolan.libvlc.util.VLCVideoLayout;

import java.util.ArrayList;

/**
 * Owns the libVLC player plus the reconnect logic.
 *
 * libVLC rather than ExoPlayer/Media3 because the SC8830 has no HEVC decoder, so
 * a software fallback path matters, and because libVLC handles Reolink's RTSP
 * digest auth without special-casing.
 *
 * Rendering goes through VLCVideoLayout rather than a bare SurfaceView. With a
 * bare surface libVLC 3.x never reports the video geometry - both
 * onNewVideoLayout() and getCurrentVideoTrack() come back 0x0 - which makes any
 * scaling decision impossible. VLCVideoLayout owns that math internally and
 * exposes it as ScaleType.
 */
public class VideoController {

    public interface StatusListener {
        void onStatus(String text, boolean isError);
    }

    private static final String TAG = "VideoController";

    /** Restart the stream if no frame has arrived for this long. */
    private static final long STALL_TIMEOUT_MS = 12_000L;
    private static final long[] BACKOFF_MS = {1_000L, 2_000L, 5_000L, 10_000L, 20_000L, 30_000L};

    private final Context ctx;
    private final VLCVideoLayout videoLayout;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final StatusListener listener;

    private LibVLC libVlc;
    private MediaPlayer player;
    private Config config;

    private String currentUrl;
    private int retryIndex;
    private boolean viewReady;
    private boolean wantPlaying;
    private long lastFrameAt;

    private final Runnable reconnectTask = new Runnable() {
        @Override public void run() {
            if (wantPlaying && currentUrl != null) startInternal();
        }
    };

    private final Runnable watchdogTask = new Runnable() {
        @Override public void run() {
            if (wantPlaying && lastFrameAt > 0
                    && System.currentTimeMillis() - lastFrameAt > STALL_TIMEOUT_MS) {
                Log.w(TAG, "stream stalled, restarting");
                report("Stream stalled - reconnecting", true);
                scheduleReconnect();
            }
            ui.postDelayed(this, 4_000L);
        }
    };

    public VideoController(Context ctx, VLCVideoLayout videoLayout, Config config,
                           StatusListener listener) {
        this.ctx = ctx.getApplicationContext();
        this.videoLayout = videoLayout;
        this.config = config;
        this.listener = listener;
    }

    public void setConfig(Config c) {
        this.config = c;
        applyScale();
    }

    /** Called once the layout has been through a pass and is attached to the window. */
    public void onViewReady() {
        viewReady = true;
        if (wantPlaying && currentUrl != null) startInternal();
    }

    public void play(String url) {
        currentUrl = url;
        wantPlaying = true;
        retryIndex = 0;
        ui.removeCallbacks(watchdogTask);
        ui.postDelayed(watchdogTask, 4_000L);
        startInternal();
    }

    /** Stop decoding but remember the URL, so resume() can bring it straight back. */
    public void pause() {
        wantPlaying = false;
        ui.removeCallbacks(reconnectTask);
        ui.removeCallbacks(watchdogTask);
        releasePlayer();
    }

    public void resume() {
        // Guard against a double start: applyConfig() selects a feed and then
        // restores the awake state, and both paths would otherwise call play().
        if (wantPlaying && player != null) return;
        if (currentUrl != null) play(currentUrl);
    }

    public void release() {
        pause();
        if (libVlc != null) {
            libVlc.release();
            libVlc = null;
        }
    }

    /**
     * FIT_SCREEN crops the overflow so the video covers the pane; BEST_FIT keeps
     * the whole frame and letterboxes it. Neither distorts the aspect ratio -
     * that would be SURFACE_FILL.
     */
    public void applyScale() {
        // MediaPlayer.Event callbacks arrive on a libVLC thread, and setVideoScale
        // touches the view hierarchy - it must run on the UI thread or it fails.
        ui.post(new Runnable() {
            @Override public void run() { applyScaleNow(); }
        });
    }

    private void applyScaleNow() {
        if (player == null) return;
        int zoom = config.videoZoomPercent;
        try {
            if (zoom > 100) {
                // Explicit factor relative to the source's native size. This is the
                // fallback for streams whose geometry libVLC never reports, where
                // FIT_SCREEN has nothing to compute a crop from.
                player.setScale(zoom / 100f);
                Log.i(TAG, "scale -> manual " + zoom + "%");
            } else {
                MediaPlayer.ScaleType want = config.videoFillPane
                        ? MediaPlayer.ScaleType.SURFACE_FIT_SCREEN
                        : MediaPlayer.ScaleType.SURFACE_BEST_FIT;
                player.setScale(0f);        // 0 = let libVLC fit it
                player.setVideoScale(want);
                Log.i(TAG, "scale -> " + want);
            }
        } catch (Exception e) {
            Log.w(TAG, "applying scale failed", e);
        }
    }

    /** The pane changed size (rotation, or the split percentage moving). */
    public void onPaneResized() {
        applyScale();
    }

    private void ensureLibVlc() {
        if (libVlc != null) return;
        ArrayList<String> opts = new ArrayList<>();
        opts.add("--no-sub-autodetect-file");
        opts.add("--audio-time-stretch");
        opts.add("--avcodec-skiploopfilter=4");   // cheapest deblocking; the A7 needs the help
        opts.add("--network-caching=" + config.networkCachingMs);
        opts.add("--live-caching=" + config.networkCachingMs);
        if (config.rtspOverTcp) opts.add("--rtsp-tcp");
        opts.add("-vv");
        libVlc = new LibVLC(ctx, opts);
    }

    private void startInternal() {
        if (!viewReady || currentUrl == null || currentUrl.trim().isEmpty()) return;

        releasePlayer();
        ensureLibVlc();
        report("Connecting...", false);

        player = new MediaPlayer(libVlc);
        player.setEventListener(eventListener);
        player.attachViews(videoLayout, null, false, false);
        applyScale();

        Media media = new Media(libVlc, Uri.parse(currentUrl));
        // Hardware decode with software fallback. The doorbell is H.264, which the
        // SC8830 decodes in hardware; the fallback only matters if a feed is HEVC.
        media.setHWDecoderEnabled(true, true);
        media.addOption(":network-caching=" + config.networkCachingMs);
        media.addOption(":live-caching=" + config.networkCachingMs);
        media.addOption(":clock-jitter=0");
        media.addOption(":clock-synchro=0");
        if (config.rtspOverTcp) media.addOption(":rtsp-tcp");

        player.setMedia(media);
        media.release();

        lastFrameAt = System.currentTimeMillis();
        player.play();
    }

    private final MediaPlayer.EventListener eventListener = new MediaPlayer.EventListener() {
        @Override public void onEvent(MediaPlayer.Event event) {
            switch (event.type) {
                case MediaPlayer.Event.Playing:
                    retryIndex = 0;
                    lastFrameAt = System.currentTimeMillis();
                    report(null, false);
                    applyScale();       // the scale only sticks once a vout exists
                    break;
                case MediaPlayer.Event.Vout:
                    applyScale();
                    lastFrameAt = System.currentTimeMillis();
                    break;
                case MediaPlayer.Event.TimeChanged:
                case MediaPlayer.Event.PositionChanged:
                    lastFrameAt = System.currentTimeMillis();
                    break;
                case MediaPlayer.Event.Buffering:
                    if (event.getBuffering() < 100f) lastFrameAt = System.currentTimeMillis();
                    break;
                case MediaPlayer.Event.EncounteredError:
                    report("Cannot reach camera", true);
                    scheduleReconnect();
                    break;
                case MediaPlayer.Event.EndReached:
                    scheduleReconnect();
                    break;
                default:
                    break;
            }
        }
    };

    private void scheduleReconnect() {
        if (!wantPlaying) return;
        ui.removeCallbacks(reconnectTask);
        long delay = BACKOFF_MS[Math.min(retryIndex, BACKOFF_MS.length - 1)];
        retryIndex++;
        lastFrameAt = 0;   // suspend the watchdog until the retry actually starts
        Log.i(TAG, "reconnect in " + delay + "ms (attempt " + retryIndex + ")");
        ui.postDelayed(reconnectTask, delay);
    }

    private void releasePlayer() {
        if (player == null) return;
        player.setEventListener(null);
        try {
            if (player.isPlaying()) player.stop();
        } catch (Exception ignored) {
        }
        try {
            player.detachViews();
        } catch (Exception ignored) {
        }
        player.release();
        player = null;
    }

    private void report(final String text, final boolean isError) {
        if (listener == null) return;
        ui.post(new Runnable() {
            @Override public void run() { listener.onStatus(text, isError); }
        });
    }
}
