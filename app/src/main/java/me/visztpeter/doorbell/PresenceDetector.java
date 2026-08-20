package me.visztpeter.doorbell;

import android.graphics.ImageFormat;
import android.graphics.SurfaceTexture;
import android.hardware.Camera;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;

import java.util.List;

/**
 * Wakes the screen when somebody approaches the tablet.
 *
 * Deliberately NOT an ML model. On a 1.3GHz Cortex-A7, ML Kit face detection
 * runs at a few frames per second and pins a core; luma frame-differencing on a
 * downsampled Y plane costs well under a millisecond per frame. The camera HAL's
 * own face detector is used too when the device advertises it, since that runs
 * on the ISP and is effectively free.
 *
 * The camera is only held open while the screen is dimmed, which is the only
 * time the answer matters.
 */
public class PresenceDetector {

    public interface Listener {
        void onPresence();
    }

    private static final String TAG = "PresenceDetector";
    private static final int TARGET_W = 320, TARGET_H = 240;
    private static final long MIN_FRAME_INTERVAL_MS = 200;   // ~5 fps is plenty
    private static final int WARMUP_FRAMES = 5;              // let AE/AWB settle

    private final Config config;
    private final Listener listener;

    private HandlerThread thread;
    private Handler handler;
    private Camera camera;
    private SurfaceTexture dummyTexture;

    private byte[] prevLuma;
    private int frameW, frameH;
    private long lastProcessedAt;
    private int framesSeen;
    private volatile boolean running;

    public PresenceDetector(Config config, Listener listener) {
        this.config = config;
        this.listener = listener;
    }

    public boolean isRunning() {
        return running;
    }

    public void start() {
        if (running) return;
        running = true;
        thread = new HandlerThread("presence");
        thread.start();
        handler = new Handler(thread.getLooper());
        handler.post(new Runnable() {
            @Override public void run() { openCamera(); }
        });
    }

    public void stop() {
        if (!running) return;
        running = false;
        if (handler != null) {
            handler.post(new Runnable() {
                @Override public void run() { closeCamera(); }
            });
        }
        if (thread != null) {
            thread.quitSafely();
            thread = null;
            handler = null;
        }
    }

    private void openCamera() {
        int camId = findFrontCamera();
        if (camId < 0) {
            Log.w(TAG, "no front camera; presence detection disabled");
            running = false;
            return;
        }
        try {
            camera = Camera.open(camId);
            Camera.Parameters p = camera.getParameters();

            Camera.Size size = pickPreviewSize(p.getSupportedPreviewSizes());
            p.setPreviewSize(size.width, size.height);
            p.setPreviewFormat(ImageFormat.NV21);

            List<String> modes = p.getSupportedFocusModes();
            if (modes != null && modes.contains(Camera.Parameters.FOCUS_MODE_INFINITY)) {
                p.setFocusMode(Camera.Parameters.FOCUS_MODE_INFINITY);
            }
            camera.setParameters(p);

            frameW = size.width;
            frameH = size.height;
            prevLuma = null;
            framesSeen = 0;

            // A preview target is mandatory even though nothing is displayed.
            dummyTexture = new SurfaceTexture(10);
            camera.setPreviewTexture(dummyTexture);

            int bufSize = frameW * frameH * ImageFormat.getBitsPerPixel(ImageFormat.NV21) / 8;
            camera.addCallbackBuffer(new byte[bufSize]);
            camera.addCallbackBuffer(new byte[bufSize]);
            camera.setPreviewCallbackWithBuffer(previewCallback);

            camera.startPreview();
            tryStartHardwareFaceDetection(p);
            Log.i(TAG, "presence camera up at " + frameW + "x" + frameH);
        } catch (Exception e) {
            Log.e(TAG, "camera open failed", e);
            closeCamera();
            running = false;
        }
    }

    /** Free when the HAL supports it; silently skipped when it does not. */
    private void tryStartHardwareFaceDetection(Camera.Parameters p) {
        try {
            if (p.getMaxNumDetectedFaces() <= 0) return;
            camera.setFaceDetectionListener(new Camera.FaceDetectionListener() {
                @Override public void onFaceDetection(Camera.Face[] faces, Camera c) {
                    if (faces != null && faces.length > 0) fire();
                }
            });
            camera.startFaceDetection();
            Log.i(TAG, "hardware face detection enabled");
        } catch (Exception e) {
            Log.i(TAG, "hardware face detection unavailable: " + e.getMessage());
        }
    }

    private void closeCamera() {
        if (camera != null) {
            try {
                camera.setPreviewCallbackWithBuffer(null);
                camera.stopPreview();
            } catch (Exception ignored) {
            }
            camera.release();
            camera = null;
        }
        if (dummyTexture != null) {
            dummyTexture.release();
            dummyTexture = null;
        }
        prevLuma = null;
    }

    private final Camera.PreviewCallback previewCallback = new Camera.PreviewCallback() {
        @Override public void onPreviewFrame(byte[] data, Camera cam) {
            try {
                long now = System.currentTimeMillis();
                if (data != null && now - lastProcessedAt >= MIN_FRAME_INTERVAL_MS) {
                    lastProcessedAt = now;
                    analyse(data);
                }
            } finally {
                if (cam != null && data != null) cam.addCallbackBuffer(data);
            }
        }
    };

    /**
     * Samples every 4th pixel in both axes, then subtracts the frame's mean luma
     * shift before thresholding, so an auto-exposure ramp does not read as motion.
     */
    private void analyse(byte[] data) {
        final int step = 4;
        final int lumaLen = frameW * frameH;
        if (data.length < lumaLen) return;

        if (prevLuma == null || prevLuma.length != lumaLen) {
            prevLuma = new byte[lumaLen];
            System.arraycopy(data, 0, prevLuma, 0, lumaLen);
            return;
        }

        int samples = 0;
        long shiftSum = 0;
        for (int y = 0; y < frameH; y += step) {
            int row = y * frameW;
            for (int x = 0; x < frameW; x += step) {
                int i = row + x;
                shiftSum += (data[i] & 0xff) - (prevLuma[i] & 0xff);
                samples++;
            }
        }
        if (samples == 0) return;
        final int meanShift = (int) (shiftSum / samples);

        int changed = 0;
        final int threshold = config.motionThreshold;
        for (int y = 0; y < frameH; y += step) {
            int row = y * frameW;
            for (int x = 0; x < frameW; x += step) {
                int i = row + x;
                int d = ((data[i] & 0xff) - (prevLuma[i] & 0xff)) - meanShift;
                if (d < 0) d = -d;
                if (d > threshold) changed++;
            }
        }

        System.arraycopy(data, 0, prevLuma, 0, lumaLen);

        if (++framesSeen <= WARMUP_FRAMES) return;

        int perMille = (int) (changed * 1000L / samples);
        if (perMille >= config.motionAreaPerMille) {
            Log.d(TAG, "motion " + perMille + "/1000");
            fire();
        }
    }

    private void fire() {
        if (listener != null) listener.onPresence();
    }

    private static int findFrontCamera() {
        Camera.CameraInfo info = new Camera.CameraInfo();
        int n = Camera.getNumberOfCameras();
        for (int i = 0; i < n; i++) {
            Camera.getCameraInfo(i, info);
            if (info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) return i;
        }
        return n > 0 ? 0 : -1;
    }

    private static Camera.Size pickPreviewSize(List<Camera.Size> sizes) {
        Camera.Size best = null;
        for (Camera.Size s : sizes) {
            if (s.width >= TARGET_W && s.height >= TARGET_H) {
                if (best == null || s.width * s.height < best.width * best.height) best = s;
            }
        }
        if (best == null) {
            for (Camera.Size s : sizes) {
                if (best == null || s.width * s.height > best.width * best.height) best = s;
            }
        }
        return best;
    }
}
