package dev.seraphicness.nmshook.transform;

import net.bytebuddy.jar.asm.Handle;
import net.bytebuddy.jar.asm.MethodVisitor;
import net.bytebuddy.jar.asm.Opcodes;
import net.bytebuddy.jar.asm.Type;
import net.bytebuddy.utility.OpenedClassReader;

import java.util.ArrayList;
import java.util.List;

/**
 * Rewrites the instructions matched by a {@link PointSpec} inside one method.
 *
 * <p>Every rewrite is an {@code invokedynamic} that is stack-neutral with respect to the original code:
 * <ul>
 *   <li>INVOKE inject: loads the method's parameters (+ {@code this}) and calls {@code (P..., Self)V}</li>
 *   <li>Redirect: replaces {@code invokeX owner.name(A...)R} with {@code indy (Owner?, A..., Self?)R}</li>
 *   <li>ModifyVariable / ModifyReturnValue: value on the stack goes through {@code indy (T, Self?)T}</li>
 * </ul>
 * No branches are inserted, so existing stack map frames stay valid (only max stack grows, which the
 * writer recomputes with {@code COMPUTE_MAXS}).
 *
 * <p>With {@code emit = false} the visitor only counts matches (used for registration-time validation).
 */
final class PointMethodVisitor extends MethodVisitor {

    private static final String DISPATCHER = "dev/seraphicness/nmshook/transform/HookDispatcher";
    private static final String INDY_NAME = "nmshook";

    private static final Handle BOOTSTRAP = new Handle(Opcodes.H_INVOKESTATIC, DISPATCHER, "bootstrap",
            "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;III)"
                    + "Ljava/lang/invoke/CallSite;", false);

    private static final Handle BOOTSTRAP_REDIRECT = new Handle(Opcodes.H_INVOKESTATIC, DISPATCHER,
            "bootstrapRedirect",
            "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;II"
                    + "Ljava/lang/invoke/MethodHandle;)Ljava/lang/invoke/CallSite;", false);

    private final PointSpec spec;
    private final int id;
    private final boolean emit;
    private final boolean isStatic;
    private final String selfDesc;
    private final Type methodType;

    /** Occurrences seen so far (all candidates of the right kind, before ordinal filtering). */
    private int occurrences;
    /** Occurrences that were (or would be) rewritten. */
    private int applied;
    /** INVOKE kinds: every call seen in the method, for diagnostics. */
    private final List<String> seenCalls;

    PointMethodVisitor(MethodVisitor delegate, PointSpec spec, int id, boolean emit,
                       String ownerInternalName, boolean isStatic, String methodDescriptor,
                       List<String> seenCalls) {
        super(OpenedClassReader.ASM_API, delegate);
        this.spec = spec;
        this.id = id;
        this.emit = emit;
        this.isStatic = isStatic;
        this.selfDesc = "L" + ownerInternalName + ";";
        this.methodType = Type.getMethodType(methodDescriptor);
        this.seenCalls = seenCalls;
    }

    int occurrences() {
        return occurrences;
    }

    int applied() {
        return applied;
    }

    private boolean selectOccurrence() {
        int index = occurrences++;
        boolean hit = spec.ordinal() < 0 || spec.ordinal() == index;
        if (hit) applied++;
        return hit && emit;
    }

    private String selfSuffix() {
        return isStatic ? "" : selfDesc;
    }

    private void pushSelf() {
        if (!isStatic) super.visitVarInsn(Opcodes.ALOAD, 0);
    }

    // ------------------------------------------------------------------ INVOKE / REDIRECT

    @Override
    public void visitMethodInsn(int opcode, String owner, String name, String descriptor, boolean isInterface) {
        boolean invokeKind = spec.kind() == HookDispatcher.KIND_INJECT || spec.kind() == HookDispatcher.KIND_REDIRECT;
        if (!invokeKind) {
            super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
            return;
        }
        if (seenCalls != null) seenCalls.add(owner + "." + name + descriptor);

        boolean matches = owner.equals(spec.invokeOwner()) && name.equals(spec.invokeName())
                && (spec.invokeDesc() == null || spec.invokeDesc().equals(descriptor))
                && !name.equals("<init>");
        if (!matches || !selectOccurrence()) {
            super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
            return;
        }

        if (spec.kind() == HookDispatcher.KIND_REDIRECT) {
            emitRedirect(opcode, owner, name, descriptor, isInterface);
        } else if (spec.shiftAfter()) {
            super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
            emitInject();
        } else {
            emitInject();
            super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
        }
    }

    private void emitInject() {
        StringBuilder desc = new StringBuilder("(");
        int slot = isStatic ? 0 : 1;
        for (Type param : methodType.getArgumentTypes()) {
            super.visitVarInsn(param.getOpcode(Opcodes.ILOAD), slot);
            slot += param.getSize();
            desc.append(param.getDescriptor());
        }
        pushSelf();
        desc.append(selfSuffix()).append(")V");
        super.visitInvokeDynamicInsn(INDY_NAME, desc.toString(), BOOTSTRAP,
                id, HookDispatcher.KIND_INJECT, isStatic ? 0 : 1);
    }

    private void emitRedirect(int opcode, String owner, String name, String descriptor, boolean isInterface) {
        Type call = Type.getMethodType(descriptor);
        StringBuilder desc = new StringBuilder("(");
        if (opcode != Opcodes.INVOKESTATIC) desc.append("L").append(owner).append(";");
        for (Type arg : call.getArgumentTypes()) desc.append(arg.getDescriptor());
        pushSelf();
        desc.append(selfSuffix()).append(")").append(call.getReturnType().getDescriptor());

        int tag = switch (opcode) {
            case Opcodes.INVOKESTATIC -> Opcodes.H_INVOKESTATIC;
            case Opcodes.INVOKEINTERFACE -> Opcodes.H_INVOKEINTERFACE;
            case Opcodes.INVOKESPECIAL -> Opcodes.H_INVOKESPECIAL;
            default -> Opcodes.H_INVOKEVIRTUAL;
        };
        Handle original = new Handle(tag, owner, name, descriptor, isInterface);
        super.visitInvokeDynamicInsn(INDY_NAME, desc.toString(), BOOTSTRAP_REDIRECT,
                id, isStatic ? 0 : 1, original);
    }

    // ------------------------------------------------------------------ MODIFY_VARIABLE

    @Override
    public void visitVarInsn(int opcode, int varIndex) {
        if (spec.kind() != HookDispatcher.KIND_MODIFY_VARIABLE || varIndex != spec.localIndex()) {
            super.visitVarInsn(opcode, varIndex);
            return;
        }
        boolean isLoad = opcode >= Opcodes.ILOAD && opcode <= Opcodes.ALOAD;
        boolean isStore = opcode >= Opcodes.ISTORE && opcode <= Opcodes.ASTORE;
        if (isLoad && spec.localLoad()) {
            super.visitVarInsn(opcode, varIndex);
            if (selectOccurrence()) emitValueTransform(valueType(opcode - Opcodes.ILOAD), HookDispatcher.KIND_MODIFY_VARIABLE);
        } else if (isStore && !spec.localLoad()) {
            if (selectOccurrence()) emitValueTransform(valueType(opcode - Opcodes.ISTORE), HookDispatcher.KIND_MODIFY_VARIABLE);
            super.visitVarInsn(opcode, varIndex);
        } else {
            super.visitVarInsn(opcode, varIndex);
        }
    }

    /** @param kindOffset 0=int, 1=long, 2=float, 3=double, 4=reference */
    private String valueType(int kindOffset) {
        return switch (kindOffset) {
            case 0 -> spec.valueDesc() != null && isIntLike(spec.valueDesc()) ? spec.valueDesc() : "I";
            case 1 -> "J";
            case 2 -> "F";
            case 3 -> "D";
            default -> spec.valueDesc() != null ? spec.valueDesc() : "Ljava/lang/Object;";
        };
    }

    private static boolean isIntLike(String desc) {
        return desc.equals("I") || desc.equals("Z") || desc.equals("B") || desc.equals("C") || desc.equals("S");
    }

    // ------------------------------------------------------------------ MODIFY_RETURN

    @Override
    public void visitInsn(int opcode) {
        if (spec.kind() == HookDispatcher.KIND_MODIFY_RETURN
                && opcode >= Opcodes.IRETURN && opcode <= Opcodes.ARETURN
                && selectOccurrence()) {
            emitValueTransform(methodType.getReturnType().getDescriptor(), HookDispatcher.KIND_MODIFY_RETURN);
        }
        super.visitInsn(opcode);
    }

    private void emitValueTransform(String valueDesc, int kind) {
        pushSelf();
        String desc = "(" + valueDesc + selfSuffix() + ")" + valueDesc;
        super.visitInvokeDynamicInsn(INDY_NAME, desc, BOOTSTRAP, id, kind, isStatic ? 0 : 1);
    }
}
