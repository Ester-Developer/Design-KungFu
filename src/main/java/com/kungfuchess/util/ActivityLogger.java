package com.kungfuchess.util;

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;

/**
 * Mirrors {@code System.out}/{@code System.err} into a persistent log file under
 * {@code logs/}, in addition to the console — satisfies the assignment's "store logs
 * on both server and client side, for all client/server activity" requirement without
 * touching every individual log call site.
 */
public final class ActivityLogger {

    private ActivityLogger() {
    }

    /** Call once at process start. {@code name} becomes {@code logs/<name>.log} (appended, not overwritten). */
    public static void install(String name) {
        try {
            Path logsDir = Path.of("logs");
            Files.createDirectories(logsDir);
            Path logFile = logsDir.resolve(name + ".log");
            PrintStream fileOut = new PrintStream(new FileOutputStream(logFile.toFile(), true), true, StandardCharsets.UTF_8);

            System.setOut(new TeePrintStream(System.out, fileOut));
            System.setErr(new TeePrintStream(System.err, fileOut));
            System.out.println("=== log session started " + LocalDateTime.now() + " ===");
        } catch (IOException e) {
            System.err.println("[ActivityLogger] Failed to set up file logging: " + e.getMessage());
        }
    }

    /** Writes every byte to both the original stream and the log file. */
    private static final class TeePrintStream extends PrintStream {
        private final PrintStream also;

        TeePrintStream(PrintStream original, PrintStream also) {
            super(original, true);
            this.also = also;
        }

        @Override
        public void write(byte[] buf, int off, int len) {
            super.write(buf, off, len);
            also.write(buf, off, len);
            also.flush();
        }

        @Override
        public void write(int b) {
            super.write(b);
            also.write(b);
        }
    }
}
