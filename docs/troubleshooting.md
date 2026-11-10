# Troubleshooting

## `NMSHook is not yet initialized. Use after NMSHookPlugin is enabled.`

- Your plugin is enabled before NMSHook: add the dependency with `load: BEFORE`
  ([Getting started](getting-started.md#3-declare-the-dependency-on-nmshook)).
- You shaded `nmshook-api` into your jar. Use `compileOnly` / `provided`.

## `NoClassDefFoundError: dev/seraphicness/nmshook/api/...`

NMSHook is missing on the server, or your `paper-plugin.yml` dependency lacks `join-classpath: true`.

## `HookTargetException: ... does not exist. Did you mean: ...?`

The method name is wrong for this server version. Use the suggested name, or look it up
([Finding targets](targeting.md)). Names are Mojang names on Paper 26.x.

## `... is not declared in X but inherited from Y`

Only declared methods can be hooked. Hook `Y` and filter with `ctx.getInstance() instanceof X`.

## `no overload of X#m matches descriptor ... Available: ...`

Your descriptor or `params(...)` is wrong. Copy one of the listed descriptors.

## `matches N overloads; the hook applies to all of them` (warning)

Not an error, but `ctx.getArg(i)` may mean different things per overload. Add a descriptor.

## `no call to owner.name(desc) in X#m. Calls found: ...`

The INVOKE target does not appear in that method. Check the owner — it must be the type written in the
call instruction, e.g. an interface or superclass ([INVOKE targets](targeting.md#invoke-targets)).

## `ordinal N is out of range; ... has K matching instruction(s)`

Ordinals are 0-based. Use `0 … K-1`, or `-1` for all.

## `handler parameters (...) must match ...` / `handlers must look like 'T handler(T value)'`

The handler signature does not match the target. See the exact signatures in the
[Mixin API](mixin-api.md).

## `Failed to instrument <class>: VerifyError ...`

The JVM rejected the transformed class, usually because a `@ModifyVariable` handler type is not exactly
the variable's type. Nothing was installed. Fix the type, or report it with `/nmshook debug on` output.

## `Hook #N ... disabled after 10 consecutive failures`

The hook kept throwing; the server kept running without it. Fix the cause (first stack trace in the log),
then `/nmshook enable N`.

## `A different NMSHook version (dispatcher ABI ...) is still loaded in this JVM. Restart the server.`

You replaced the NMSHook jar and reloaded. Restart the server instead.

## `WARNING: A Java agent has been loaded dynamically`

Harmless. Start the server with `-javaagent:plugins/NMSHook-<version>.jar`
([Server setup](server-setup.md#recommended-start-nmshook-as-a-java-agent)).
