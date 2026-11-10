# NMSHook

**Runtime NMS hook library for Paper plugins.**  
Intercept, modify, and replace NMS method behavior using annotations or a fluent lambda API — no server JAR modifications required.

Built on [ByteBuddy](https://bytebuddy.net/) + [paperweight-userdev](https://github.com/PaperMC/paperweight).

## Features

- **Type-safe NMS access** — Reference NMS classes as `Class<?>` directly via paperweight. No string-based reflection.
- **Non-invasive** — Plugin-only installation. No server core patches. Hooks are cleanly removed on disable.
- **Annotation API** — `@Inject`, `@Overwrite`, `@Shadow`, `@ModifyArg` for declarative hook definitions.
- **Lambda API** — Fluent builder for quick, inline hooks without annotation classes.
- **Runtime bytecode transformation** — ByteBuddy Advice inlined into NMS methods at runtime via `java.lang.instrument`.
- **Owner-based lifecycle** — Register and unregister hooks per plugin. No cross-plugin interference.
- **Debug mode** — Dump transformed bytecode and trace slow hook execution.
- **Folia compatible** — ThreadLocal-isolated handler instances for multi-threaded tick safety.

## Requirements

- Java 25+
- Paper 26.1 – 26.3
- ByteBuddy 1.18+ (bundled)

## Quick Start

### Lambda API

```java
NMSHookLib.hook()
    .owner(this)
    .target(ServerPlayer.class)
    .method("hurtServer")
    .at(HookPoint.BEFORE)
    .cancellable(true)
    .listener(ctx -> {
        float damage = ctx.getArg(2);
        if (damage > 20f) ctx.cancel();
    })
    .register();
```

### Annotation API

```java
@NMSMixin(target = ServerPlayer.class)
public abstract class ServerPlayerHook {

    @Shadow
    private float lastSentHealth;

    @Inject(method = "hurtServer", at = @At("HEAD"), cancellable = true)
    private void onHurt(ServerLevel level, DamageSource source, float amount, CallbackInfo ci) {
        if (lastSentHealth <= 5f) ci.cancel();
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

@Override
public void onDisable() {
    NMSHookLib.unregisterAll(this);
}
```

## Annotations

| Annotation | Description |
|---|---|
| `@NMSMixin(target)` | Declares a hook class targeting an NMS class |
| `@Inject(method, at)` | Injects code at HEAD, TAIL, or RETURN of a method |
| `@Overwrite` | Completely replaces the target method |
| `@ModifyArg(method, index)` | Modifies a specific method argument before execution |
| `@Shadow` | Binds a field to the target class instance (read + write) |

## Lambda Hook Points

| HookPoint | Description |
|---|---|
| `BEFORE` | Runs before the method. Can cancel execution. |
| `AFTER` | Runs after the method. Can modify the return value. |
| `REPLACE` | Replaces the method entirely. Original code does not run. |

## Architecture

```
Your Plugin
  │
  ├── @NMSMixin / NMSHookLib.hook()    ← user-facing API
  │
NMSHook (nmshook-plugin)
  │
  ├── nmshook-api        Shared kernel: annotations, callbacks, interfaces
  ├── nmshook-hook       Hook domain: registry, executor, priority
  ├── nmshook-mixin      Mixin domain: parsing, handler adapter, shadow binding
  ├── nmshook-transform  Transform domain: ByteBuddy Advice, bootstrap injection
  ├── nmshook-agent      Agent domain: JVM Instrumentation lifecycle
  └── nmshook-resolver   Resolver domain: Mojang mapping resolution
```

**Runtime flow:**

```
NMS method called
  → inlined NMSAdvice.enter()
    → HookDispatcher.dispatchBefore()          [bootstrap CL]
      → HookExecutor.handleBeforePhase()       [plugin CL]
        → REPLACE hooks → skip original
        → BEFORE hooks  → cancel if requested
  → original method (or skipped)
  → inlined NMSAdvice.exit()
    → HookDispatcher.dispatchAfter()
      → HookExecutor.handleAfterPhase()
        → AFTER hooks → modify return value
```

## Configuration

### Debug Mode

Add to your server JVM args:

```
-Dnmshook.debug=true
```

Transformed bytecode will be dumped to `plugins/NMSHook/debug/`.  
Hooks exceeding 1ms execution time will be logged as warnings.

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
-Xshare:off                      # hide the one-time CDS warning (bootstrap classpath append)
```

## Building

```bash
./gradlew build
```

Requires JDK 25 (downloaded automatically via the Gradle toolchain resolver if missing).

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
