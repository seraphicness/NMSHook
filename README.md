# NMSHook

**Runtime NMS hook library for Paper plugins.**  
Intercept, modify, and replace NMS method behavior using annotations or a fluent lambda API — no server JAR modifications required.

Built on [ByteBuddy](https://bytebuddy.net/) + [paperweight-userdev](https://github.com/PaperMC/paperweight).

## Features

- **Type-safe NMS access** — Reference NMS classes as `Class<?>` directly. No string-based reflection.
- **Non-invasive** — Plugin-only installation. No server core patches.
- **Annotation API** — `@Inject` (HEAD / TAIL / INVOKE), `@Overwrite`, `@ModifyArg`, `@Redirect`,
  `@ModifyVariable`, `@ModifyReturnValue`, `@Shadow` (fields and methods).
- **Lambda API** — Fluent builder for quick, inline hooks; returns a `HookHandle` to remove it again.
- **Overload selection** — Pick one overload with a JVM descriptor or parameter types.
- **Fail-fast validation** — Wrong names, descriptors, missing calls or bad handler signatures are rejected
  at registration with a message that lists close candidates.
- **Automatic cleanup** — Hooks of a plugin are removed when it is disabled (even without `owner(...)`),
  and methods left without hooks get their original bytecode back.
- **Isolation** — A throwing hook never breaks the NMS method; after N consecutive failures it is disabled.
- **Low overhead** — Inactive methods cost one flag check; handlers are reached through constant call sites.
- **Diagnostics** — `/nmshook` lists hooks with call counts, timings and failures; enable/disable at runtime;
  bytecode dump in debug mode.
- **Folia compatible** — Per-thread Mixin instances, no global mutable state on the hot path.

## Requirements

- Java 25+
- Paper 26.1 – 26.3
- ByteBuddy 1.18+ (bundled)

## Quick Start

### Lambda API

```java
HookHandle handle = NMSHookLib.hook()
    .owner(this)
    .target(ServerPlayer.class)
    .method("hurtServer")
    .params(ServerLevel.class, DamageSource.class, float.class)   // optional: one overload only
    .at(HookPoint.BEFORE)
    .cancellable(true)
    .listener(ctx -> {
        float damage = ctx.getArg(2);
        if (damage > 20f) ctx.cancel();
    })
    .register();

handle.unregister(); // optional — done automatically when your plugin is disabled
```

The overload can also be given as a descriptor: `.method("hurtServer(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/damagesource/DamageSource;F)Z")`.

### Annotation API

```java
@NMSMixin(target = ServerPlayer.class)
public abstract class ServerPlayerHook {

    @Shadow
    private float lastSentHealth;

    @Shadow
    abstract boolean isSpectator();          // forwarded to the real ServerPlayer method

    // Declaring the target's parameters selects that overload automatically
    @Inject(method = "hurtServer", at = @At("HEAD"), cancellable = true)
    private void onHurt(ServerLevel level, DamageSource source, float amount, CallbackInfoReturnable<Boolean> cir) {
        if (lastSentHealth <= 5f || isSpectator()) {
            cir.setReturnValue(false);
            cir.cancel();
        }
    }

    @Inject(method = "die", at = @At("TAIL"))
    private void onDie(DamageSource source, CallbackInfo ci) {
        // runs after the original method
    }

    @ModifyArg(method = "hurtServer", index = 2)
    private float onDamage(float damage) {
        return damage * 0.5f;
    }

    @Overwrite
    public void attack(Entity target) {
        // completely replaces the original method
    }
}
```

Register in your plugin:

```java
@Override
public void onEnable() {
    NMSHookLib.registerMixin(ServerPlayerHook.class, this);
}
// no onDisable cleanup needed: NMSHook removes your hooks when your plugin is disabled
```

### Instruction-level hooks

```java
@NMSMixin(target = ServerPlayer.class)
public class TickHooks {

    // Before (or after, shift = AFTER) a call inside the method. Cancelling returns from tick() early.
    @Inject(method = "tick", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/level/ServerPlayer;doTick()V"), cancellable = true)
    private void beforeDoTick(CallbackInfo ci) { }

    // Replace a call: (receiver, call args...) -> call return type
    @Redirect(method = "tick", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/level/ServerPlayer;doTick()V"))
    private void wrapDoTick(ServerPlayer self) {
        self.doTick();
    }

    // Change a local variable when it is stored (or loaded, @At("LOAD")); index = JVM local slot
    @ModifyVariable(method = "someMethod", index = 3, at = @At(value = "STORE", ordinal = 0))
    private int clamp(int value) {
        return Math.min(value, 20);
    }

    // Change what the method returns
    @ModifyReturnValue(method = "isInvulnerableTo")
    private boolean neverInvulnerable(boolean original) {
        return false;
    }
}
```

These rewrite single instructions with `invokedynamic`, so they compose with each other and with
HEAD/TAIL hooks on the same method. If a `@Redirect` handler throws, the original call runs instead.

## Annotations

| Annotation | Description |
|---|---|
| `@NMSMixin(target, priority)` | Declares a hook class targeting an NMS class |
| `@Inject(method, at)` | Runs code at `HEAD`, `TAIL`/`RETURN`, or at an `INVOKE` inside the method |
| `@Overwrite(method?)` | Replaces the target method (overload = handler signature) |
| `@ModifyArg(method, index)` | Modifies one argument before the method runs |
| `@Redirect(method, at = @At("INVOKE", target))` | Replaces a call inside the method |
| `@ModifyVariable(method, index, at = LOAD/STORE)` | Modifies a local variable |
| `@ModifyReturnValue(method)` | Modifies the returned value |
| `@Shadow` | Field: copied from / written back to the target. Abstract method: forwarded to the target |

`method` accepts a name (`"tick"`), parameters (`"hurt(F)"`) or a full descriptor (`"hurt(F)Z"`).
`@At` supports `ordinal` (which occurrence, 0-based) and `shift` (`BEFORE` / `AFTER`, INVOKE only).

## Lambda Hook Points

| HookPoint | Description |
|---|---|
| `BEFORE` | Runs before the method. Can change arguments and cancel execution. |
| `AFTER` | Runs after the method. Can modify the return value. |
| `REPLACE` | Replaces the method entirely. Original code does not run. |

## Validation

Problems are reported when you register, not when the hook silently fails to fire:

```
HookTargetException: Hook ServerPlayer#hurt: net.minecraft.server.level.ServerPlayer#hurt does not exist.
  Did you mean: hurtServer? (Paper 26.x runs with Mojang names; check the name for this Minecraft version.)
```

- Missing method → close names; inherited method → the class that declares it; wrong descriptor → all overloads
- Ambiguous name → warning listing the overloads (the hook applies to all of them)
- `@At("INVOKE")` target not found → the calls the method actually makes
- Handler signature mismatch, `ordinal` out of range, abstract/native targets
- If the JVM rejects the transformed class, the registration is rolled back and the error is thrown

At runtime, values of the wrong type (`setArg`, `setReturnValue`, Redirect results) are rejected before
they reach NMS code, and counted as hook failures.

## Diagnostics

`/nmshook` (permission `nmshook.admin`, default op):

| Command | |
|---|---|
| `/nmshook` | Summary |
| `/nmshook list [filter]` | Hooks with owner plugin, calls, avg time, failures (filter: plugin / class / method) |
| `/nmshook info <id>` | Details of one hook |
| `/nmshook enable <id>` / `disable <id>` | Toggle a hook (enable also resets its failure counter) |
| `/nmshook profile on\|off\|reset` | Per-hook timing |
| `/nmshook debug on\|off` | Bytecode dump + slow-phase logging |
| `/nmshook reload` | Reload `config.yml` |

`plugins/NMSHook/config.yml`:

```yaml
failure-threshold: 10     # disable a hook after N consecutive exceptions (0 = never)
profiling: false          # per-hook timing
debug: false              # dump transformed classes to plugins/NMSHook/debug/
slow-threshold-ms: 1.0
```

## Performance

Cost per call of a trivial method (`int add(int, int)`), JDK 21, one thread:

| | ns/call |
|---|---|
| Not instrumented | 0.3 |
| Instrumented, no active hook | 2.9 |
| One empty BEFORE hook | 6.3 |
| BEFORE + AFTER hooks | 12.8 |

(The previous dispatcher measured ~1450 ns/call for one empty BEFORE hook in the same setup.)

## Architecture

```
Your Plugin
  │
  ├── @NMSMixin / NMSHookLib.hook()    ← user-facing API (nmshook-api)
  │
NMSHook (nmshook-plugin)               Paper glue: /nmshook, config, plugin-disable cleanup
  │
  ├── nmshook-core       Engine: sites, registration, validation, dispatch
  ├── nmshook-api        Shared kernel: annotations, callbacks, MethodSelector, HookHandle
  ├── nmshook-hook       Hook domain: definitions, executor (isolation, circuit breaker, stats)
  ├── nmshook-mixin      Mixin domain: parsing, handler adapter, @Shadow
  ├── nmshook-transform  Transform domain: class plans, Advice, invokedynamic points, bootstrap dispatcher
  ├── nmshook-agent      Agent domain: JVM Instrumentation lifecycle
  └── nmshook-resolver   Resolver domain: version detection
```

**Runtime flow:**

```
NMS method called
  → inlined NMSAdvice.enter(siteId)          flags[siteId] == 0 → nothing else happens
    → HookDispatcher.enter()                 [bootstrap CL, constant call site]
      → engine.onEnter() → REPLACE → BEFORE  [plugin CL]
  → original method (or skipped)
       └ rewritten instructions → invokedynamic → engine.onPoint()   (INVOKE / Redirect / Modify*)
  → inlined NMSAdvice.exit(siteId)
    → engine.onExit() → AFTER hooks → return value
```

Each instrumented class has one *plan* (all its hooks). Every change re-transforms the class once from
its original bytecode, so the result does not depend on registration order, and removing the last hook
restores the original class.

## Configuration

### Debug Mode

Set `debug: true` in `config.yml`, run `/nmshook debug on`, or add `-Dnmshook.debug=true` to the JVM args.
Transformed bytecode is dumped to `plugins/NMSHook/debug/` and phases slower than `slow-threshold-ms` are logged.

### javaagent Mode (recommended — no JVM warnings)

Start the server with NMSHook as a javaagent:

```
java -javaagent:plugins/NMSHook-0.1.0-SNAPSHOT.jar -jar paper.jar
```

The plugin then uses the Instrumentation from `premain` and never self-attaches,
so the JVM's *"A Java agent has been loaded dynamically"* warning is never printed.
The jar stays in `plugins/` and is loaded as a normal plugin as well.

Without `-javaagent`, NMSHook falls back to dynamic attach (once per JVM — reloads reuse
the same Instrumentation). In that case you can hide the warning with:

```
-XX:+EnableDynamicAgentLoading   # hide dynamic agent loading warning
```

The bootstrap dispatcher is defined directly in the boot class loader (no class path append), so the
CDS warning *"Sharing is only supported for boot loader classes..."* is not printed and `-Xshare:off` is not needed.

## Building

```bash
./gradlew build
```

Requires JDK 25 (downloaded automatically via the Gradle toolchain resolver if missing).
`./gradlew :nmshook-core:test` runs the instrumentation tests (they retransform real classes in the test JVM).

Output: `nmshook-plugin/build/libs/NMSHook-<version>.jar`

## Dependency (for plugin developers)

Add `nmshook-api` as a compile-only dependency:

```kotlin
repositories {
    // your NMSHook repository
}

dependencies {
    compileOnly("dev.seraphicness.nmshook:nmshook-api:0.1.0-SNAPSHOT")
}
```

Since Minecraft 26.1 the server is unobfuscated and Paper no longer remaps plugins.
If your plugin references NMS classes, build it with Mojang names (paperweight-userdev
without a `reobfJar` step). Spigot-mapped (reobfuscated) jars will not work on 26.x.

And declare the dependency in `paper-plugin.yml`:

```yaml
dependencies:
  server:
    NMSHook:
      load: BEFORE
      required: true
```

## License

[MIT](LICENSE)
