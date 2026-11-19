# AGENTS.md

WIFIGramm — Android застосунок-інтерком між телефонами в мережі WiFi.

## Build and test — only on GitHub Actions

**Ніколи не збирати й не тестувати локально на Termux.** На смартфоні бракує пам'яті: Gradle (Kotlin + Compose) заїдає RAM, opencode падає разом із телефоном. Спроби локальної збірки вбивають сесію.

Як це працює:

- `./gradlew` на цій машині — не запускати. Навіть `testDebugUnitTest` занадто важкий.
- Нативного коду в застосунку більше немає: голос іде через готовий AAR WebRTC, NDK не потрібен.
- Тести, lint і збірка живуть у CI: `.github/workflows/android-build.yml` на кожен push у `main` (`Test & Lint`, `Build Debug`, `Build Release`).
- Реліз — тег `v*.*.*`: `bash scripts/release.sh v0.4.0` → `.github/workflows/release.yml` збирає підписані APK і створює GitHub Release.

Корисні команди CI (вони працюють, бо не їдуть на телефон):

```bash
gh run list --limit 3
gh run watch <run-id> --exit-status
gh run view <run-id> --log-failed
```

## Голос: WebRTC

- Голос дзвінка іде тільки через WebRTC: `io.getstream:stream-webrtc-android` (версія в `gradle/libs.versions.toml`, alias `webrtc-android`). Старого шляху з UDP-аудіо й вендореним Opus більше немає — код `app/src/main/cpp/**` видалено у v0.9.0.
- Дзвінок лише 1:1. Старі протоколи не підтримуються: телефон надсилає в hello capability-марки `v2` (`Protocol.CAP_PROTOCOL_V2`), `rtc2` (`Protocol.CAP_WEBRTC`), `call` (`Protocol.CAP_CALL`); телефон без цих марок отримує відмову з причиною `VoiceEngine.REASON_OLD_PEER`.
- Розмова — це offer/answer/candidate, які йдуть звичайним TCP-протоколом (`Protocol.TYPE_RTC`, `Protocol.RtcSignal`, версія 2).
- Рушій живе у окремому процесі `:voice` (`call/VoiceService.kt`), двері до нього — `call/VoiceClient.kt`. Нативна частина WebRTC падає в процесі, застосунок лишається живим.
- Кожен крок початку дзвінка пишеться в журнал `filesDir/voice_trace.txt` (`call/VoiceTrace.kt`). Останній рядок журналу показує в UI разом із причиною збою.

## Код

- Kotlin. Сторонні бібліотеки: Compose, AndroidX і libwebrtc (`io.github.webrtc-sdk:android`) для дзвінків. Коментарі в коді — англійською, рядки для користувача — українською.
- Налаштування — у `ProfileStore` (SharedPreferences). Мова програми — теж там (`Locales`).
- Налаштування — `Settings.kt` (гучність голосу, дзвінок при входячому, динамік за замовчуванням) + `ProfileStore.settings()`, UI — `ui/Dialogs.kt` (`TuningDialog`).
- Екран дзвінка — `ui/CallScreen.kt`: показує двигун, кодек, rtt/jitter/loss і діагностику (`Intercom.Quality`).
- Рядки — у `res/values/strings.xml` (укр) і `res/values-en/strings.xml` (англ); неhardcode-ити текст у Kotlin.
- Release-збірка ввімкнена з R8 (`isMinifyEnabled`), тому JNI-класи треба тримати в `app/proguard-rules.pro`.
