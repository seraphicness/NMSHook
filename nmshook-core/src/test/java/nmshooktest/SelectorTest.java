package nmshooktest;

import dev.seraphicness.nmshook.api.target.MethodSelector;
import dev.seraphicness.nmshook.resolver.VersionDetector;
import dev.seraphicness.nmshook.transform.PointSpec;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SelectorTest {

    @Test
    void parseForms() {
        assertEquals(new MethodSelector("tick", null), MethodSelector.parse("tick"));
        MethodSelector params = MethodSelector.parse("add(II)");
        assertFalse(params.isFullDescriptor());
        assertTrue(params.matches("add", "(II)I"));
        assertFalse(params.matches("add", "(III)I"));
        MethodSelector full = MethodSelector.parse("add(II)I");
        assertTrue(full.isFullDescriptor());
        assertFalse(full.matches("add", "(II)J"));
        assertEquals("(Ljava/lang/String;)", MethodSelector.parse("x(Ljava.lang.String;)").descriptor());
    }

    @Test
    void ofParameterTypes() throws Exception {
        MethodSelector s = MethodSelector.of("add", int.class, int.class);
        assertEquals(1, s.findDeclared(Target.class).size());
        assertTrue(s.matches(Target.class.getDeclaredMethod("add", int.class, int.class)));
        assertEquals("(II)I", MethodSelector.descriptorOf(Target.class.getDeclaredMethod("add", int.class, int.class)));
    }

    @Test
    void invokeTargets() {
        assertArrayEquals(new String[]{"net/minecraft/world/entity/Entity", "discard", "()V"},
                PointSpec.parseInvokeTarget("Lnet/minecraft/world/entity/Entity;discard()V"));
        assertArrayEquals(new String[]{"a/B", "c", null}, PointSpec.parseInvokeTarget("a.B;c"));
        assertThrows(IllegalArgumentException.class, () -> PointSpec.parseInvokeTarget("noSemicolon"));
        assertThrows(IllegalArgumentException.class, () -> PointSpec.parseInvokeTarget(""));
    }

    @Test
    void supportedVersions() {
        VersionDetector d = new VersionDetector();
        for (String v : new String[]{"26.1", "26.1.2", "26.2", "26.3", "26.3.1"}) assertTrue(d.isSupported(v), v);
        for (String v : new String[]{"26.4", "27.1", "1.21.11", "26", "x", null}) assertFalse(d.isSupported(v), v);
    }
}
