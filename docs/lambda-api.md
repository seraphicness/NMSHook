# Lambda API

```java
HookHandle handle = NMSHookLib.hook()
        .owner(plugin)                    // optional, recommended
        .target(ServerPlayer.class)       // required: the class that declares the method
        .method("hurtServer")             // required: name, optionally with descriptor
        .params(ServerLevel.class, DamageSource.class, float.class) // optional: pick one overload
        .at(HookPoint.BEFORE)             // required: BEFORE, AFTER or REPLACE
        .cancellable(true)                // BEFORE only: allow ctx.cancel()
        .priority(1000)                   // lower runs first, default 1000
        .listener(ctx -> { ... })         // required
        .register();                      // validates and installs; throws HookTargetException
```

## Builder methods

| Method | Description |
|---|---|
| `owner(Object)` | Usually your plugin. Used by `NMSHookLib.unregisterAll(owner)` and shown in `/nmshook list`. Hooks are also removed when your plugin is disabled even without an owner. |
| `target(Class<?>)` | The class that **declares** the method. Inherited methods must be hooked on the declaring class. |
| `method(String)` | `"tick"`, `"hurtServer(Lnet/.../ServerLevel;Lnet/.../DamageSource;F)"` or with return type `"...F)Z"`. |
| `descriptor(String)` | Same as the part after the name in `method(...)`. |
| `params(Class<?>...)` | Selects the overload with exactly these parameter types. |
| `at(HookPoint)` | See below. |
| `cancellable(boolean)` | Only cancellable BEFORE hooks can skip the original method. |
| `priority(int)` | Order among hooks on the same method and phase. Lower first. |
| `listener(HookListener)` | `ctx -> { }` |
| `register()` | Returns a [`HookHandle`](lifecycle.md#hookhandle). |

Without a descriptor the hook applies to **every overload** with that name, and NMSHook logs a warning
listing them. Add a descriptor or `params(...)` to pick one.

## Hook points

| Point | Runs | Can |
|---|---|---|
| `BEFORE` | before the method body | change arguments (`setArg`), cancel (if `cancellable`) with a return value |
| `AFTER` | after the method returns normally | read and change the return value |
| `REPLACE` | instead of the method | provide the return value; the original never runs |

Order on one method: **REPLACE → BEFORE (by priority) → original → AFTER (by priority)**.

- If a REPLACE hook runs successfully, BEFORE hooks, the original and AFTER hooks are skipped. Only the
  first enabled REPLACE hook (by priority) runs. If it throws, the original method runs instead.
- If a BEFORE hook cancels, the remaining BEFORE hooks, the original and the AFTER hooks are skipped.
- AFTER hooks do not run when the method throws an exception.

## HookContext

| Method | Description |
|---|---|
| `Object getInstance()` | `this` of the hooked method; `null` for static methods |
| `Object[] getArgs()` | Arguments (primitives are boxed) |
| `<T> T getArg(int index)` | Argument cast to the type you assign it to: `float damage = ctx.getArg(2);` |
| `void setArg(int index, Object value)` | BEFORE: change the argument the original receives. Wrong types throw immediately. |
| `void cancel()` / `boolean isCancelled()` | BEFORE + `cancellable(true)`: skip the original |
| `void setReturnValue(Object)` / `Object getReturnValue()` | Return value for cancel / REPLACE, or the current value in AFTER |
| `String getMethodName()` / `Class<?> getTargetClass()` | What is being hooked |

### Return values and primitives

- When cancelling or replacing a method that returns a primitive and you do not set a return value, the
  default is returned (`0`, `false`, ...).
- Values of the wrong type are rejected: the original / default value is used and a warning is logged.
  For example `setReturnValue("x")` on an `int` method never reaches the server code.
- Boxing matters: for a `float` method use `ctx.setReturnValue(1.0f)`, not `1.0` (a `Double`).

## Examples

**Halve all damage to players**

```java
NMSHookLib.hook().owner(this)
        .target(ServerPlayer.class).method("hurtServer")
        .at(HookPoint.BEFORE)
        .listener(ctx -> ctx.setArg(2, (float) ctx.getArg(2) * 0.5f))
        .register();
```

**Change a return value**

```java
NMSHookLib.hook().owner(this)
        .target(SomeNmsClass.class).method("isAllowed")
        .at(HookPoint.AFTER)
        .listener(ctx -> {
            if (!(boolean) ctx.getReturnValue() && bypass(ctx.getInstance())) ctx.setReturnValue(true);
        })
        .register();
```

**Replace a method**

```java
NMSHookLib.hook().owner(this)
        .target(SomeNmsClass.class).method("calculate").params(int.class)
        .at(HookPoint.REPLACE)
        .listener(ctx -> ctx.setReturnValue((int) ctx.getArg(0) * 2))
        .register();
```

## Threading

Listeners run on whatever thread calls the hooked method (main thread, async chunk threads, network
threads, Folia region threads). Keep them fast and thread-safe, and do not call Bukkit API that requires
the main thread from a hook unless you know the method runs there.

A listener that throws does not break the server method — see [failure handling](lifecycle.md#failures).
