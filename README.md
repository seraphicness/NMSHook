# NMSHook

[![JitPack](https://jitpack.io/v/seraphicness/NMSHook.svg)](https://jitpack.io/#seraphicness/NMSHook)
![Paper](https://img.shields.io/badge/Paper-26.1%20–%2026.3-blue)
![Java](https://img.shields.io/badge/Java-25-orange)
[![License: MIT](https://img.shields.io/badge/License-MIT-green.svg)](LICENSE)

**Mixin-style runtime hooks into Paper server internals — no server patches, no forks.**

NMSHook lets Paper plugins change how the Minecraft server itself behaves. Hooks are written with
Mixin-like annotations or a lambda builder and applied at runtime with ByteBuddy, from a normal plugin.

📖 **[Documentation](docs/README.md)** ·
[Getting started](docs/getting-started.md) ·
[Lambda API](docs/lambda-api.md) ·
[Mixin API](docs/mixin-api.md) ·
[Finding targets](docs/targeting.md) ·
[Server setup](docs/server-setup.md) ·
[Troubleshooting](docs/troubleshooting.md)

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
- **Thread-safe** — Per-thread Mixin instances, lock-free dispatch (Folia-ready design; not yet declared `folia-supported`).

## Requirements

- Java 25+
- Paper 26.1 – 26.3
- ByteBuddy 1.18+ (bundled)

## Installation (server owners)

1. Paper **26.1 – 26.3**, Java **25+**.
2. Put `NMSHook-<version>.jar` in `plugins/`.
3. Recommended — start the server with NMSHook as a Java agent (avoids a harmless JVM warning):

   ```bash
   java -javaagent:plugins/NMSHook-<version>.jar -jar paper.jar --nogui
   ```

Manage hooks in game with `/nmshook` (permission `nmshook.admin`). Details: [Server setup](docs/server-setup.md).

## Usage (plugin developers)

### 1. Dependency

```kotlin
repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://jitpack.io")
}

dependencies {
    compileOnly("com.github.seraphicness.NMSHook:nmshook-api:TAG") // TAG = release tag, e.g. v0.1.0
}
```

Use `compileOnly` — **never shade the API**; the classes come from the NMSHook plugin at runtime.
To reference NMS classes such as `ServerPlayer` at compile time, use
[paperweight-userdev](https://docs.papermc.io/paper/dev/userdev/) **without `reobfJar`** (Paper 26.x runs
with Mojang names and no longer remaps plugins). Maven setup: [Getting started](docs/getting-started.md).

### 2. paper-plugin.yml

```yaml
dependencies:
  server:
    NMSHook:
      load: BEFORE
      required: true
      join-classpath: true
```

(classic `plugin.yml`: `depend: [NMSHook]`)

### 3. Register hooks in `onEnable`

**Lambda**

```java
@Override
public void onEnable() {
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
}
```

**Mixin**

```java
@NMSMixin(target = ServerPlayer.class)
public abstract class ServerPlayerHook {

    @Shadow private float lastSentHealth;          // copied from / written back to the player
    @Shadow abstract boolean isSpectator();        // forwarded to the real method

    // declaring the target's parameters selects that overload
    @Inject(method = "hurtServer", at = @At("HEAD"), cancellable = true)
    private void onHurt(ServerLevel level, DamageSource source, float amount, CallbackInfoReturnable<Boolean> cir) {
        if (lastSentHealth <= 5f || isSpectator()) {
            cir.setReturnValue(false);
            cir.cancel();
        }
    }

    @ModifyArg(method = "hurtServer", index = 2)
    private float halve(float damage) {
        return damage * 0.5f;
    }

    @Redirect(method = "tick", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/Entity;discard()V"))
    private void keep(Entity entity) { }

    @ModifyReturnValue(method = "isInvulnerableTo")
    private boolean never(boolean original) {
        return false;
    }
}
```

```java
NMSHookLib.registerMixin(ServerPlayerHook.class, this);
```

No `onDisable` code is needed: when your plugin is disabled, its hooks are removed and the original
server code is restored. Mistakes (wrong name, descriptor, signature) throw `HookTargetException` at
registration with an explanation. Full reference: [Lambda API](docs/lambda-api.md), [Mixin API](docs/mixin-api.md).

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

## Building

```bash
./gradlew build                 # plugin jar: nmshook-plugin/build/libs/NMSHook-<version>.jar
./gradlew :nmshook-core:test    # instrumentation tests (retransform real classes in the test JVM)
./gradlew :nmshook-api:publishToMavenLocal   # API jar + sources + javadoc into ~/.m2
```

Requires JDK 25 (downloaded automatically by the Gradle toolchain resolver if missing).

### Releasing the API

Push a tag; JitPack builds `nmshook-api` from it on first request (see `jitpack.yml`):

```bash
git tag v0.1.0 && git push origin v0.1.0
# -> com.github.seraphicness.NMSHook:nmshook-api:v0.1.0
```

The API can also be published to GitHub Packages with
`./gradlew :nmshook-api:publishAllPublicationsToGitHubPackagesRepository` (needs `GITHUB_ACTOR` /
`GITHUB_TOKEN`; consumers of GitHub Packages need a token too, so JitPack is the default).

## License

[MIT](LICENSE)
