package dev.velvet.nmshook.agent;

import java.lang.instrument.Instrumentation;
import java.lang.reflect.Method;
import java.util.logging.Logger;

/**
 * Core class of the Agent domain that manages the JVM Instrumentation lifecycle.
 * Supports both the javaagent premain entry point and runtime dynamic attachment.
 *
 * <p>Acquisition order (first hit wins):
 * <ol>
 *   <li>{@code -javaagent} premain — no JVM warning at all</li>
 *   <li>An agent already attached earlier in this JVM (e.g. after a plugin reload) — no new attach, no new warning</li>
 *   <li>Dynamic self-attach (fallback) — the JVM prints the "agent loaded dynamically" warning</li>
 * </ol>
 */
public final class NMSAgent {

    private static final Logger LOGGER = Logger.getLogger(NMSAgent.class.getName());

    /** Instrumentation obtained via javaagent premain / agentmain. */
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

    /**
     * Returns the Instrumentation stored by premain/agentmain in <b>this</b> class's loader.
     * Public so the plugin-classloader copy of this class can read the system-classloader copy reflectively.
     */
    public static Instrumentation getPreloadedInstrumentation() {
        return premainInstrumentation;
    }

    public NMSAgent(AttachStrategy fallback) {
        Instrumentation inst = findPreloaded();
        if (inst != null) {
            LOGGER.info("Using Instrumentation obtained via -javaagent (no dynamic attach)");
        } else if ((inst = findAlreadyAttached()) != null) {
            LOGGER.info("Reusing Instrumentation attached earlier in this JVM (no re-attach)");
        } else {
            LOGGER.warning("NMSHook is not loaded as -javaagent; falling back to dynamic attach. "
                    + "The JVM will print a 'Java agent has been loaded dynamically' warning. "
                    + "To remove it, start the server with -javaagent:plugins/<NMSHook jar>.");
            inst = fallback.acquire();
        }
        this.instrumentation = inst;
    }

    /**
     * With {@code -javaagent}, premain runs in the <i>system</i> classloader, while the plugin
     * is loaded by a separate plugin classloader (Paperclip does not delegate to the system CL),
     * so the plugin sees a different copy of this class whose static field is null.
     * Look up the system-CL copy explicitly.
     */
    private static Instrumentation findPreloaded() {
        if (premainInstrumentation != null) return premainInstrumentation;
        try {
            Class<?> systemCopy = Class.forName(NMSAgent.class.getName(), false,
                    ClassLoader.getSystemClassLoader());
            if (systemCopy == NMSAgent.class) return null;
            Method getter = systemCopy.getMethod("getPreloadedInstrumentation");
            return (Instrumentation) getter.invoke(null);
        } catch (ClassNotFoundException e) {
            return null; // not started with -javaagent
        } catch (ReflectiveOperationException | ClassCastException e) {
            LOGGER.fine(() -> "Could not read premain Instrumentation: " + e);
            return null;
        }
    }

    /** ByteBuddy caches its attached agent in the system classloader; reuse it instead of attaching again. */
    private static Instrumentation findAlreadyAttached() {
        try {
            return net.bytebuddy.agent.ByteBuddyAgent.getInstrumentation();
        } catch (IllegalStateException e) {
            return null;
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
