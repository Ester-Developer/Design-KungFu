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
public final class SoundManager {

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

    /** Sharp low thud: a capture occurred. */
    public void playCapture()   { play("capture.wav",    () -> synth(160, 130, 0.55)); }

    /** Short fanfare chord: game over. */
    public void playGameOver()  { play("game_over.wav",  () -> synthChord()); }

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
        // Try loading from disk first
        File file = new File(SOUND_DIR + filename);
        if (file.exists()) {
            try (AudioInputStream ais = AudioSystem.getAudioInputStream(file)) {
                return openClip(ais);
            } catch (Exception ignored) {}
        }
        // Fall back to synthetic tone
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
     * @param hz       frequency in Hz
     * @param durationMs duration in milliseconds
     * @param amplitude  peak amplitude (0.0–1.0)
     */
    private static byte[] synth(double hz, int durationMs, double amplitude) {
        int samples = SAMPLE_RATE * durationMs / 1000;
        byte[] buf = new byte[samples * 2];
        for (int i = 0; i < samples; i++) {
            double t = (double) i / SAMPLE_RATE;
            // Fade out over last 20% to avoid click
            double env = i < samples * 0.8 ? 1.0 : (1.0 - (i - samples * 0.8) / (samples * 0.2));
            short sample = (short) (Math.sin(2 * Math.PI * hz * t) * amplitude * env * Short.MAX_VALUE);
            buf[i * 2]     = (byte) (sample & 0xFF);
            buf[i * 2 + 1] = (byte) ((sample >> 8) & 0xFF);
        }
        return buf;
    }

    /** Three-note ascending chord (C4, E4, G4) for game-over fanfare. */
    private static byte[] synthChord() {
        int durationMs = 600;
        int samples = SAMPLE_RATE * durationMs / 1000;
        double[] freqs = { 261.63, 329.63, 392.00 };
        byte[] buf = new byte[samples * 2];
        for (int i = 0; i < samples; i++) {
            double t = (double) i / SAMPLE_RATE;
            double env = i < samples * 0.7 ? 1.0 : (1.0 - (i - samples * 0.7) / (samples * 0.3));
            double val = 0;
            for (double f : freqs) val += Math.sin(2 * Math.PI * f * t);
            short sample = (short) (val / freqs.length * 0.5 * env * Short.MAX_VALUE);
            buf[i * 2]     = (byte) (sample & 0xFF);
            buf[i * 2 + 1] = (byte) ((sample >> 8) & 0xFF);
        }
        return buf;
    }
}
