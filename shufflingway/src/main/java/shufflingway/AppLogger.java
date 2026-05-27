package shufflingway;

import scraper.AppPaths;

import java.io.IOException;
import java.nio.file.Path;
import java.util.logging.*;

/** File-based exception logger writing to the app data directory. */
public final class AppLogger {

    private static final Logger LOGGER = Logger.getLogger("shufflingway");
    private static volatile boolean initialized = false;

    private AppLogger() {}

    public static synchronized void init() {
        if (initialized) return;
        initialized = true;
        try {
            Path logFile = AppPaths.appDataDir().resolve("shufflingway.log");
            FileHandler fh = new FileHandler(logFile.toAbsolutePath().toString(), true);
            fh.setFormatter(new SimpleFormatter());
            fh.setLevel(Level.ALL);
            LOGGER.addHandler(fh);
            LOGGER.setUseParentHandlers(false);
            LOGGER.setLevel(Level.ALL);
        } catch (IOException e) {
            System.err.println("AppLogger: could not open log file — " + e.getMessage());
        }
    }

    public static void log(String context, Throwable t) {
        init();
        LOGGER.log(Level.SEVERE, context, t);
    }

    public static void log(Throwable t) {
        log("Exception", t);
    }
}
