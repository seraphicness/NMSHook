package dev.seraphicness.nmshook.transform;

/**
 * Describes an instruction-level injection point inside a target method.
 *
 * @param kind        one of {@code HookDispatcher.KIND_*}
 * @param invokeOwner INVOKE/REDIRECT: internal name of the called method's owner ({@code a/b/C})
 * @param invokeName  INVOKE/REDIRECT: called method name
 * @param invokeDesc  INVOKE/REDIRECT: called method descriptor, or {@code null} for any
 * @param shiftAfter  INVOKE: inject after the call instead of before
 * @param ordinal     occurrence to use (0-based), -1 for all
 * @param localIndex  MODIFY_VARIABLE: local variable slot
 * @param localLoad   MODIFY_VARIABLE: true = on load, false = on store
 * @param valueDesc   MODIFY_VARIABLE: descriptor of the variable type (from the handler)
 * @param cancellable INVOKE: whether the injection may cancel (return early)
 */
public record PointSpec(
        int kind,
        String invokeOwner,
        String invokeName,
        String invokeDesc,
        boolean shiftAfter,
        int ordinal,
        int localIndex,
        boolean localLoad,
        String valueDesc,
        boolean cancellable
) {

    public static PointSpec inject(String owner, String name, String desc, boolean after, int ordinal,
                                   boolean cancellable) {
        return new PointSpec(HookDispatcher.KIND_INJECT, owner, name, desc, after, ordinal, -1, false, null,
                cancellable);
    }

    public static PointSpec redirect(String owner, String name, String desc, int ordinal) {
        return new PointSpec(HookDispatcher.KIND_REDIRECT, owner, name, desc, false, ordinal, -1, false, null,
                false);
    }

    public static PointSpec modifyVariable(int index, boolean load, String valueDesc, int ordinal) {
        return new PointSpec(HookDispatcher.KIND_MODIFY_VARIABLE, null, null, null, false, ordinal, index, load,
                valueDesc, false);
    }

    public static PointSpec modifyReturn(int ordinal) {
        return new PointSpec(HookDispatcher.KIND_MODIFY_RETURN, null, null, null, false, ordinal, -1, false, null,
                false);
    }

    /**
     * Parses a Mixin-style invoke target: {@code "Lowner/Class;name(desc)ret"}, {@code "Lowner/Class;name"},
     * or dotted {@code "owner.Class;name(...)"}.
     *
     * @return {owner, name, descOrNull}
     */
    public static String[] parseInvokeTarget(String target) {
        if (target == null || target.isBlank()) {
            throw new IllegalArgumentException("@At(\"INVOKE\") requires target = \"Lowner/Class;method(desc)ret\"");
        }
        String t = target.trim();
        int semi = t.indexOf(';');
        if (semi < 0) {
            throw new IllegalArgumentException("Invalid invoke target (expected \"Lowner/Class;method(desc)ret\"): " + target);
        }
        String owner = t.substring(0, semi);
        if (owner.startsWith("L")) owner = owner.substring(1);
        owner = owner.replace('.', '/');
        String rest = t.substring(semi + 1);
        int paren = rest.indexOf('(');
        String name = paren < 0 ? rest : rest.substring(0, paren);
        String desc = paren < 0 ? null : rest.substring(paren).replace('.', '/');
        if (owner.isEmpty() || name.isEmpty()) {
            throw new IllegalArgumentException("Invalid invoke target: " + target);
        }
        return new String[]{owner, name, desc};
    }

    public String describe() {
        return switch (kind) {
            case HookDispatcher.KIND_INJECT, HookDispatcher.KIND_REDIRECT ->
                    "INVOKE " + invokeOwner + "." + invokeName + (invokeDesc != null ? invokeDesc : "")
                            + (kind == HookDispatcher.KIND_INJECT ? (shiftAfter ? " (after)" : " (before)") : "")
                            + (ordinal >= 0 ? " #" + ordinal : "");
            case HookDispatcher.KIND_MODIFY_VARIABLE ->
                    (localLoad ? "LOAD" : "STORE") + " slot " + localIndex + (ordinal >= 0 ? " #" + ordinal : "");
            case HookDispatcher.KIND_MODIFY_RETURN -> "RETURN" + (ordinal >= 0 ? " #" + ordinal : "");
            default -> "?";
        };
    }
}
