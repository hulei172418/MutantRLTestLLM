package mujava.rl;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;

public final class EpisodeLogger {
    private final Path logFile;

    public EpisodeLogger(String logPath) {
        this.logFile = Paths.get(logPath).toAbsolutePath().normalize();
    }

    public synchronized void append(EpisodeSummary summary) {
        if (summary == null) {
            return;
        }
        try {
            Files.createDirectories(logFile.getParent());
            Files.write(logFile,
                    (summary.toJson().toString() + System.lineSeparator()).getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to append RL episode log: " + logFile, e);
        }
    }
}
