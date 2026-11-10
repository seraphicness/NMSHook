package nmshooktest;

import dev.seraphicness.nmshook.api.HookHandle;
import dev.seraphicness.nmshook.api.NMSHookDebug;
import dev.seraphicness.nmshook.api.NMSHookLib;
import dev.seraphicness.nmshook.api.hook.HookPoint;
import dev.seraphicness.nmshook.api.target.HookTargetException;
import dev.seraphicness.nmshook.core.DefaultNMSHookEngine;
import dev.seraphicness.nmshook.hook.HookDefinition;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;

import static org.junit.jupiter.api.Assertions.*;

class LambdaHookTest {

    private TestSupport.CapturingLogger log;
    private DefaultNMSHookEngine engine;

    @BeforeEach
    void setUp() {
        log = new TestSupport.CapturingLogger("lambda");
        engine = new DefaultNMSHookEngine(TestSupport.instrumentation(), log.logger);
        NMSHookLib.bootstrap(engine);
    }

    @AfterEach
    void tearDown() {
        engine.shutdown();
        NMSHookLib.bootstrap(null);
    }

    @Test
    void beforeHookModifiesArgumentsOfSelectedOverloadOnly() {
        NMSHookLib.hook().target(Target.class).method("add").params(int.class, int.class)
                .at(HookPoint.BEFORE).listener(ctx -> ctx.setArg(0, 100)).register();

        Target t = new Target();
        assertEquals(102, t.add(1, 2));
        assertEquals(6, t.add(1, 2, 3), "other overload must not be hooked");
    }

    @Test
    void descriptorInMethodString() {
        NMSHookLib.hook().target(Target.class).method("add(III)I")
                .at(HookPoint.AFTER).listener(ctx -> ctx.setReturnValue(-1)).register();
        Target t = new Target();
        assertEquals(-1, t.add(1, 2, 3));
        assertEquals(3, t.add(1, 2));
    }

    @Test
    void cancellableBeforeSkipsOriginalWithReturnValue() {
        NMSHookLib.hook().target(Target.class).method("greet").at(HookPoint.BEFORE).cancellable(true)
                .listener(ctx -> {
                    ctx.setReturnValue("cancelled");
                    ctx.cancel();
                }).register();
        assertEquals("cancelled", new Target().greet("x"));
    }

    @Test
    void nonCancellableHookCannotCancel() {
        NMSHookLib.hook().target(Target.class).method("greet").at(HookPoint.BEFORE).cancellable(false)
                .listener(ctx -> ctx.cancel()).register();
        assertEquals("hello x", new Target().greet("x"));
    }

    @Test
    void cancelFromNonCancellableHookDoesNotLeakIntoNextHook() {
        NMSHookLib.hook().target(Target.class).method("greet").at(HookPoint.BEFORE).priority(1).cancellable(false)
                .listener(ctx -> ctx.cancel()).register();
        NMSHookLib.hook().target(Target.class).method("greet").at(HookPoint.BEFORE).priority(2).cancellable(true)
                .listener(ctx -> { }).register();
        assertEquals("hello x", new Target().greet("x"));
    }

    @Test
    void concurrentCallsDuringRegistrationChurn() throws Exception {
        Target t = new Target();
        java.util.concurrent.atomic.AtomicReference<Throwable> failure = new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicBoolean running = new java.util.concurrent.atomic.AtomicBoolean(true);
        Thread[] callers = new Thread[4];
        for (int i = 0; i < callers.length; i++) {
            callers[i] = new Thread(() -> {
                while (running.get()) {
                    try {
                        String r = t.greet("x");
                        if (!r.equals("hello x") && !r.equals("HOOKED")) throw new AssertionError("unexpected " + r);
                        int sum = t.add(1, 2);
                        if (sum != 3 && sum != 30) throw new AssertionError("unexpected " + sum);
                    } catch (Throwable e) {
                        failure.compareAndSet(null, e);
                    }
                }
            });
            callers[i].start();
        }
        try {
            for (int i = 0; i < 40; i++) {
                HookHandle a = NMSHookLib.hook().target(Target.class).method("greet").at(HookPoint.AFTER)
                        .listener(ctx -> ctx.setReturnValue("HOOKED")).register();
                HookHandle b = NMSHookLib.hook().target(Target.class).method("add").params(int.class, int.class)
                        .at(HookPoint.AFTER).listener(ctx -> ctx.setReturnValue(30)).register();
                a.unregister();
                b.unregister();
            }
        } finally {
            running.set(false);
            for (Thread c : callers) c.join();
        }
        if (failure.get() != null) throw new AssertionError(failure.get());
        assertEquals(0, engine.instrumentedClassCount());
    }

    @Test
    void cancelOnPrimitiveWithoutReturnValueUsesDefault() {
        NMSHookLib.hook().target(Target.class).method("add").params(int.class, int.class)
                .at(HookPoint.BEFORE).cancellable(true).listener(ctx -> ctx.cancel()).register();
        assertEquals(0, new Target().add(1, 2));
    }

    @Test
    void afterHookChangesReturnValue() {
        NMSHookLib.hook().target(Target.class).method("greet").at(HookPoint.AFTER)
                .listener(ctx -> ctx.setReturnValue(((String) ctx.getReturnValue()).toUpperCase())).register();
        assertEquals("HELLO BOB", new Target().greet("bob"));
    }

    @Test
    void replaceHook() {
        NMSHookLib.hook().target(Target.class).method("add").params(int.class, int.class)
                .at(HookPoint.REPLACE).listener(ctx -> ctx.setReturnValue((int) ctx.getArg(0) * (int) ctx.getArg(1)))
                .register();
        assertEquals(12, new Target().add(3, 4));
    }

    @Test
    void staticAndVoidMethods() {
        NMSHookLib.hook().target(Target.class).method("square").at(HookPoint.AFTER)
                .listener(ctx -> {
                    assertNull(ctx.getInstance());
                    ctx.setReturnValue((long) ctx.getReturnValue() + 1);
                }).register();
        NMSHookLib.hook().target(Target.class).method("touch").at(HookPoint.BEFORE).cancellable(true)
                .listener(ctx -> ctx.cancel()).register();

        assertEquals(26L, Target.square(5));
        Target t = new Target();
        t.touch();
        assertEquals(0, t.counter);
    }

    @Test
    void priorityOrder() {
        StringBuilder order = new StringBuilder();
        NMSHookLib.hook().target(Target.class).method("touch").at(HookPoint.BEFORE).priority(500)
                .listener(ctx -> order.append("b")).register();
        NMSHookLib.hook().target(Target.class).method("touch").at(HookPoint.BEFORE).priority(100)
                .listener(ctx -> order.append("a")).register();
        NMSHookLib.hook().target(Target.class).method("touch").at(HookPoint.BEFORE).priority(900)
                .listener(ctx -> order.append("c")).register();
        new Target().touch();
        assertEquals("abc", order.toString());
    }

    @Test
    void unregisterRestoresOriginalBytecode() {
        HookHandle h1 = NMSHookLib.hook().target(Target.class).method("greet").at(HookPoint.AFTER)
                .listener(ctx -> ctx.setReturnValue("a")).register();
        HookHandle h2 = NMSHookLib.hook().target(Target.class).method("greet").at(HookPoint.AFTER).priority(2000)
                .listener(ctx -> ctx.setReturnValue(ctx.getReturnValue() + "b")).register();
        assertEquals("ab", new Target().greet("x"));
        assertEquals(1, engine.instrumentedClassCount());

        h1.unregister();
        assertFalse(h1.isRegistered());
        assertEquals("hello xb", new Target().greet("x"));
        assertEquals(1, engine.instrumentedClassCount());

        h2.unregister();
        assertEquals("hello x", new Target().greet("x"));
        assertEquals(0, engine.instrumentedClassCount(), "class must be restored once no hooks remain");
    }

    @Test
    void unregisterByClassLoaderRemovesOwnerlessHooks() {
        NMSHookLib.hook().target(Target.class).method("greet").at(HookPoint.AFTER)
                .listener(ctx -> ctx.setReturnValue("hooked")).register();
        assertEquals("hooked", new Target().greet("x"));
        int removed = engine.unregisterByClassLoader(LambdaHookTest.class.getClassLoader());
        assertEquals(1, removed);
        assertEquals("hello x", new Target().greet("x"));
    }

    @Test
    void unregisterAllByOwner() {
        Object owner = new Object();
        NMSHookLib.hook().owner(owner).target(Target.class).method("greet").at(HookPoint.AFTER)
                .listener(ctx -> ctx.setReturnValue("hooked")).register();
        NMSHookLib.unregisterAll(owner);
        assertEquals("hello x", new Target().greet("x"));
    }

    @Test
    void circuitBreakerDisablesFailingHook() {
        engine.executor().setFailureThreshold(3);
        AtomicInteger calls = new AtomicInteger();
        HookHandle handle = NMSHookLib.hook().target(Target.class).method("greet").at(HookPoint.BEFORE)
                .listener(ctx -> {
                    calls.incrementAndGet();
                    throw new IllegalStateException("boom");
                }).register();
        Target t = new Target();
        for (int i = 0; i < 10; i++) assertEquals("hello x", t.greet("x"), "failing hook must not break the method");

        assertEquals(3, calls.get(), "hook must stop running after 3 consecutive failures");
        HookDefinition def = engine.hook(handle.id()).orElseThrow();
        assertFalse(def.isEnabled());
        assertTrue(log.contains(Level.WARNING, "threw an exception"));
        assertTrue(log.contains(Level.SEVERE, "disabled after 3 consecutive failures"));

        assertTrue(engine.setEnabled(handle.id(), true));
        t.greet("x");
        assertEquals(4, calls.get());
    }

    @Test
    void wrongReturnTypeIsRejectedInsteadOfCrashing() {
        NMSHookLib.hook().target(Target.class).method("add").params(int.class, int.class).at(HookPoint.AFTER)
                .listener(ctx -> ctx.setReturnValue("not an int")).register();
        assertEquals(3, new Target().add(1, 2));
        assertTrue(log.contains(Level.WARNING, "wrong type"));
    }

    @Test
    void setArgWithWrongTypeCountsAsFailure() {
        NMSHookLib.hook().target(Target.class).method("add").params(int.class, int.class).at(HookPoint.BEFORE)
                .listener(ctx -> ctx.setArg(0, "x")).register();
        assertEquals(3, new Target().add(1, 2));
        assertTrue(log.contains(Level.WARNING, "threw an exception"));
    }

    @Test
    void statisticsAreRecorded() {
        NMSHookDebug.setProfiling(true);
        try {
            HookHandle h = NMSHookLib.hook().target(Target.class).method("touch").at(HookPoint.BEFORE)
                    .listener(ctx -> { }).register();
            Target t = new Target();
            for (int i = 0; i < 5; i++) t.touch();
            HookDefinition def = engine.hook(h.id()).orElseThrow();
            assertEquals(5, def.getCalls());
            assertTrue(def.getTotalNanos() > 0);
        } finally {
            NMSHookDebug.setProfiling(false);
        }
    }

    // ------------------------------------------------------------------ validation

    @Test
    void missingMethodSuggestsCloseNames() {
        var e = assertThrows(HookTargetException.class, () -> NMSHookLib.hook().target(Target.class)
                .method("gret").at(HookPoint.BEFORE).listener(ctx -> { }).register());
        assertTrue(e.getMessage().contains("Did you mean: greet"), e.getMessage());
        assertEquals(0, engine.instrumentedClassCount());
    }

    @Test
    void inheritedMethodPointsToDeclaringClass() {
        var e = assertThrows(HookTargetException.class, () -> NMSHookLib.hook().target(Target.class)
                .method("inherited").at(HookPoint.BEFORE).listener(ctx -> { }).register());
        assertTrue(e.getMessage().contains("inherited from nmshooktest.TargetBase"), e.getMessage());
    }

    @Test
    void wrongDescriptorListsOverloads() {
        var e = assertThrows(HookTargetException.class, () -> NMSHookLib.hook().target(Target.class)
                .method("add(J)J").at(HookPoint.BEFORE).listener(ctx -> { }).register());
        assertTrue(e.getMessage().contains("add(II)I"), e.getMessage());
        assertTrue(e.getMessage().contains("add(III)I"), e.getMessage());
    }

    @Test
    void ambiguousNameWarns() {
        NMSHookLib.hook().target(Target.class).method("add").at(HookPoint.BEFORE).listener(ctx -> { }).register();
        assertTrue(log.contains(Level.WARNING, "matches 2 overloads"));
    }
}
