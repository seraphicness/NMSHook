# Finding targets

Hooks are matched against the bytecode of the running server, so names and descriptors must be exactly
what the server uses. Since Paper 26.1 the server is unobfuscated: names are the Mojang names you see in
your IDE with the paperweight dev bundle.

If anything is wrong, registration fails with a `HookTargetException` that lists close names, the
available overloads, or the calls the method actually makes. Reading that message is often the fastest way.

## Viewing the bytecode

Any of these works:

- **IntelliJ IDEA**: open the class from the dev bundle → *View → Show Bytecode*.
- **javap** on the server jar. After the server has started once, Paperclip has extracted the real
  server jar to `versions/<version>/` in the server folder:

  ```bash
  javap -p -c -l -cp versions/26.1.2/paper-26.1.2.jar net.minecraft.server.level.ServerPlayer
  ```

  `-p` shows private members, `-c` the instructions, `-l` the local variable tables.

## Method names and descriptors

A JVM descriptor lists parameter types in parentheses, then the return type:

| Java | Descriptor |
|---|---|
| `boolean` `byte` `char` `short` `int` `long` `float` `double` `void` | `Z` `B` `C` `S` `I` `J` `F` `D` `V` |
| `String` | `Ljava/lang/String;` |
| `ServerPlayer` | `Lnet/minecraft/server/level/ServerPlayer;` |
| `int[]` / `String[][]` | `[I` / `[[Ljava/lang/String;` |
| `boolean hurtServer(ServerLevel, DamageSource, float)` | `(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/damagesource/DamageSource;F)Z` |

`javap -s` prints descriptors directly. You can write either the parameters only (`"hurtServer(...F)"`)
or the full descriptor (`"hurtServer(...F)Z"`). In the lambda API, `params(ServerLevel.class,
DamageSource.class, float.class)` builds the parameter part for you.

**Only methods declared in the target class can be hooked.** If `ServerPlayer` inherits `tick()` from
`LivingEntity` without overriding it, hook `LivingEntity` and check `ctx.getInstance() instanceof ServerPlayer`.
NMSHook tells you the declaring class when you get this wrong.

## INVOKE targets

`@At(value = "INVOKE", target = ...)` names a call instruction:

```
L<owner>;<name><descriptor>
```

Take it from the `invoke…` line in `javap -c`:

```
42: invokevirtual #118  // Method net/minecraft/world/entity/Entity.discard:()V
```

becomes

```java
target = "Lnet/minecraft/world/entity/Entity;discard()V"
```

- The owner is the class **written in the call instruction** — the static type at the call site — which
  can be a superclass or interface of the object's real class (`java/util/List`, not `ArrayList`).
- The descriptor can be omitted (`"Lnet/minecraft/world/entity/Entity;discard"`) to match any overload.
- Dots are accepted instead of slashes.
- `ordinal` counts matching calls in bytecode order, starting at 0.

## Local variable slots

`@ModifyVariable(index = …)` uses the JVM slot number:

1. Slot `0` is `this` for instance methods (static methods start parameters at 0).
2. Parameters follow in order; `long` and `double` take **two** slots.
3. Local variables come after the parameters, in the slots the compiler assigned.

```java
public double half(double d) {   // 0 = this, 1-2 = d
    double h = d / 2;            // 3-4 = h
    return h;
}
```

The reliable way to find a slot is `javap -l` (LocalVariableTable) or the `istore_3` / `dload 5` numbers
in `javap -c`. Slots can be reused by different variables in different blocks of one method, so check the
range in the LocalVariableTable and use `ordinal` to pick the right store or load.
