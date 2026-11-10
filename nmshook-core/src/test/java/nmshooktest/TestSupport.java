package nmshooktest;

import net.bytebuddy.agent.ByteBuddyAgent;

import java.lang.instrument.Instrumentation;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

final class TestSupport {

    private static Instrumentation instrumentation;

    static synchronized Instrumentation instrumentation() {
        if (instrumentation == null) instrumentation = ByteBuddyAgent.install();
        return instrumentation;
    }

    /** Logger that records messages for assertions. */
    static final class CapturingLogger {
        final Logger logger;
        final List<LogRecord> records = new ArrayList<>();

        CapturingLogger(String name) {
            logger = Logger.getLogger("nmshooktest." + name + "." + System.nanoTime());
            logger.setUseParentHandlers(false);
            logger.setLevel(Level.ALL);
            logger.addHandler(new Handler() {
                @Override
                public void publish(LogRecord record) {
                    synchronized (records) {
                        records.add(record);
                    }
                }

                @Override
                public void flush() {
                }

                @Override
                public void close() {
                }
            });
        }

        boolean contains(Level level, String fragment) {
            synchronized (records) {
                return records.stream().anyMatch(r -> r.getLevel().equals(level) && r.getMessage().contains(fragment));
            }
        }
    }

    private TestSupport() {
    }
}
