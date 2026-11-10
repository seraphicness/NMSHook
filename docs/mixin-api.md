# Mixin API

A Mixin is a class annotated with `@NMSMixin` whose methods are handlers for one target class.

```java
@NMSMixin(target = ServerPlayer.class, priority = 1000)
public class PlayerHooks {
    @Inject(method = "tick", at = @At("HEAD"))
    private void onTick(CallbackInfo ci) { }
}

// in onEnable
HookHandle handle = NMSHookLib.registerMixin(PlayerHooks.class, this);
```

- The class needs a **no-arg constructor**. It may be `abstract` (needed for `@Shadow` methods).
- Handlers may be `private` and may be `static` (no instance needed).
- NMSHook creates **one Mixin instance per thread**. Instance fields (other than `@Shadow`) are therefore
  per-thread scratch state; use `static` fields for shared state.
- `priority` orders this Mixin's handlers against other hooks on the same method (lower first).
- Registration is all-or-nothing: if one handler is invalid, nothing of the Mixin is installed and a
  `HookTargetException` describes the problem.

## Choosing the target method

Every handler annotation has a `method` attribute:

| Value | Selects |
|---|---|
| `"tick"` | every overload named `tick` (a warning is logged if there are several) |
| `"hurt(F)"` | overloads with exactly these parameters |
| `"hurt(F)Z"` | the exact JVM descriptor |

`@Inject` handlers that declare the target's parameters (see below) select that overload automatically.
`@Overwrite` always uses the handler's own signature. See [Finding targets](targeting.md) for how to
get names and descriptors.

---

## `@Inject` — run code at a point

### HEAD — before the method body

```java
@Inject(method = "hurtServer", at = @At("HEAD"), cancellable = true)
private void onHurt(ServerLevel level, DamageSource source, float amount, CallbackInfoReturnable<Boolean> cir) {
    if (amount > 20f) {
        cir.setReturnValue(false); // value returned when cancelled
        cir.cancel();
    }
}
```

Handler signature: **`(target parameters..., CallbackInfo ci)`** or with `CallbackInfoReturnable<R>`.

- The target parameters are optional: `(CallbackInfo ci)` alone is valid. If you declare them, they must
  match the target exactly (and they select the overload).
- `ci.cancel()` skips the original method — only with `cancellable = true`.
- For non-void methods use `CallbackInfoReturnable<R>` to choose the return value when cancelling;
  without it the default value (`0`, `false`, `null`) is returned.

### TAIL / RETURN — after the method returns

```java
@Inject(method = "getMaxHealth", at = @At("TAIL"))
private void onMaxHealth(CallbackInfoReturnable<Float> cir) {
    cir.setReturnValue(cir.getReturnValue() * 2);
}
```

`cir.getReturnValue()` is the value the method returned; `setReturnValue` changes it.
`TAIL` and `RETURN` behave the same. Not called when the method throws.

### INVOKE — before or after a call inside the method

```java
@Inject(method = "tick",
        at = @At(value = "INVOKE",
                 target = "Lnet/minecraft/server/level/ServerPlayer;doTick()V",
                 shift = At.Shift.BEFORE,   // or AFTER
                 ordinal = -1),             // which occurrence, -1 = all
        cancellable = true)
private void beforeDoTick(CallbackInfo ci) {
    if (frozen) ci.cancel();   // returns from tick() immediately
}
```

Handler signature: same as HEAD — `(target parameters..., CallbackInfo)`.
The parameter values are the **current** values at that point of the method.

With `cancellable = true`, `ci.cancel()` returns from the **target** method right there (with the value
from `CallbackInfoReturnable`, or the default). Cancellation is implemented with an internal signal that
unwinds to the method's exit; a `catch (Throwable)` block inside the target method between the call and
the method exit would intercept it, so avoid cancelling inside such blocks.

---

## `@Overwrite` — replace the whole method

```java
@Overwrite
public void attack(Entity target) {
    // the original attack(Entity) never runs
}

@Overwrite(method = "calculateDamage")   // when the handler name must differ
private float myCalculateDamage(float base) { return base; }
```

The handler's **parameter and return types select the overload**, so they must match the target exactly.
If several Mixins overwrite the same method, only the one with the lowest `priority` runs and a conflict
warning is logged.

---

## `@ModifyArg` — change one argument

```java
@ModifyArg(method = "hurtServer", index = 2)
private float halveDamage(float amount) {
    return amount * 0.5f;
}
```

Handler signature: **`T handler(T value)`** where `T` is exactly the type of parameter `index` (0-based).
Runs before other BEFORE hooks of the same Mixin priority.

---

## `@Redirect` — replace a call inside the method

```java
@Redirect(method = "tick",
          at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Entity;discard()V"))
private void dontDiscard(Entity entity) {
    // the call entity.discard() inside tick() is replaced by this
}
```

Handler signature: **`(receiver, call arguments...) -> call return type`** for an instance call, or
**`(call arguments...) -> call return type`** for a static call.

| Original call | Handler |
|---|---|
| `entity.discard()` (`()V`) | `void h(Entity entity)` |
| `level.getBlockState(pos)` (`(Lnet/minecraft/core/BlockPos;)L.../BlockState;`) | `BlockState h(Level level, BlockPos pos)` |
| `Math.max(a, b)` (static `(II)I`) | `static int h(int a, int b)` |
| `list.size()` (interface `()I`) | `int h(List<?> list)` |

- `ordinal` in `@At` picks one occurrence of the call; default `-1` redirects all.
- If the handler throws or is disabled, **the original call is executed instead**.
- Calling the original yourself is just a normal Java call: `return level.getBlockState(pos);`
- Constructors (`<init>`) cannot be redirected.

---

## `@ModifyVariable` — change a local variable

```java
// void example(int x) { int y = x * 2; ... }   slots: 0 = this, 1 = x, 2 = y
@ModifyVariable(method = "example", index = 2, at = @At(value = "STORE", ordinal = 0))
private int clampY(int y) {
    return Math.min(y, 100);
}
```

Handler signature: **`T handler(T value)`** where `T` is **exactly** the variable's declared type.

| `at` | Runs |
|---|---|
| `@At("STORE")` (default) | on the value being written to the variable |
| `@At("LOAD")` | on the value being read from the variable (the variable itself is unchanged) |

- `index` is the **JVM local variable slot**, not the n-th variable. See
  [Finding targets → local variable slots](targeting.md#local-variable-slots).
- `ordinal` selects which store/load (0-based, in bytecode order); `-1` = all.
- `x++` style increments (`iinc`) are not stores and are not intercepted.

---

## `@ModifyReturnValue` — change the returned value

```java
@ModifyReturnValue(method = "isInvulnerableTo")
private boolean neverInvulnerable(boolean original) {
    return false;
}
```

Handler signature: **`R handler(R original)`** where `R` is exactly the target's return type.
Applies to every `return` in the method; `at = @At(value = "RETURN", ordinal = n)` selects one.

---

## `@Shadow` — access the target instance

```java
@NMSMixin(target = ServerPlayer.class)
public abstract class ShadowExample {

    @Shadow
    private int spawnInvulnerableTime;     // field: same name, compatible type

    @Shadow
    abstract boolean isSpectator();        // method: same name and parameter types, must be abstract

    @Inject(method = "tick", at = @At("HEAD"))
    private void onTick(CallbackInfo ci) {
        if (!isSpectator()) spawnInvulnerableTime = 0;   // written back after the handler
    }
}
```

- **Fields** are copied from the target instance before the handler runs and written back afterwards
  (final fields are not written back). They are looked up by name in the target class and its superclasses.
- **Methods** must be declared `abstract`; calls are forwarded to the target instance's method with the
  same name and parameter types (searched up the hierarchy).
- Shadows refer to the instance of the hooked call, so they only work in handlers on instance methods.
- Missing shadow members are reported at registration.

---

## Combining

Any number of handlers and Mixins can target the same method. HEAD/TAIL/Overwrite/ModifyArg hooks and
lambda hooks share one method-level dispatcher; INVOKE / Redirect / ModifyVariable / ModifyReturnValue
rewrite single instructions. Instruction-level hooks always see the **original** method bytecode, so
ordinals and slots do not shift because of other hooks.

## Annotation reference

| Annotation | Attributes |
|---|---|
| `@NMSMixin` | `target` (Class), `priority` (int, default 1000) |
| `@Inject` | `method`, `at`, `cancellable` (default false) |
| `@Overwrite` | `method` (default: handler name) |
| `@ModifyArg` | `method`, `index` (default 0) |
| `@Redirect` | `method`, `at` (`INVOKE` + `target`, `ordinal`) |
| `@ModifyVariable` | `method`, `index`, `at` (`STORE` default / `LOAD`, `ordinal`) |
| `@ModifyReturnValue` | `method`, `at` (`RETURN` default, `ordinal`) |
| `@Shadow` | — |
| `@At` | `value`, `target` (INVOKE), `ordinal` (default -1), `shift` (INVOKE: `BEFORE` default / `AFTER`) |
