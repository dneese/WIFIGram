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
- `app/src/main/cpp/speexdsp` — SpeexDSP 1.2.1 (вендорено, лише препроцесор).
- Обидві збираються статично в `wifigram-opus` через `app/src/main/cpp/CMakeLists.txt`.
- CMake потребує `-DFLOATING_POINT -DOUTSIDE_SPEEX -DRANDOM_PREFIX=wfg -DUSE_SMALLFT=1 -DEXPORT=` і `-include speex/speexdsp_types.h` — без цих defines Speex не компілюється.

## Код

- Kotlin, без сторонніх бібліотек, окрім Compose та AndroidX. Коментарі в коді — англійською, рядки для користувача — українською.
- Налаштування — у `ProfileStore` (SharedPreferences).
- Звук дзвінка — `CallSession`, буфер — `call/PlayoutBuffer.kt`, кодек — `call/OpusCodec.kt`, шумопоглинання — `call/Denoiser.kt`.
