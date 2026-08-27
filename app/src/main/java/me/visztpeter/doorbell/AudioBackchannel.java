package me.visztpeter.doorbell;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.net.Uri;
import android.util.Log;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Push-to-talk over the ONVIF RTSP audio backchannel.
 *
 * A camera that supports talk-back answers DESCRIBE with an extra sendonly audio
 * track when the request carries `Require: www.onvif.org/ver20/backchannel`. We
 * SETUP that track, PLAY, and then push mic audio into it as RTP.
 *
 * This is a hand-written RTSP client rather than part of the libVLC path,
 * because VLC's client is receive-only and has no notion of a backchannel. It
 * runs entirely alongside playback and never touches it.
 *
 * The Reolink D340W offers PCMU/8000 (G.711 mu-law, static payload type 0),
 * which needs no codec library - companding is a few lines of arithmetic.
 */
public class AudioBackchannel {

    public interface Listener {
        /** {@code errorResId} of 0 means no error. Ids rather than strings so
         *  this class needs no Context and the caller controls the language. */
        void onTalkState(boolean active, int errorResId);
    }

    private static final String TAG = "AudioBackchannel";
    private static final String REQUIRE = "www.onvif.org/ver20/backchannel";

    /** 20 ms of 8 kHz mono audio: the conventional G.711 packet size. */
    private static final int SAMPLES_PER_PACKET = 160;
    private static final int PAYLOAD_TYPE = 0;      // PCMU, static

    /**
     * How long to keep the session alive after the button is released.
     *
     * The camera holds a sizeable jitter buffer. Tearing the session down the
     * instant capture stops makes it flush that buffer, clipping the end of every
     * sentence, so we keep feeding silence until it has had time to play out.
     * 700 ms proved too short against this camera; it buffers more than that.
     */
    private static final int TAIL_MS = 2000;

    /** Long enough for the readiness tone to finish before the mic goes live. */
    private static final int CUE_GAP_MS = 260;

    /** G.711 mu-law encodes digital silence as 0xFF. */
    private static final byte ULAW_SILENCE = (byte) 0xFF;

    private final Listener listener;

    private volatile boolean running;   // session alive
    private volatile boolean talking;   // mic captured
    private Thread worker;

    public AudioBackchannel(Listener listener) {
        this.listener = listener;
    }

    public boolean isRunning() {
        return running;
    }

    /** Starts talking. The feed URL supplies host, path and credentials. */
    public void start(final String feedUrl) {
        if (running) return;
        running = true;
        talking = true;
        worker = new Thread(new Runnable() {
            @Override public void run() { session(feedUrl); }
        }, "backchannel");
        worker.start();
    }

    /** Release: stop capturing, but let the tail play out before teardown. */
    public void stop() {
        talking = false;
    }

    /** Hard stop, for pause/teardown where waiting is pointless. */
    public void cancel() {
        talking = false;
        running = false;
        worker = null;
    }

    // ------------------------------------------------------------ RTSP session

    private void session(String feedUrl) {
        Socket sock = null;
        AudioRecord rec = null;
        try {
            Uri u = Uri.parse(feedUrl);
            String host = u.getHost();
            int port = u.getPort() > 0 ? u.getPort() : 554;
            String user = "", pass = "";
            String info = u.getUserInfo();
            if (info != null && info.contains(":")) {
                user = info.substring(0, info.indexOf(':'));
                pass = info.substring(info.indexOf(':') + 1);
            }
            // Credentials live in the URL's userinfo; the request line must not
            // carry them or the digest will not match what the camera computes.
            String path = u.getPath() == null ? "" : u.getPath();
            String base = "rtsp://" + host + ":" + port + path;

            sock = new Socket(host, port);
            sock.setSoTimeout(6000);
            OutputStream out = sock.getOutputStream();
            InputStream in = new BufferedInputStream(sock.getInputStream());

            Auth auth = new Auth(user, pass);
            int cseq = 1;

            // --- DESCRIBE, asking for the backchannel ---
            String describe = request("DESCRIBE", base, cseq++, auth, true,
                    "Accept: application/sdp\r\n");
            String reply = exchange(out, in, describe);
            if (reply.startsWith("RTSP/1.0 401") && auth.learn(reply)) {
                describe = request("DESCRIBE", base, cseq++, auth, true,
                        "Accept: application/sdp\r\n");
                reply = exchange(out, in, describe);
            }
            if (!reply.startsWith("RTSP/1.0 200")) {
                fail(R.string.talk_err_refused, firstLine(reply));
                return;
            }

            String contentBase = header(reply, "Content-Base");
            if (contentBase == null) contentBase = base + "/";
            String track = backchannelTrack(reply);
            if (track == null) {
                fail(R.string.talk_err_no_channel, null);
                return;
            }
            String trackUrl = track.startsWith("rtsp://") ? track : contentBase + track;

            // --- SETUP, interleaved so everything shares this TCP connection ---
            String setup = request("SETUP", trackUrl, cseq++, auth, true,
                    "Transport: RTP/AVP/TCP;unicast;interleaved=0-1\r\n");
            reply = exchange(out, in, setup);
            if (reply.startsWith("RTSP/1.0 401") && auth.learn(reply)) {
                setup = request("SETUP", trackUrl, cseq++, auth, true,
                        "Transport: RTP/AVP/TCP;unicast;interleaved=0-1\r\n");
                reply = exchange(out, in, setup);
            }
            if (!reply.startsWith("RTSP/1.0 200")) {
                fail(R.string.talk_err_setup, firstLine(reply));
                return;
            }

            String session = header(reply, "Session");
            if (session != null && session.contains(";")) {
                session = session.substring(0, session.indexOf(';')).trim();
            }
            int channel = interleavedChannel(header(reply, "Transport"));

            // --- PLAY ---
            String play = request("PLAY", contentBase, cseq++, auth, true,
                    "Session: " + session + "\r\n");
            reply = exchange(out, in, play);
            if (!reply.startsWith("RTSP/1.0 200")) {
                fail(R.string.talk_err_play, firstLine(reply));
                return;
            }

            rec = openMic();
            if (rec == null) {
                fail(R.string.talk_err_no_mic, null);
                return;
            }
            // The mic is open but not yet recording. Announce readiness, then
            // give the cue tone time to finish before capture starts - otherwise
            // the beep is the first thing the doorbell hears.
            report(true, 0);
            Thread.sleep(CUE_GAP_MS);

            startDrain(in);
            pump(rec, out, channel);
            running = false;

        } catch (Exception e) {
            Log.w(TAG, "backchannel session ended", e);
            fail(R.string.talk_err_play, e.getClass().getSimpleName());
        } finally {
            if (rec != null) {
                try { rec.stop(); } catch (Exception ignored) { }
                rec.release();
            }
            if (sock != null) {
                try { sock.close(); } catch (Exception ignored) { }
            }
            running = false;
            report(false, 0);
        }
    }

    /**
     * The camera keeps sending on this socket while we talk. Nothing here needs
     * that data, but it has to be consumed or the receive buffer fills and the
     * connection stalls.
     */
    private void startDrain(final InputStream in) {
        Thread t = new Thread(new Runnable() {
            @Override public void run() {
                byte[] sink = new byte[4096];
                try {
                    while (running && in.read(sink) >= 0) {
                        // discard
                    }
                } catch (Exception ignored) {
                }
            }
        }, "backchannel-drain");
        t.setDaemon(true);
        t.start();
    }

    /** Streams mic audio as RTP until stop() is called. */
    private void pump(AudioRecord rec, OutputStream out, int channel) throws Exception {
        // When the mic could only open at 16 kHz we read twice as much and keep
        // every other sample, so what goes on the wire is always 8 kHz.
        int step = decimate ? 2 : 1;
        short[] pcm = new short[SAMPLES_PER_PACKET * step];
        byte[] packet = new byte[12 + SAMPLES_PER_PACKET];
        byte[] framed = new byte[4 + packet.length];

        int ssrc = new Random().nextInt();
        int seq = new Random().nextInt(0xFFFF);
        int timestamp = 0;

        rec.startRecording();
        int sent = 0;
        long began = System.currentTimeMillis();
        while (running && talking) {
            int want = SAMPLES_PER_PACKET * step;
            int got = 0;
            while (got < want && running && talking) {
                int n = rec.read(pcm, got, want - got);
                if (n <= 0) break;
                got += n;
            }
            if (got < want) continue;

            packet[0] = (byte) 0x80;                       // version 2
            packet[1] = (byte) PAYLOAD_TYPE;
            packet[2] = (byte) (seq >> 8);
            packet[3] = (byte) seq;
            packet[4] = (byte) (timestamp >> 24);
            packet[5] = (byte) (timestamp >> 16);
            packet[6] = (byte) (timestamp >> 8);
            packet[7] = (byte) timestamp;
            packet[8] = (byte) (ssrc >> 24);
            packet[9] = (byte) (ssrc >> 16);
            packet[10] = (byte) (ssrc >> 8);
            packet[11] = (byte) ssrc;
            for (int i = 0; i < SAMPLES_PER_PACKET; i++) {
                packet[12 + i] = linearToUlaw(pcm[i * step]);
            }

            // RTSP interleaved framing: '$', channel, 16-bit big-endian length.
            framed[0] = '$';
            framed[1] = (byte) channel;
            framed[2] = (byte) (packet.length >> 8);
            framed[3] = (byte) packet.length;
            System.arraycopy(packet, 0, framed, 4, packet.length);
            out.write(framed);
            out.flush();

            seq = (seq + 1) & 0xFFFF;
            timestamp += SAMPLES_PER_PACKET;              // 8 kHz clock
            sent++;
        }
        try {
            rec.stop();                                  // mic is done; the tail is silence
        } catch (Exception ignored) {
        }

        // Tail: same RTP timeline, silent payload, so the camera keeps playing
        // rather than flushing what it has not reached yet.
        java.util.Arrays.fill(packet, 12, packet.length, ULAW_SILENCE);
        long tailUntil = System.currentTimeMillis() + TAIL_MS;
        while (running && System.currentTimeMillis() < tailUntil) {
            packet[2] = (byte) (seq >> 8);
            packet[3] = (byte) seq;
            packet[4] = (byte) (timestamp >> 24);
            packet[5] = (byte) (timestamp >> 16);
            packet[6] = (byte) (timestamp >> 8);
            packet[7] = (byte) timestamp;

            framed[0] = '$';
            framed[1] = (byte) channel;
            framed[2] = (byte) (packet.length >> 8);
            framed[3] = (byte) packet.length;
            System.arraycopy(packet, 0, framed, 4, packet.length);
            out.write(framed);
            out.flush();

            seq = (seq + 1) & 0xFFFF;
            timestamp += SAMPLES_PER_PACKET;
            Thread.sleep(20);                            // real-time pacing
        }

        Log.i(TAG, "sent " + sent + " RTP packets (" + (sent * 20) + " ms of audio) in "
                + (System.currentTimeMillis() - began) + " ms, plus " + TAIL_MS + " ms tail");
    }

    /**
     * 8 kHz mono is what the backchannel wants. Old hardware does not always
     * offer it, so fall back to 16 kHz and drop every other sample.
     */
    private boolean decimate;

    private AudioRecord openMic() {
        int[] rates = {8000, 16000};
        for (int rate : rates) {
            int min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT);
            if (min <= 0) continue;
            AudioRecord r = new AudioRecord(MediaRecorder.AudioSource.MIC, rate,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                    Math.max(min, SAMPLES_PER_PACKET * 8));
            if (r.getState() == AudioRecord.STATE_INITIALIZED) {
                decimate = (rate != 8000);
                if (decimate) Log.i(TAG, "8 kHz unavailable; recording 16 kHz and decimating");
                return r;
            }
            r.release();
        }
        return null;
    }

    // --------------------------------------------------------------- G.711

    /** ITU-T G.711 mu-law companding: 16-bit linear PCM to one byte. */
    static byte linearToUlaw(short sample) {
        final int BIAS = 0x84, CLIP = 32635;
        int sign = (sample >> 8) & 0x80;
        int v = sign != 0 ? -sample : sample;
        if (v > CLIP) v = CLIP;
        v += BIAS;

        int exponent = 7;
        for (int mask = 0x4000; (v & mask) == 0 && exponent > 0; exponent--, mask >>= 1) {
            // walk down to the most significant set bit
        }
        int mantissa = (v >> (exponent + 3)) & 0x0F;
        return (byte) ~(sign | (exponent << 4) | mantissa);
    }

    // ---------------------------------------------------------------- RTSP bits

    private String request(String method, String url, int cseq, Auth auth,
                           boolean backchannel, String extra) throws Exception {
        StringBuilder sb = new StringBuilder();
        sb.append(method).append(' ').append(url).append(" RTSP/1.0\r\n");
        sb.append("CSeq: ").append(cseq).append("\r\n");
        sb.append("User-Agent: WatchPanel\r\n");
        if (backchannel) sb.append("Require: ").append(REQUIRE).append("\r\n");
        if (extra != null) sb.append(extra);
        String a = auth.header(method, url);
        if (a != null) sb.append("Authorization: ").append(a).append("\r\n");
        sb.append("\r\n");
        return sb.toString();
    }

    private String exchange(OutputStream out, InputStream in, String req) throws Exception {
        out.write(req.getBytes("UTF-8"));
        out.flush();
        return readReply(in);
    }

    /**
     * Reads one RTSP reply, stepping over any interleaved binary frames.
     *
     * The camera starts pushing RTP down this same connection the moment SETUP
     * succeeds, so a reply is not necessarily the next thing on the socket. An
     * interleaved frame is '$', a channel byte, a 16-bit length, then payload.
     */
    private String readReply(InputStream in) throws Exception {
        ByteArrayOutputStream acc = new ByteArrayOutputStream();
        while (true) {
            int b = in.read();
            if (b < 0) break;

            if (acc.size() == 0 && b == '$') {
                in.read();                                   // channel
                int hi = in.read(), lo = in.read();
                if (hi < 0 || lo < 0) break;
                skipFully(in, ((hi & 0xFF) << 8) | (lo & 0xFF));
                continue;
            }

            acc.write(b);
            String s = acc.toString("UTF-8");
            int split = s.indexOf("\r\n\r\n");
            if (split < 0) continue;

            Matcher m = Pattern.compile("Content-Length:\\s*(\\d+)",
                    Pattern.CASE_INSENSITIVE).matcher(s);
            int len = m.find() ? Integer.parseInt(m.group(1)) : 0;
            if (s.length() - (split + 4) >= len) return s;
        }
        return acc.toString("UTF-8");
    }

    private static void skipFully(InputStream in, int n) throws Exception {
        int done = 0;
        while (done < n) {
            long s = in.skip(n - done);
            if (s > 0) {
                done += (int) s;
            } else if (in.read() < 0) {
                return;
            } else {
                done++;
            }
        }
    }

    /** The control attribute of the sendonly audio track in the SDP. */
    private static String backchannelTrack(String reply) {
        int body = reply.indexOf("\r\n\r\n");
        if (body < 0) return null;
        String[] lines = reply.substring(body + 4).split("\r?\n");

        String control = null;
        boolean inAudio = false, sendonly = false;
        for (String raw : lines) {
            String line = raw.trim();
            if (line.startsWith("m=")) {
                if (inAudio && sendonly && control != null) return control;
                inAudio = line.startsWith("m=audio");
                sendonly = false;
                control = null;
            } else if (inAudio) {
                if (line.startsWith("a=control:")) control = line.substring(10).trim();
                else if (line.equals("a=sendonly")) sendonly = true;
            }
        }
        return (inAudio && sendonly) ? control : null;
    }

    private static int interleavedChannel(String transport) {
        if (transport != null) {
            Matcher m = Pattern.compile("interleaved=(\\d+)").matcher(transport);
            if (m.find()) return Integer.parseInt(m.group(1));
        }
        return 0;
    }

    private static String header(String reply, String name) {
        Matcher m = Pattern.compile("^" + name + ":\\s*(.+)$",
                Pattern.MULTILINE | Pattern.CASE_INSENSITIVE).matcher(reply);
        return m.find() ? m.group(1).trim() : null;
    }

    private static String firstLine(String s) {
        int i = s.indexOf("\r\n");
        return i > 0 ? s.substring(0, i) : s;
    }

    /** {@code detail} never reaches the screen; it only enriches the log. */
    private void fail(int resId, String detail) {
        Log.w(TAG, "talk-back failed (" + resId + ") " + (detail == null ? "" : detail));
        report(false, resId);
    }

    private void report(boolean active, int errorResId) {
        if (listener != null) listener.onTalkState(active, errorResId);
    }

    /**
     * RTSP digest auth. The nonce is per-connection on Reolink, so the challenge
     * and every authenticated request have to share one socket; the response
     * hash also folds in the method, so it is recomputed per request.
     */
    private static class Auth {
        private final String user, pass;
        private String realm, nonce;

        Auth(String user, String pass) {
            this.user = user;
            this.pass = pass;
        }

        boolean learn(String reply) {
            Matcher m = Pattern.compile("Digest realm=\"([^\"]+)\",\\s*nonce=\"([^\"]+)\"")
                    .matcher(reply);
            if (!m.find()) return false;
            realm = m.group(1);
            nonce = m.group(2);
            return true;
        }

        String header(String method, String uri) throws Exception {
            if (realm == null || nonce == null) return null;
            String ha1 = md5(user + ":" + realm + ":" + pass);
            String ha2 = md5(method + ":" + uri);
            String response = md5(ha1 + ":" + nonce + ":" + ha2);
            return String.format(Locale.US,
                    "Digest username=\"%s\", realm=\"%s\", nonce=\"%s\", uri=\"%s\", response=\"%s\"",
                    user, realm, nonce, uri, response);
        }

        private static String md5(String s) throws Exception {
            byte[] d = MessageDigest.getInstance("MD5").digest(s.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        }
    }
}
