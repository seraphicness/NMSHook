# Getting started

This guide takes you from an empty Paper plugin to a working hook.

## 1. Requirements

- Paper **26.1 – 26.3** server running on **Java 25+**
- The **NMSHook plugin jar** in the server's `plugins/` folder (see [Server setup](server-setup.md))
- Your plugin built with **Java 25** against Mojang names (Paper 26.x is unobfuscated)

## 2. Add the API dependency

The API is published through [JitPack](https://jitpack.io/#seraphicness/NMSHook).
Replace `TAG` with a release tag of this repository (for example `v0.1.0`) or a commit hash.

**Gradle (Kotlin DSL)**

```kotlin
repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://jitpack.io")
}

dependencies {
    compileOnly("com.github.seraphicness.NMSHook:nmshook-api:TAG")
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
}
```

**Maven**

```xml
<repositories>
    <repository>
        <id>jitpack.io</id>
        <url>https://jitpack.io</url>
    </repository>
</repositories>

<dependency>
    <groupId>com.github.seraphicness.NMSHook</groupId>
    <artifactId>nmshook-api</artifactId>
    <version>TAG</version>
    <scope>provided</scope>
</dependency>
```

> **Use `compileOnly` / `provided`. Never shade `nmshook-api` into your jar.**
> The classes must come from the NMSHook plugin at runtime. A shaded copy has its own
> `NMSHookLib` that was never initialised and throws `NMSHook is not yet initialized`.

### Referencing NMS classes

To write `ServerPlayer.class` in your code you need the server internals on your compile classpath.
Use [paperweight-userdev](https://docs.papermc.io/paper/dev/userdev/) with the dev bundle of the lowest
Paper version you support, **without a `reobfJar` step** — since 26.1 the server runs with Mojang names and
Paper no longer remaps plugins:

```kotlin
plugins {
    java
    id("io.papermc.paperweight.userdev") version "2.0.0-beta.24"
}

dependencies {
    paperweight.paperDevBundle("26.1.2.build.+")
    compileOnly("com.github.seraphicness.NMSHook:nmshook-api:TAG")
}
```

Keep the `.build.` part in the version: a bare `26.1.+` can resolve to a different, possibly breaking
release. Only the latest paperweight-userdev release is supported by PaperMC; check the
[Plugin Portal](https://plugins.gradle.org/plugin/io.papermc.paperweight.userdev) for newer versions.
Since the dev bundle already contains the Paper API, you do not need a separate `paper-api` dependency.

If you only hook a few methods you can also avoid compile-time NMS access and look classes up at runtime:

```java
Class<?> serverPlayer = Class.forName("net.minecraft.server.level.ServerPlayer");
```

## 3. Declare the dependency on NMSHook

**`paper-plugin.yml`**

```yaml
dependencies:
  server:
    NMSHook:
      load: BEFORE        # NMSHook must be enabled before your plugin
      required: true
      join-classpath: true  # lets your plugin see the NMSHook API classes
```

**`plugin.yml`** (classic Bukkit plugins)

```yaml
depend: [NMSHook]
```

## 4. Your first hook

```java
public final class MyPlugin extends JavaPlugin {

    @Override
    public void onEnable() {
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
    }
}
```

That's it — no `onDisable` code is needed. When your plugin is disabled, NMSHook removes its hooks and
restores the original server code.

If something is wrong (method name, descriptor, handler signature), `register()` throws a
`HookTargetException` with a message that explains the problem. Let it fail your `onEnable` during
development, or catch it to disable just that feature:

```java
try {
    NMSHookLib.registerMixin(MyHooks.class, this);
} catch (HookTargetException e) {
    getLogger().warning("Feature X disabled: " + e.getMessage());
}
```

## 5. Check that it works

Run `/nmshook list` on the server. Your hook appears with its id, owner and a call counter.

## Next steps

- [Lambda API](lambda-api.md) — the full builder and `HookContext`
- [Mixin API](mixin-api.md) — annotation-based hooks, including call redirects and local variables
- [Finding targets](targeting.md) — how to find the right method and descriptor
