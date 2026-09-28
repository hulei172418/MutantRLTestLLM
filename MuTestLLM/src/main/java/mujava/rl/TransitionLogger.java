package mujava.rl;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;

public final class TransitionLogger {
    private final Path logFile;

    public TransitionLogger(String logPath) {
        this.logFile = Paths.get(logPath).toAbsolutePath().normalize();
    }

    public synchronized void append(Transition transition) {
        if (transition == null) {
            return;
        }
        try {
            Files.createDirectories(logFile.getParent());
            Files.write(logFile,
                    (transition.toJson().toString() + System.lineSeparator()).getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to append RL transition log: " + logFile, e);
        }
    }
}
