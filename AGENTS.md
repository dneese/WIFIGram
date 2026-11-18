# AGENTS.md

WIFIGramm — Android застосунок-інтерком між телефонами в мережі WiFi.

## Build and test — only on GitHub Actions

**Ніколи не збирати й не тестувати локально на Termux.** На смартфоні бракує пам'яті: Gradle (Kotlin + Compose) заїдає RAM, opencode падає разом із телефоном. Спроби локальної збірки вбивають сесію.

Як це працює:

- `./gradlew` на цій машині — не запускати. Навіть `testDebugUnitTest` занадто важкий.
- Нативний код (Opus, Speex) потребує NDK, якого тут немає — його качати не треба, CI зробить це сам.
- Тести, lint і збірка живуть у CI: `.github/workflows/android-build.yml` на кожен push у `main` (`Test & Lint`, `Build Debug`, `Build Release`).
- Реліз — тег `v*.*.*`: `bash scripts/release.sh v0.4.0` → `.github/workflows/release.yml` збирає підписані APK і створює GitHub Release.

Корисні команди CI (вони працюють, бо не їдуть на телефон):

```bash
gh run list --limit 3
gh run watch <run-id> --exit-status
gh run view <run-id> --log-failed
```

## Нативні бібліотеки

- `app/src/main/cpp/opus` — Opus 1.5.2 (вендорено, читає `Makefile.am`, `configure.ac`, `package_version`).
- Збирається статично в `wifigram-opus` через `app/src/main/cpp/CMakeLists.txt`.
- SpeexDSP прибрано у v0.4.1: його шумопоглиначав голос разом із гуком. Тепер шум і ехо прибирає сам телефон (`call/VoiceEffects.kt`).

## Код

- Kotlin. Сторонні бібліотеки: Compose, AndroidX і libwebrtc (`io.github.webrtc-sdk:android`) для дзвінків. Коментарі в коді — англійською, рядки для користувача — українською.
- Налаштування — у `ProfileStore` (SharedPreferences). Мова програми — теж там (`Locales`).
- Голос дзвінка іде через WebRTC (`call/WebRtcCall.kt`): DTLS-SRTP, вбудований Opus, AEC/NS/AGC, ICE тільки по локальній мережі.
- Сигналізація WebRTC (`offer`/`answer`/`ICE candidate`) ходить наявним TCP-каналом (`net/Protocol.kt`, тип `TYPE_RTC`).
- Старий ручний голосовий шлях (`call/CallSession.kt`, `net/AudioDatagram.kt`) лишився в коді як запасний шар і вже не використовується `Intercom`.
- Рядки — у `res/values/strings.xml` (укр) і `res/values-en/strings.xml` (англ); неhardcode-ити текст у Kotlin.
- Release-збірка ввімкнена з R8 (`isMinifyEnabled`), тому JNI-класи треба тримати в `app/proguard-rules.pro`.
