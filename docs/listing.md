# Store listing

Copy-paste material for Hangar, Modrinth, SpigotMC-style pages and the GitHub repository.

## Name

**NMSHook**

## Summary (one line, ≤ 100 characters)

> Mixin-style runtime hooks into Paper server internals — no server patches, no forks.

한국어: Paper 서버 내부(NMS)를 Mixin 방식으로 런타임에 후킹하는 라이브러리 — 서버 수정 없이.

## Description (English)

**NMSHook** lets Paper plugins change how the Minecraft server itself behaves — at runtime, from a
normal plugin, without forking or patching the server.

Write hooks the way you would with Mixin:

```java
@NMSMixin(target = ServerPlayer.class)
public class PlayerHooks {
    @Inject(method = "hurtServer", at = @At("HEAD"), cancellable = true)
    private void onHurt(ServerLevel level, DamageSource source, float amount, CallbackInfoReturnable<Boolean> cir) {
        if (amount > 20f) { cir.setReturnValue(false); cir.cancel(); }
    }
}
```

or inline with a lambda:

```java
NMSHookLib.hook().owner(this).target(ServerPlayer.class).method("hurtServer")
    .at(HookPoint.BEFORE).listener(ctx -> ctx.setArg(2, (float) ctx.getArg(2) * 0.5f)).register();
```

**Features**

- `@Inject` (HEAD / TAIL / INVOKE), `@Overwrite`, `@ModifyArg`, `@Redirect`, `@ModifyVariable`,
  `@ModifyReturnValue`, `@Shadow` fields and methods
- Lambda API with BEFORE / AFTER / REPLACE hooks
- Pick exact overloads by descriptor or parameter types
- Mistakes are reported at startup with suggestions ("Did you mean: hurtServer?")
- Hooks are removed automatically when your plugin is disabled; the original code is restored
- A broken hook never crashes the server: it is isolated and switched off after repeated failures
- A few nanoseconds of overhead per hooked call
- `/nmshook` to list, profile, enable and disable hooks at runtime

**For server owners:** install it when a plugin requires it. Optionally start the server with
`-javaagent:plugins/NMSHook-<version>.jar` to avoid a harmless JVM warning.

**For developers:** `compileOnly("com.github.seraphicness.NMSHook:nmshook-api:<tag>")` from JitPack.
Docs: https://github.com/seraphicness/NMSHook/tree/master/docs

Requires Paper 26.1 – 26.3 and Java 25.

## 설명 (한국어)

**NMSHook**은 Paper 플러그인이 마인크래프트 서버 자체의 동작을 바꿀 수 있게 해 주는 라이브러리입니다.
서버를 포크하거나 패치하지 않고, 일반 플러그인으로 런타임에 적용됩니다.

- Mixin과 같은 방식의 어노테이션: `@Inject`(HEAD / TAIL / INVOKE), `@Overwrite`, `@ModifyArg`,
  `@Redirect`, `@ModifyVariable`, `@ModifyReturnValue`, `@Shadow`
- 람다로 바로 거는 BEFORE / AFTER / REPLACE 훅
- 디스크립터나 파라미터 타입으로 정확한 오버로드 지정
- 잘못된 대상은 서버 시작 시점에 바로 알려줌 ("Did you mean: hurtServer?")
- 플러그인이 꺼지면 훅 자동 해제, 원래 코드로 복원
- 고장 난 훅은 서버를 멈추지 않고 격리되며, 계속 실패하면 자동으로 꺼짐
- 훅이 걸린 호출당 몇 나노초 수준의 오버헤드
- `/nmshook`으로 훅 목록, 프로파일링, 켜기/끄기

**서버 관리자:** 이 라이브러리가 필요한 플러그인이 있을 때 설치하세요. 서버를
`-javaagent:plugins/NMSHook-<버전>.jar`로 시작하면 JVM 경고도 뜨지 않습니다.

**개발자:** JitPack에서 `compileOnly("com.github.seraphicness.NMSHook:nmshook-api:<태그>")`.

요구 사항: Paper 26.1 – 26.3, Java 25.

## Tags

| Platform | Values |
|---|---|
| Hangar category | Developer Tools |
| Hangar tags | Library, Supports Folia: **no** (not declared yet) |
| Modrinth project type | Plugin |
| Modrinth categories | Library, Utility |
| Modrinth loaders | Paper |
| Modrinth game versions | 26.1, 26.2, 26.3 |
| GitHub topics | `minecraft`, `paper`, `paper-plugin`, `papermc`, `nms`, `mixin`, `bytecode`, `bytebuddy`, `java-agent`, `minecraft-plugin`, `library`, `java` |

GitHub "About" description:

> Mixin-style runtime hooks into Paper (26.x) server internals for plugins. ByteBuddy-based, no server patches.

## Links

- Source: https://github.com/seraphicness/NMSHook
- Docs: https://github.com/seraphicness/NMSHook/tree/master/docs
- API: https://jitpack.io/#seraphicness/NMSHook
- License: MIT
