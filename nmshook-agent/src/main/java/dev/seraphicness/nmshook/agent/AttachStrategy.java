package dev.seraphicness.nmshook.agent;

import java.lang.instrument.Instrumentation;

/**
 * Strategy interface for acquiring Instrumentation.
 * Abstracts the javaagent approach and the dynamic attachment (Attach API) approach.
 */
public sealed interface AttachStrategy {

    Instrumentation acquire();

    /** Uses the Instrumentation pre-acquired via the -javaagent JVM option. */
    record PreloadedAgent(Instrumentation instrumentation) implements AttachStrategy {
        @Override
        public Instrumentation acquire() {
            return instrumentation;
        }
    }

    /** Dynamically attaches at runtime via ByteBuddyAgent.install(). Requires a JDK. */
    record DynamicAttach() implements AttachStrategy {
        @Override
        public Instrumentation acquire() {
            // ByteBuddyAgent.install() — fallback mechanism; will fail on a JRE
            return net.bytebuddy.agent.ByteBuddyAgent.install();
        }
    }
}
