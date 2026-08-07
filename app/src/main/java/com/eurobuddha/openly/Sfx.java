package com.eurobuddha.openly;

import android.content.Context;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;

import java.util.Random;
import java.util.concurrent.ExecutorService;

/**
 * Procedural sound — the design spec's 5 quiet cues, synthesis engine ported 1:1 from the casino
 * {@code Sfx}. Sine, not square (no arcade). No external libs: each cue renders to a 16-bit mono PCM
 * buffer at 44100 Hz through a one-shot AudioTrack on a background thread; every path is try/caught so
 * odd audio HW never crashes the app. Master gain 1.6 (quieter than casino's 2.6). Default ON, with a
 * persisted toggle. Cues: tick (slider detents), lock (bet committed), seal (declare), settleWin,
 * settleEnd (loss/void/refund).
 */
public final class Sfx {

    private static final int SR = 44100;
    private static final float MASTER = 1.6f;
    // Bounded queue + DiscardPolicy: under a flood (e.g. rapid slider ticks) excess cues are dropped
    // rather than backing up and machine-gunning after the gesture ends.
    private static final ExecutorService EXEC = new java.util.concurrent.ThreadPoolExecutor(
            1, 2, 5, java.util.concurrent.TimeUnit.SECONDS,
            new java.util.concurrent.LinkedBlockingQueue<>(6),
            new java.util.concurrent.ThreadPoolExecutor.DiscardPolicy());
    private static final Random RND = new Random();
    private static volatile long lastTickMs = 0;

    private static final String PREFS = "openly_sfx", KEY = "on";
    private static volatile boolean enabled = true;

    private Sfx() {}

    public static void init(Context c) {
        try { enabled = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY, true); }
        catch (Throwable ignored) {}
    }
    public static boolean enabled() { return enabled; }
    public static void setEnabled(Context c, boolean on) {
        enabled = on;
        try { c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY, on).apply(); }
        catch (Throwable ignored) {}
    }
    public static void release() { try { EXEC.shutdownNow(); } catch (Throwable ignored) {} }

    // ---- the 5 cues ----

    /** 8ms high-passed noise pop — slider detents only. Time-gated so a fast drag can't flood the synth. */
    public static void tick() {
        long now = System.currentTimeMillis();
        if (now - lastTickMs < 55) return;
        lastTickMs = now;
        play(() -> { float[] b = buf(0.03f); noise(b, 0.008f, 0.04f, Filter.HIGHPASS, 4000, 0.7f, 0f); return b; });
    }

    /** Low sine "thunk" 180→120 Hz, 120ms — stake committed / bet posted. */
    public static void lock() {
        play(() -> { float[] b = buf(0.16f); slide(b, 180, 120, 0.12f, 0.14f, 0f); return b; });
    }

    /** Short band-passed swish — envelope tear on declare. */
    public static void seal() {
        play(() -> { float[] b = buf(0.16f); noise(b, 0.14f, 0.10f, Filter.BANDPASS, 1600, 0.9f, 0f); return b; });
    }

    /** 3 ascending sine notes 660/880/1320, ~0.5s — the win moment. */
    public static void settleWin() {
        play(() -> {
            float[] b = buf(0.55f);
            tone(b, 660, 0.16f, 0.12f, 0.00f);
            tone(b, 880, 0.16f, 0.12f, 0.15f);
            tone(b, 1320, 0.24f, 0.13f, 0.30f);
            return b;
        });
    }

    /** Single soft 440 Hz sine, ~0.3s — loss / void / refund (neutral, never a sad trombone). */
    public static void settleEnd() {
        play(() -> { float[] b = buf(0.32f); tone(b, 440, 0.30f, 0.10f, 0f); return b; });
    }

    /** Three quick bright pings, ~0.5s — an incoming counter offer arrived. Unmissable but not shrill. */
    public static void counter() {
        play(() -> {
            float[] b = buf(0.5f);
            tone(b, 1150, 0.10f, 0.15f, 0.00f);
            tone(b, 1150, 0.10f, 0.15f, 0.17f);
            tone(b, 1150, 0.10f, 0.15f, 0.34f);
            return b;
        });
    }

    // ---- synthesis (sine-only tone; slide + filtered noise for lock/seal/tick) ----

    private enum Filter { HIGHPASS, LOWPASS, BANDPASS }
    private interface Render { float[] run(); }

    private static float[] buf(float seconds) { return new float[Math.max(1, (int) (SR * seconds))]; }

    private static void tone(float[] b, float freq, float dur, float vol, float delay) {
        int start = (int) (delay * SR), n = (int) (dur * SR);
        double phase = 0, inc = 2 * Math.PI * freq / SR;
        for (int i = 0; i < n; i++) {
            int idx = start + i; if (idx < 0 || idx >= b.length) break;
            b[idx] += (float) Math.sin(phase) * vol * env(i, n);
            phase += inc;
        }
    }

    private static void slide(float[] b, float f0, float f1, float dur, float vol, float delay) {
        int start = (int) (delay * SR), n = (int) (dur * SR);
        double phase = 0;
        for (int i = 0; i < n; i++) {
            int idx = start + i; if (idx < 0 || idx >= b.length) break;
            float f = f0 + (f1 - f0) * (i / (float) n);
            b[idx] += (float) Math.sin(phase) * vol * env(i, n);
            phase += 2 * Math.PI * f / SR;
        }
    }

    private static void noise(float[] b, float dur, float vol, Filter f, float freq, float q, float delay) {
        int start = (int) (delay * SR), n = (int) (dur * SR);
        if (n <= 0) return;
        float[] tmp = new float[n];
        for (int i = 0; i < n; i++) tmp[i] = (RND.nextFloat() * 2 - 1) * (1 - i / (float) n);
        switch (f) {
            case HIGHPASS: highpass(tmp, n, freq); break;
            case LOWPASS:  lowpass(tmp, n, freq);  break;
            case BANDPASS: bandpass(tmp, n, freq, q); break;
        }
        for (int i = 0; i < n; i++) { int idx = start + i; if (idx < 0 || idx >= b.length) break; b[idx] += tmp[i] * vol * env(i, n); }
    }

    private static float env(int i, int n) {
        float a = n * 0.05f;
        return (i < a ? i / a : 1f) * (1f - (i / (float) n));
    }

    private static float rc(float freq) {
        float dt = 1f / SR, rc = 1f / (2f * (float) Math.PI * freq);
        return rc / (rc + dt);
    }
    private static void lowpass(float[] b, int n, float freq) {
        float a = 1f - rc(freq), prev = b[0];
        for (int i = 1; i < n; i++) { prev = prev + a * (b[i] - prev); b[i] = prev; }
    }
    private static void highpass(float[] b, int n, float freq) {
        float a = rc(freq), prevIn = b[0], prevOut = b[0];
        for (int i = 1; i < n; i++) { float in = b[i]; prevOut = a * (prevOut + in - prevIn); prevIn = in; b[i] = prevOut; }
    }
    private static void bandpass(float[] b, int n, float freq, float q) {
        float spread = Math.max(1.05f, 1f + 0.6f / Math.max(0.1f, q));
        lowpass(b, n, freq * spread); highpass(b, n, freq / spread);
    }

    // ---- playback ----

    private static void play(Render r) {
        if (!enabled) return;
        try {
            EXEC.execute(() -> {
                AudioTrack t = null;
                try {
                    short[] pcm = toPcm(r.run());
                    int min = AudioTrack.getMinBufferSize(SR, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
                    t = makeTrack(Math.max(min, 8192));
                    if (t == null) return;
                    t.play();
                    int off = 0;
                    while (off < pcm.length) { int w = t.write(pcm, off, pcm.length - off); if (w <= 0) break; off += w; }
                    long ms = (pcm.length * 1000L / SR) + 60;
                    try { Thread.sleep(Math.min(ms, 4000)); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
                    try { t.stop(); } catch (Throwable ignored) {}
                } catch (Throwable ignored) {
                } finally { if (t != null) { try { t.release(); } catch (Throwable ignored) {} } }
            });
        } catch (Throwable ignored) {}
    }

    private static short[] toPcm(float[] mix) {
        short[] out = new short[mix.length];
        for (int i = 0; i < mix.length; i++) {
            float v = mix[i] * MASTER;
            if (v > 1f) v = 1f; else if (v < -1f) v = -1f;
            out[i] = (short) (v * 32767f);
        }
        return out;
    }

    private static AudioTrack makeTrack(int bufferBytes) {
        try {
            return new AudioTrack.Builder()
                    .setAudioAttributes(new android.media.AudioAttributes.Builder()
                            .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC).build())
                    .setAudioFormat(new AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(SR)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                    .setBufferSizeInBytes(bufferBytes)
                    .setTransferMode(AudioTrack.MODE_STREAM).build();
        } catch (Throwable t) {
            try {
                return new AudioTrack(AudioManager.STREAM_MUSIC, SR, AudioFormat.CHANNEL_OUT_MONO,
                        AudioFormat.ENCODING_PCM_16BIT, bufferBytes, AudioTrack.MODE_STREAM);
            } catch (Throwable t2) { return null; }
        }
    }
}
