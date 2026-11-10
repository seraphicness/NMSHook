# Lifecycle

## When to register

Register in your plugin's `onEnable`. NMSHook must be enabled first, which the
[`load: BEFORE` dependency](getting-started.md#3-declare-the-dependency-on-nmshook) guarantees.
`NMSHookLib.isAvailable()` tells you whether the engine is running.

Registering is synchronous: when `register()` / `registerMixin()` returns, the hook is active.
Each registration re-transforms the target class once, so register at startup rather than per event.

## HookHandle

```java
HookHandle handle = NMSHookLib.hook()...register();
HookHandle mixin  = NMSHookLib.registerMixin(MyHooks.class, this);

handle.id();            // number shown in /nmshook list (for a Mixin: its registration id)
handle.isRegistered();  // false after unregister() or after your plugin is disabled
handle.unregister();    // remove this hook / all handlers of this Mixin
```

Use handles to switch features on and off at runtime. Unregistering the last hook of a method restores
the method's original bytecode.

## Automatic cleanup

When a plugin is disabled, NMSHook removes every hook it registered:

- hooks whose `owner` is that plugin (or an object from it), and
- hooks whose listener lambda or Mixin class was loaded by that plugin — so hooks registered **without**
  an owner are cleaned up as well.

You do not need an `onDisable`. `NMSHookLib.unregisterAll(owner)` is still available if you want to
remove hooks earlier.

When NMSHook itself is disabled, all hooks are removed and every instrumented class is restored.

## Failures

A hook that throws never breaks the server method:

| Hook | On exception |
|---|---|
| BEFORE / AFTER | the exception is logged and the method continues as if the hook did nothing |
| REPLACE / `@Overwrite` | the original method runs |
| `@Redirect` | the original call runs |
| `@ModifyVariable` / `@ModifyReturnValue` | the value is left unchanged |

The first failure is logged with its stack trace; repeated failures only at FINE level. After
`failure-threshold` consecutive failures (default 10, see [Server setup](server-setup.md#configyml)) the
hook is **disabled** and a SEVERE message tells you its id. Fix the cause, then `/nmshook enable <id>`.

Values of the wrong type (`setArg`, `setReturnValue`, redirect results) are rejected before they reach
server code and count as failures.

## Reloads

`/reload` and plugin managers are supported: disabling removes the hooks, enabling registers them again.
NMSHook keeps one small dispatcher class in the JVM's boot class loader across reloads. If you update the
NMSHook jar to a version whose dispatcher changed, NMSHook refuses to start and asks for a full restart.
