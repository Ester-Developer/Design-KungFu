package com.kungfuchess.view;

import javax.sound.sampled.*;
import java.io.*;
import java.util.HashMap;
import java.util.Map;

/**
 * Plays short sound effects using only {@code javax.sound.sampled} (JDK built-in).
 *
 * <p>Each sound file is loaded once and cached as a {@link Clip}. Replay resets to
 * frame 0 so rapid re-triggering works correctly. All failures are swallowed
 * gracefully — a missing or broken audio file must never crash the game.</p>
 *
 * <p>If a {@code .wav} file is absent from the resources directory, a short synthetic
 * tone is generated in memory so the game runs immediately without external assets.</p>
 */
public class SoundManager {

    private static final String SOUND_DIR = "src/main/resources/assets/sound/";
    private static final int SAMPLE_RATE = 22050;

    private final Map<String, Clip> cache = new HashMap<>();

    // -------------------------------------------------------------------------
    // Public sound triggers
    // -------------------------------------------------------------------------

    /** Bright blip: a piece just started moving. */
    public void playMoveStart() { play("move_start.wav", () -> synth(880, 80, 0.35)); }

    /** Soft thud: a piece landed on an empty square. */
    public void playMoveLand()  { play("move_land.wav",  () -> synth(330, 100, 0.25)); }

    /** Sharp percussive hit: a capture occurred. Noise burst onset + deep tone. */
    public void playCapture()   { play("capture.wav",    () -> synthCapture()); }

    /**
     * Knight jump whoosh: rising pitch sweep distinct from playMoveStart.
     * Synthesized as a frequency-swept sine (200→900 Hz) over 120ms.
     */
    public void playJump()      { play("jump.wav",       () -> synthJump()); }

    /**
     * Dodge counter-capture: a piece successfully dodged an incoming attacker.
     * Synthesized as a quick descending-then-ascending sweep (600→200→800 Hz, 150ms)
     * with a slight reverb tail — clearly distinct from move-start and jump.
     */
    public void playDodge()     { play("dodge.wav",      () -> synthDodge()); }

    /**
     * Scream attack: a piece screams at an adjacent enemy.
     * Synthesized as a sharp high-pitched burst (1200→400 Hz, 80ms) — piercing and
     * clearly distinct from all other game sounds.
     */
    public void playScream()    { play("scream.wav",     () -> synthScream()); }

    /**
     * Triumphant fanfare: game over.
     * Ascending arpeggio across 6 notes with layered sine+square timbre,
     * noticeably longer and more celebratory than a plain chord.
     */
    public void playGameOver()  { play("game_over.wav",  () -> synthFanfare()); }

    /**
     * Short dissonant buzz: an illegal move was attempted.
     * Clearly negative in character — distinct from all other sounds.
     */
    public void playIllegal()   { play("illegal.wav",    () -> synthIllegal()); }

    /**
     * Shimmer/ascend: a pawn was promoted.
     * Bright rising arpeggio, clearly distinct from move/capture/illegal/game-over.
     */
    public void playPromotion() { play("promotion.wav",  () -> synthPromotion()); }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    /** Plays the named file (cached), falling back to the supplier if absent/broken. */
    private void play(String filename, java.util.function.Supplier<byte[]> fallback) {
        try {
            Clip clip = cache.computeIfAbsent(filename, k -> loadOrSynth(k, fallback));
            if (clip == null) return;
            clip.stop();
            clip.setFramePosition(0);
            clip.start();
        } catch (Exception ignored) { /* never crash the game */ }
    }

    private Clip loadOrSynth(String filename, java.util.function.Supplier<byte[]> fallback) {
        File file = new File(SOUND_DIR + filename);
        if (file.exists()) {
            try (AudioInputStream ais = AudioSystem.getAudioInputStream(file)) {
                return openClip(ais);
            } catch (Exception ignored) {}
        }
        try {
            byte[] pcm = fallback.get();
            return openClip(pcmToAudioStream(pcm));
        } catch (Exception ignored) {}
        return null;
    }

    private static Clip openClip(AudioInputStream ais) throws LineUnavailableException, IOException {
        Clip clip = AudioSystem.getClip();
        clip.open(ais);
        return clip;
    }

    private static AudioInputStream pcmToAudioStream(byte[] pcm) {
        AudioFormat fmt = new AudioFormat(SAMPLE_RATE, 16, 1, true, false);
        return new AudioInputStream(new ByteArrayInputStream(pcm), fmt, pcm.length / 2);
    }

    /**
     * Generates a simple sine-wave tone as 16-bit signed little-endian PCM.
     *
     * @param hz         frequency in Hz
     * @param durationMs duration in milliseconds
     * @param amplitude  peak amplitude (0.0–1.0)
     */
    private static byte[] synth(double hz, int durationMs, double amplitude) {
        int samples = SAMPLE_RATE * durationMs / 1000;
        byte[] buf = new byte[samples * 2];
        for (int i = 0; i < samples; i++) {
            double t = (double) i / SAMPLE_RATE;
            double env = i < samples * 0.8 ? 1.0 : (1.0 - (i - samples * 0.8) / (samples * 0.2));
            short sample = (short) (Math.sin(2 * Math.PI * hz * t) * amplitude * env * Short.MAX_VALUE);
            buf[i * 2]     = (byte) (sample & 0xFF);
            buf[i * 2 + 1] = (byte) ((sample >> 8) & 0xFF);
        }
        return buf;
    }

    /**
     * Knight jump whoosh: frequency sweep from 200 Hz to 900 Hz over 120ms.
     * Reads as a quick upward "whoosh" — clearly distinct from the flat 880 Hz blip
     * of playMoveStart.
     */
    private static byte[] synthJump() {
        int durationMs = 120;
        int samples = SAMPLE_RATE * durationMs / 1000;
        byte[] buf = new byte[samples * 2];
        double f0 = 200.0, f1 = 900.0;
        for (int i = 0; i < samples; i++) {
            double t = (double) i / SAMPLE_RATE;
            double frac = (double) i / samples;
            double freq = f0 + (f1 - f0) * frac;
            double env = i < samples * 0.7 ? 1.0 : (1.0 - (frac - 0.7) / 0.3);
            short sample = (short) (Math.sin(2 * Math.PI * freq * t) * 0.4 * env * Short.MAX_VALUE);
            buf[i * 2]     = (byte) (sample & 0xFF);
            buf[i * 2 + 1] = (byte) ((sample >> 8) & 0xFF);
        }
        return buf;
    }

    /**
     * Dodge whoosh: V-shaped frequency sweep (600→200→800 Hz) over 150ms.
     * The dip-then-rise reads as "dodge" — clearly distinct from the plain rising
     * sweep of synthJump and the flat blip of playMoveStart.
     */
    private static byte[] synthDodge() {
        int durationMs = 150;
        int samples = SAMPLE_RATE * durationMs / 1000;
        byte[] buf = new byte[samples * 2];
        double f0 = 600.0, fMid = 200.0, f1 = 800.0;
        int half = samples / 2;
        for (int i = 0; i < samples; i++) {
            double t = (double) i / SAMPLE_RATE;
            double frac = (double) i / samples;
            double freq = (i < half)
                ? f0 + (fMid - f0) * ((double) i / half)
                : fMid + (f1 - fMid) * ((double)(i - half) / (samples - half));
            double env = i < samples * 0.75 ? 1.0 : (1.0 - (frac - 0.75) / 0.25);
            short sample = (short) (Math.sin(2 * Math.PI * freq * t) * 0.45 * env * Short.MAX_VALUE);
            buf[i * 2]     = (byte) (sample & 0xFF);
            buf[i * 2 + 1] = (byte) ((sample >> 8) & 0xFF);
        }
        return buf;
    }

    /**
     * Scream attack: sharp descending-pitch burst (1400→300 Hz) over 80ms.
     * Piercing onset that drops quickly — unmistakably distinct from all other sounds.
     */
    private static byte[] synthScream() {
        int durationMs = 80;
        int samples = SAMPLE_RATE * durationMs / 1000;
        byte[] buf = new byte[samples * 2];
        double f0 = 1400.0, f1 = 300.0;
        for (int i = 0; i < samples; i++) {
            double t    = (double) i / SAMPLE_RATE;
            double frac = (double) i / samples;
            double freq = f0 + (f1 - f0) * frac;
            double env  = 1.0 - frac;  // quick linear fade-out
            short sample = (short) (Math.sin(2 * Math.PI * freq * t) * 0.55 * env * Short.MAX_VALUE);
            buf[i * 2]     = (byte) (sample & 0xFF);
            buf[i * 2 + 1] = (byte) ((sample >> 8) & 0xFF);
        }
        return buf;
    }

    /**
     * Triumphant fanfare: 8-note ascending arpeggio (C4–E4–G4–C5–E5–G5–C6–E6),
     * each note 140ms, layered sine+square+harmonic for a brighter, fuller timbre.
     * Reverb-like decay tail added by mixing a quieter delayed copy. Total ~1120ms.
     */
    private static byte[] synthFanfare() {
        double[] freqs = { 261.63, 329.63, 392.00, 523.25, 659.25, 783.99, 1046.50, 1318.51 };
        int noteMs = 140;
        int noteSamples = SAMPLE_RATE * noteMs / 1000;
        int totalSamples = noteSamples * freqs.length;
        // Add a decay tail of 300ms
        int tailSamples = SAMPLE_RATE * 300 / 1000;
        int total = totalSamples + tailSamples;
        double[] mix = new double[total];

        for (int n = 0; n < freqs.length; n++) {
            double f = freqs[n];
            for (int i = 0; i < noteSamples; i++) {
                double t = (double) i / SAMPLE_RATE;
                double env = i < noteSamples * 0.75 ? 1.0
                    : (1.0 - (i - noteSamples * 0.75) / (noteSamples * 0.25));
                double sine    = Math.sin(2 * Math.PI * f * t);
                double square  = Math.signum(sine) * 0.25;
                double harmonic = Math.sin(2 * Math.PI * f * 2 * t) * 0.15; // 2nd harmonic
                double val = (sine * 0.55 + square + harmonic) * 0.42 * env;
                int idx = n * noteSamples + i;
                mix[idx] += val;
                // Reverb tail: quieter delayed copy 80ms later
                int delayIdx = idx + SAMPLE_RATE * 80 / 1000;
                if (delayIdx < total) mix[delayIdx] += val * 0.25;
            }
        }
        byte[] buf = new byte[total * 2];
        for (int i = 0; i < total; i++) {
            short sample = (short) (Math.max(-1.0, Math.min(1.0, mix[i])) * Short.MAX_VALUE);
            buf[i * 2]     = (byte) (sample & 0xFF);
            buf[i * 2 + 1] = (byte) ((sample >> 8) & 0xFF);
        }
        return buf;
    }

    /**
     * Forceful capture hit: short white-noise burst (percussive transient) at onset
     * followed by a deep 110 Hz tone, total 200ms. Reads as a real "hit".
     */
    private static byte[] synthCapture() {
        int durationMs = 200;
        int samples = SAMPLE_RATE * durationMs / 1000;
        byte[] buf = new byte[samples * 2];
        java.util.Random rng = new java.util.Random(42); // fixed seed for determinism
        int burstSamples = SAMPLE_RATE * 25 / 1000; // 25ms noise burst
        for (int i = 0; i < samples; i++) {
            double t = (double) i / SAMPLE_RATE;
            double env = i < samples * 0.7 ? 1.0 : (1.0 - (i - samples * 0.7) / (samples * 0.3));
            double tone  = Math.sin(2 * Math.PI * 110.0 * t) * 0.65;
            double noise = (i < burstSamples) ? (rng.nextDouble() * 2 - 1) * 0.5 : 0.0;
            double val   = (tone + noise) * env;
            short sample = (short) (Math.max(-1.0, Math.min(1.0, val)) * Short.MAX_VALUE);
            buf[i * 2]     = (byte) (sample & 0xFF);
            buf[i * 2 + 1] = (byte) ((sample >> 8) & 0xFF);
        }
        return buf;
    }

    /**
     * Dissonant buzz for illegal moves: two detuned frequencies (220 Hz + 233 Hz)
     * producing a harsh beating effect, 180ms.
     */
    private static byte[] synthIllegal() {
        int durationMs = 180;
        int samples = SAMPLE_RATE * durationMs / 1000;
        byte[] buf = new byte[samples * 2];
        double f1 = 220.0, f2 = 233.0;
        for (int i = 0; i < samples; i++) {
            double t = (double) i / SAMPLE_RATE;
            double env = i < samples * 0.6 ? 1.0 : (1.0 - (i - samples * 0.6) / (samples * 0.4));
            double val = (Math.sin(2 * Math.PI * f1 * t) + Math.sin(2 * Math.PI * f2 * t))
                         * 0.4 * env;
            short sample = (short) (val * Short.MAX_VALUE);
            buf[i * 2]     = (byte) (sample & 0xFF);
            buf[i * 2 + 1] = (byte) ((sample >> 8) & 0xFF);
        }
        return buf;
    }

    /**
     * Promotion shimmer: rapid ascending arpeggio (C5–E5–G5–C6), each note 60ms,
     * with a bright sine tone. Total ~240ms. Clearly distinct from all other sounds.
     */
    private static byte[] synthPromotion() {
        double[] freqs = { 523.25, 659.25, 783.99, 1046.50 };
        int noteMs = 60;
        int noteSamples = SAMPLE_RATE * noteMs / 1000;
        int total = noteSamples * freqs.length;
        byte[] buf = new byte[total * 2];
        for (int n = 0; n < freqs.length; n++) {
            double f = freqs[n];
            for (int i = 0; i < noteSamples; i++) {
                double t = (double) i / SAMPLE_RATE;
                double env = i < noteSamples * 0.7 ? 1.0
                    : (1.0 - (i - noteSamples * 0.7) / (noteSamples * 0.3));
                short sample = (short) (Math.sin(2 * Math.PI * f * t) * 0.5 * env * Short.MAX_VALUE);
                int idx = (n * noteSamples + i) * 2;
                buf[idx]     = (byte) (sample & 0xFF);
                buf[idx + 1] = (byte) ((sample >> 8) & 0xFF);
            }
        }
        return buf;
    }
}
