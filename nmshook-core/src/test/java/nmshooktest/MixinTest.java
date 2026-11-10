package nmshooktest;

import dev.seraphicness.nmshook.api.HookHandle;
import dev.seraphicness.nmshook.api.NMSHookLib;
import dev.seraphicness.nmshook.api.annotation.At;
import dev.seraphicness.nmshook.api.annotation.Inject;
import dev.seraphicness.nmshook.api.annotation.ModifyArg;
import dev.seraphicness.nmshook.api.annotation.ModifyReturnValue;
import dev.seraphicness.nmshook.api.annotation.ModifyVariable;
import dev.seraphicness.nmshook.api.annotation.NMSMixin;
import dev.seraphicness.nmshook.api.annotation.Overwrite;
import dev.seraphicness.nmshook.api.annotation.Redirect;
import dev.seraphicness.nmshook.api.annotation.Shadow;
import dev.seraphicness.nmshook.api.callback.CallbackInfo;
import dev.seraphicness.nmshook.api.callback.CallbackInfoReturnable;
import dev.seraphicness.nmshook.api.hook.HookPoint;
import dev.seraphicness.nmshook.api.target.HookTargetException;
import dev.seraphicness.nmshook.core.DefaultNMSHookEngine;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;

import static org.junit.jupiter.api.Assertions.*;

class MixinTest {

    private TestSupport.CapturingLogger log;
    private DefaultNMSHookEngine engine;
    static final List<String> EVENTS = new ArrayList<>();

    @BeforeEach
    void setUp() {
        log = new TestSupport.CapturingLogger("mixin");
        engine = new DefaultNMSHookEngine(TestSupport.instrumentation(), log.logger);
        NMSHookLib.bootstrap(engine);
        EVENTS.clear();
    }

    @AfterEach
    void tearDown() {
        engine.shutdown();
        NMSHookLib.bootstrap(null);
    }

    // ------------------------------------------------------------------ HEAD / TAIL / Overwrite / ModifyArg

    @NMSMixin(target = Target.class)
    public static class HeadTailMixin {
        // parameters declared -> overload add(int,int) is inferred
        @Inject(method = "add", at = @At("HEAD"), cancellable = true)
        private void onAdd(int a, int b, CallbackInfoReturnable<Integer> cir) {
            if (a < 0) {
                cir.setReturnValue(-999);
                cir.cancel();
            }
        }

        @Inject(method = "greet", at = @At("TAIL"))
        private void onGreet(String name, CallbackInfoReturnable<String> cir) {
            cir.setReturnValue(cir.getReturnValue() + "!");
        }
    }

    @Test
    void injectHeadAndTail() {
        NMSHookLib.registerMixin(HeadTailMixin.class);
        Target t = new Target();
        assertEquals(-999, t.add(-1, 5));
        assertEquals(4, t.add(1, 3));
        assertEquals(-1 + 5 + 1, t.add(-1, 5, 1), "inferred descriptor must leave add(III) alone");
        assertEquals("hello bob!", t.greet("bob"));
    }

    @NMSMixin(target = Target.class)
    public static class OverwriteMixin {
        @Overwrite
        public String greet(String name) {
            return "bye " + name;
        }

        @ModifyArg(method = "add(II)I", index = 1)
        private int doubleSecond(int b) {
            return b * 2;
        }
    }

    @Test
    void overwriteAndModifyArg() {
        NMSHookLib.registerMixin(OverwriteMixin.class);
        Target t = new Target();
        assertEquals("bye x", t.greet("x"));
        assertEquals(1 + 6, t.add(1, 3));
    }

    // ------------------------------------------------------------------ @Shadow

    @NMSMixin(target = Target.class)
    public abstract static class ShadowMixin {
        @Shadow
        private int secret;

        @Shadow
        abstract int helper(int v);

        @Inject(method = "readSecret", at = @At("HEAD"))
        private void onRead(CallbackInfo ci) {
            EVENTS.add("secret=" + secret + ",helper=" + helper(1));
            secret = 42; // written back to the target instance
        }
    }

    @Test
    void shadowFieldsAndMethods() {
        NMSHookLib.registerMixin(ShadowMixin.class);
        Target t = new Target();
        assertEquals(42, t.readSecret());
        assertEquals(List.of("secret=7,helper=11"), EVENTS);
    }

    // ------------------------------------------------------------------ INVOKE inject

    @NMSMixin(target = Target.class)
    public static class InvokeMixin {
        @Inject(method = "compute", at = @At(value = "INVOKE", target = "Lnmshooktest/Target;helper(I)I"))
        private void beforeHelper(int x, CallbackInfo ci) {
            EVENTS.add("before helper x=" + x);
        }

        @Inject(method = "compute", at = @At(value = "INVOKE", target = "Lnmshooktest/Target;helper(I)I",
                shift = At.Shift.AFTER))
        private void afterHelper(CallbackInfo ci) {
            EVENTS.add("after helper");
        }
    }

    @Test
    void injectAtInvoke() {
        NMSHookLib.registerMixin(InvokeMixin.class);
        assertEquals(13, new Target().compute(1));
        assertEquals(List.of("before helper x=1", "after helper"), EVENTS);
    }

    @NMSMixin(target = Target.class)
    public static class CancelAtInvokeMixin {
        @Inject(method = "compute", at = @At(value = "INVOKE", target = "Lnmshooktest/Target;helper(I)I"),
                cancellable = true)
        private void stop(int x, CallbackInfoReturnable<Integer> cir) {
            if (x > 100) {
                cir.setReturnValue(-1);
                cir.cancel();
            }
        }
    }

    @Test
    void cancellableInjectAtInvokeReturnsEarly() {
        NMSHookLib.registerMixin(CancelAtInvokeMixin.class);
        Target t = new Target();
        assertEquals(-1, t.compute(500));
        assertEquals(13, t.compute(1));
    }

    // ------------------------------------------------------------------ Redirect

    @NMSMixin(target = Target.class)
    public static class RedirectMixin {
        @Redirect(method = "compute", at = @At(value = "INVOKE", target = "Lnmshooktest/Target;helper(I)I"))
        private int redirectHelper(Target self, int v) {
            return 1000;
        }

        @Redirect(method = "sizeOf", at = @At(value = "INVOKE", target = "Ljava/util/List;size()I"))
        private int fakeSize(List<?> list) {
            return list.size() + 100;
        }

        @Redirect(method = "maxOf", at = @At(value = "INVOKE", target = "Ljava/lang/Math;max(II)I"))
        private static int useMin(int a, int b) {
            return Math.min(a, b);
        }

        @Redirect(method = "chain", at = @At(value = "INVOKE", target = "Ljava/lang/String;toUpperCase()Ljava/lang/String;"))
        private String lower(String s) {
            return s.toLowerCase() + "?";
        }
    }

    @Test
    void redirectVirtualInterfaceStaticAndObjectCalls() {
        NMSHookLib.registerMixin(RedirectMixin.class);
        Target t = new Target();
        assertEquals(1001, t.compute(1));
        assertEquals(102, t.sizeOf(List.of(1, 2)));
        assertEquals(2, t.maxOf(2, 9));
        assertEquals("abc?", t.chain("  ABC "));
    }

    @NMSMixin(target = Target.class)
    public static class FailingRedirectMixin {
        @Redirect(method = "maxOf", at = @At(value = "INVOKE", target = "Ljava/lang/Math;max(II)I"))
        private static int broken(int a, int b) {
            throw new IllegalStateException("broken redirect");
        }
    }

    @Test
    void failingRedirectFallsBackToOriginalCall() {
        NMSHookLib.registerMixin(FailingRedirectMixin.class);
        assertEquals(9, new Target().maxOf(2, 9));
        assertTrue(log.contains(Level.WARNING, "threw an exception"));
    }

    // ------------------------------------------------------------------ ModifyVariable / ModifyReturnValue

    @NMSMixin(target = Target.class)
    public static class VariableMixin {
        // compute(int x): slot 0 = this, 1 = x, 2 = y. First store of y is "y = x * 2".
        @ModifyVariable(method = "compute", index = 2, at = @At(value = At.STORE, ordinal = 0))
        private int plusFive(int y) {
            return y + 5;
        }

        // half(double d): slot 1-2 = d, slot 3-4 = h
        @ModifyVariable(method = "half", index = 3, at = @At(At.LOAD))
        private double negate(double h) {
            return -h;
        }

        @ModifyVariable(method = "chain", index = 2, at = @At(At.STORE))
        private String wrap(String t) {
            return "[" + t + "]";
        }
    }

    @Test
    void modifyVariableOnStoreAndLoad() {
        NMSHookLib.registerMixin(VariableMixin.class);
        Target t = new Target();
        assertEquals((1 * 2 + 5) + 10 + 1, t.compute(1));
        assertEquals(-2.5, t.half(5.0));
        assertEquals("[ABC]", t.chain(" abc "));
    }

    @NMSMixin(target = Target.class)
    public static class ReturnMixin {
        @ModifyReturnValue(method = "flag")
        private boolean invert(boolean original) {
            return !original;
        }

        @ModifyReturnValue(method = "square")
        private static long plusOne(long original) {
            return original + 1;
        }
    }

    @Test
    void modifyReturnValue() {
        NMSHookLib.registerMixin(ReturnMixin.class);
        assertFalse(new Target().flag());
        assertEquals(10, Target.square(3));
    }

    // ------------------------------------------------------------------ combinations & lifecycle

    @Test
    void adviceAndPointsOnTheSameMethodCompose() {
        NMSHookLib.registerMixin(RedirectMixin.class);
        NMSHookLib.registerMixin(VariableMixin.class);
        NMSHookLib.hook().target(Target.class).method("compute").at(HookPoint.BEFORE)
                .listener(ctx -> ctx.setArg(0, 2)).register();
        NMSHookLib.hook().target(Target.class).method("compute").at(HookPoint.AFTER)
                .listener(ctx -> ctx.setReturnValue((int) ctx.getReturnValue() * 10)).register();
        // x := 2; y = 2*2 -> +5 = 9; helper redirected -> 1000; return 1001 -> *10
        assertEquals(10010, new Target().compute(1));
    }

    @Test
    void unregisteringMixinRestoresEverything() {
        HookHandle redirect = NMSHookLib.registerMixin(RedirectMixin.class);
        HookHandle cancel = NMSHookLib.registerMixin(CancelAtInvokeMixin.class);
        Target t = new Target();
        assertEquals(-1, t.compute(500));

        cancel.unregister();
        assertEquals(1001, t.compute(500));
        redirect.unregister();
        assertEquals(1011, t.compute(500));
        assertEquals(0, engine.instrumentedClassCount());
    }

    @Test
    void engineShutdownRestoresClasses() {
        NMSHookLib.registerMixin(RedirectMixin.class);
        assertEquals(1001, new Target().compute(1));
        engine.shutdown();
        assertEquals(13, new Target().compute(1));
    }

    // ------------------------------------------------------------------ validation

    @NMSMixin(target = Target.class)
    public static class MissingCallMixin {
        @Redirect(method = "compute", at = @At(value = "INVOKE", target = "Lnmshooktest/Target;helpr(I)I"))
        private int r(Target self, int v) {
            return 0;
        }
    }

    @Test
    void missingInvokeTargetListsCallsInMethod() {
        var e = assertThrows(HookTargetException.class, () -> NMSHookLib.registerMixin(MissingCallMixin.class));
        assertTrue(e.getMessage().contains("nmshooktest/Target.helper(I)I"), e.getMessage());
        assertEquals(0, engine.instrumentedClassCount());
    }

    @NMSMixin(target = Target.class)
    public static class OrdinalMixin {
        @ModifyVariable(method = "compute", index = 2, at = @At(value = At.STORE, ordinal = 5))
        private int r(int y) {
            return y;
        }
    }

    @Test
    void ordinalOutOfRange() {
        var e = assertThrows(HookTargetException.class, () -> NMSHookLib.registerMixin(OrdinalMixin.class));
        assertTrue(e.getMessage().contains("has 2 matching instruction(s)"), e.getMessage());
    }

    @NMSMixin(target = Target.class)
    public static class BadSignatureMixin {
        @Inject(method = "add(II)I", at = @At("HEAD"))
        private void wrong(long a, CallbackInfo ci) {
        }
    }

    @Test
    void handlerParametersMustMatchTarget() {
        var e = assertThrows(HookTargetException.class, () -> NMSHookLib.registerMixin(BadSignatureMixin.class));
        assertTrue(e.getMessage().contains("must match"), e.getMessage());
    }

    @NMSMixin(target = Target.class)
    public static class PartiallyBrokenMixin {
        @Inject(method = "greet", at = @At("TAIL"))
        private void fine(CallbackInfoReturnable<String> cir) {
            cir.setReturnValue("changed");
        }

        @Inject(method = "doesNotExist", at = @At("HEAD"))
        private void broken(CallbackInfo ci) {
        }
    }

    @Test
    void failedMixinRegistrationLeavesNothingBehind() {
        assertThrows(HookTargetException.class, () -> NMSHookLib.registerMixin(PartiallyBrokenMixin.class));
        assertEquals("hello x", new Target().greet("x"));
        assertTrue(engine.hooks().isEmpty());
        assertEquals(0, engine.instrumentedClassCount());
    }

    @NMSMixin(target = Target.class)
    public static class OverwriteA {
        @Overwrite
        public String greet(String name) {
            return "A";
        }
    }

    @NMSMixin(target = Target.class, priority = 2000)
    public static class OverwriteB {
        @Overwrite
        public String greet(String name) {
            return "B";
        }
    }

    @Test
    void conflictingOverwritesAreReported() {
        NMSHookLib.registerMixin(OverwriteA.class);
        NMSHookLib.registerMixin(OverwriteB.class);
        assertEquals("A", new Target().greet("x"), "lower priority value wins");
    }
}
