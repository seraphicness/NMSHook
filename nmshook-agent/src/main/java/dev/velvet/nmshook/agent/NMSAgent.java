package dev.velvet.nmshook.agent;

import java.lang.instrument.Instrumentation;
import java.util.logging.Logger;

/**
 * Core class of the Agent domain that manages the JVM Instrumentation lifecycle.
 * Supports both the javaagent premain entry point and runtime dynamic attachment.
 */
public final class NMSAgent {

    private static final Logger LOGGER = Logger.getLogger(NMSAgent.class.getName());

    /** Instrumentation obtained via javaagent premain. */
    private static volatile Instrumentation premainInstrumentation;

    private final Instrumentation instrumentation;

    /** javaagent premain entry point. Automatically invoked by the JVM. */
    public static void premain(String args, Instrumentation inst) {
        premainInstrumentation = inst;
        LOGGER.info("NMSAgent premain loaded successfully");
    }

    /** agentmain entry point (for dynamic attachment). */
    public static void agentmain(String args, Instrumentation inst) {
        premainInstrumentation = inst;
        LOGGER.info("NMSAgent agentmain loaded successfully");
    }

    public NMSAgent(AttachStrategy strategy) {
        if (premainInstrumentation != null) {
            this.instrumentation = premainInstrumentation;
            LOGGER.info("Using Instrumentation obtained via premain");
        } else {
            this.instrumentation = strategy.acquire();
            LOGGER.info("Instrumentation obtained via dynamic attachment");
        }
    }

    public Instrumentation getInstrumentation() {
        return instrumentation;
    }

    /** Retransforms the specified class. Used to apply hooks to already-loaded classes. */
    public void retransform(Class<?> clazz) throws Exception {
        if (!instrumentation.isRetransformClassesSupported()) {
            throw new UnsupportedOperationException("This JVM does not support class retransformation.");
        }
        instrumentation.retransformClasses(clazz);
    }
}
