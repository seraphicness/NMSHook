# Server setup

## Install

1. Use Paper **26.1 – 26.3** on **Java 25+**.
2. Put `NMSHook-<version>.jar` into `plugins/`.
3. Install the plugins that depend on it.

## Recommended: start NMSHook as a Java agent

```bash
java -javaagent:plugins/NMSHook-<version>.jar -jar paper.jar --nogui
```

The jar stays in `plugins/`; it is loaded both as an agent and as a plugin. As an agent it receives the
JVM's instrumentation at startup, so it never has to attach to the running JVM and the JVM prints no
warning.

Without `-javaagent`, NMSHook attaches itself once at startup and Java prints:

```
WARNING: A Java agent has been loaded dynamically ...
```

That is harmless. To hide it without `-javaagent`, add `-XX:+EnableDynamicAgentLoading`.
`-Xshare:off` is **not** needed.

## `/nmshook`

Permission `nmshook.admin` (default: op).

| Command | Description |
|---|---|
| `/nmshook` | Summary: hook count, instrumented classes, failure threshold, profiling/debug state |
| `/nmshook list [filter]` | All hooks: `#id KIND Class#method [owner plugin] calls, avg time, failures`. Filter by plugin, class or method name |
| `/nmshook info <id>` | Target, point, source (Mixin class / lambda), owner, priority, state, statistics |
| `/nmshook disable <id>` | Turn a hook off without unloading its plugin |
| `/nmshook enable <id>` | Turn it on again; also resets its consecutive-failure counter |
| `/nmshook profile on\|off\|reset` | Measure per-hook execution time / reset statistics |
| `/nmshook debug on\|off` | Dump transformed classes and log slow hooks |
| `/nmshook reload` | Re-read `config.yml` |

Call counts are always recorded; execution times only while profiling (or debug) is on.

## config.yml

`plugins/NMSHook/config.yml`:

```yaml
# A hook that throws this many times in a row is disabled (re-enable with /nmshook enable <id>).
# 0 = never disable.
failure-threshold: 10

# Record per-hook execution time (shown by /nmshook list).
profiling: false

# Dump transformed classes to plugins/NMSHook/debug/ and log slow hook phases.
# Also enabled by the JVM flag -Dnmshook.debug=true
debug: false

# Slow-phase threshold for debug logging, in milliseconds.
slow-threshold-ms: 1.0
```

## Debug dumps

With debug on, every class NMSHook transforms from then on is written to `plugins/NMSHook/debug/` as a
`.class` file. Open it with `javap -c` or an IDE to see exactly what was injected.
