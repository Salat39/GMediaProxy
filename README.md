# GMediaProxy

**GMediaProxy** (Git repository `GMediaProxyPub`, application id `com.geely.online.service`) is an Android app for Geely OneOS automotive head units. It acts as a **media proxy**: it captures playback from third-party music apps via **MediaSession**, republishes metadata and controls through Geely's **`IMusicManager` AIDL binder**, and integrates with the car's **Media Center** for audio source switching. The app uses the OEM package name **`com.geely.online.service`** and exposes **`OnlineMusicService`** with action **`com.geely.service.ONLINE_MUSIC`** — the same contract the stock online-music service uses on the head unit.

## Support the project

If this app helps you, you can support further development, maintenance, and new features.

[![Donate](https://img.shields.io/badge/Donate-CloudTips-orange?style=for-the-badge)](https://pay.cloudtips.ru/p/19d38600)

### Crypto
- **BTC:** `bc1q37z3d7avhsq3ehpsjm2wldj86ajsnsd6gqnkzm`
- **ETH:** `0x69C73C422FEBBf12F47C29C51501Ad659fcdf74A`

Thanks for supporting the project.

## What's in the app

**Car integration (AIDL binder)**

- **`OnlineMusicService`** — exported service implementing **`IMusicManager.Stub()`**; the car system binds via action **`com.geely.service.ONLINE_MUSIC`**.
- **`OnlineMusicState`** — state holder and listener callbacks to the IVI media widget.
- AIDL contracts under **`app/src/main/aidl/`** (`IMusicManager`, listeners, **`MusicInfo`**).
- Implemented binder surface: playback (**`play`**, **`pause`**, **`next`**, **`pre`**, **`seekTo`**), queries (**`getCurrentPlayState`**, **`getCurrentPosition`**, **`queryMediaInfoSync`**, **`getPlayList`**), favorites (**`addFavor`**, **`cancelFavor`**), source management, **`startApp`**, **`addListener`**.

**MediaSession capture and forwarding**

- **`MediaSessionNotificationListenerService`** — notification listener service; **notification access** must be granted by the user.
- **`MediaSessionPipeline`** — session monitoring, metadata and cover-art extraction, playback command forwarding, cover caching to external storage. Works with any app that exposes a **MediaSession** once notification access is granted.
- **`AuxiliaryPackages`** — dedicated handling for Murglar, Yandex Music, VKX (favorites), Hava YM (cover art), and Strelka (burst-sync).
- Native IVI sources are skipped for online proxy: Bluetooth, Geely USB, Geely Radio, Autokit Karaoke.

**OneOS Media Center**

- **`OneOsApiBootstrap`** — initializes **`OneOSApiManager`** at app start.
- **`OnlineAudioSourceGate`** — coordinates online vs native audio source via **`MediaCenterManager`**.

**Settings UI**

- **`MainActivity`** — Jetpack Compose settings: notification-access gate, **Display as** source icon picker, like-button options, broadcast-intent help, link to logs.
- **`LogActivity`** and **`AppLogger`** — optional in-app debug log ring buffer (toggle via **`logs_enabled`** in DataStore).

**Favorites and widget controls**

- **`FavoriteRatingController`** — per-app like handling (Yandex Music, Murglar, VKX).
- **`LikeMode`**: Vendor rating / Open current player / Send intent.
- DataStore keys (**`SettingsDataStore`**): **`display_as_app_source_key`**, **`display_like_instead_of_previous`**, **`like_mode`**, **`logs_enabled`**.
- Active **Display as** source keys: **`flow`**, **`net_easy_cloud`**, **`kugou`**, **`kuwo`**, **`fandeng`**, **`jinri`**, **`sohu`**, **`xmly`**.

**Broadcast automation API**

- **`OnlineMusicUpdateReceiver`** — MacroDroid / automation integration (package **`com.geely.online.service`**).
- **Incoming:**
  - **`com.salat.gmproxy.MEDIA_UPDATE`** — extras: **`title`**, **`artist`**, **`album`**, **`cover`** (URL or path), **`progressMs`**, **`durationMs`** (long, ms), **`forceMetadata`** (boolean, optional). Play state is taken from the last known session, not from broadcast extras.
  - **`com.salat.gmproxy.ENABLE`** — resume forwarding from the real media session
  - **`com.salat.gmproxy.DISABLE`** — stop session forwarding; only **`MEDIA_UPDATE`** applies
- **Outgoing:** **`com.salat.gmproxy.LIKE`** when like mode is Send Intent.

**Startup**

- **`App`** — DataStore bootstrap, log toggle, OneOS init.

**Data and services**

- **AndroidX DataStore** for preferences; **Firebase** (Analytics, Crashlytics) as wired in **`app/build.gradle.kts`**.

## Tech stack

- Kotlin 2.0.21, Coroutines  
- Jetpack Compose (Material 3)  
- AIDL (`aidl = true`)  
- AndroidX DataStore Preferences  
- Firebase (Analytics, Crashlytics)  
- Gradle 9.4.1, AGP 9.2.1  
- R8 shrink/obfuscation on release

## Module layout

**`:com_geely`** — bundled OneOS API client library (485 Java files under **`com.geely.lib.oneosapi`**), included via **`implementation(project(":com_geely"))`**. The app actively uses **`OneOSApiManager`** and **`MediaCenterManager`**; most other packages are bundled for linkage and ProGuard preservation. Runtime behavior of car services still comes from the **system image** on the head unit.

Only **`:app`** and **`:com_geely`** are included (`settings.gradle.kts`).

## Requirements

- **JDK 11**
- **Android SDK**: `compileSdk` / `targetSdk` **36**, `minSdk` **24** (see `app/build.gradle.kts`)
- **Gradle Wrapper** (project uses Gradle **9.4.1** — see `gradle/wrapper/gradle-wrapper.properties`)
- **Android Studio** (or IntelliJ) with the Android plugin
- **`local.properties`** with `sdk.dir` (gitignored)

## Build and run

From the repository root:

```bash
./gradlew :app:assembleDebug
```

On Windows:

```powershell
.\gradlew :app:assembleDebug
```

Install the APK from `app/build/outputs/apk/debug/` via Android Studio **Run** or `adb install`. On the head unit, grant **Notification access** to GMediaProxy, then open the app.

## Release signing

Signing is **not** committed with real secrets. The project loads an optional Gradle script so keystores stay outside VCS.

1. **Template**  
   Copy **`app/_secure.signing.gradle`** to **`app/secure.signing.gradle`** (or any path you prefer).

2. **Path**  
   Root **`gradle.properties`** already contains:
   - `secure.signing=secure.signing.gradle`  
   The signing script is resolved from the **`:app`** module directory, so the default path is **`app/secure.signing.gradle`**. Point this property to another relative or absolute path if needed.

3. **Fill in keystores**  
   The template defines **`signingConfigs`** for **`release`**: set `storeFile`, `storePassword`, `keyAlias`, and `keyPassword`.  
   Gradle **applies** this script only when `secure.signing` is set **and** the file exists (`app/build.gradle.kts`).

4. **Build types**  
   `release` builds use R8 shrink/obfuscation; signing from the script attaches to release variants once configured.

Without a valid `secure.signing.gradle`, release signing steps are skipped; use debug builds for local work.

## Release pipeline (`prepareRelease`)

The root task **`prepareRelease`** (defined in `app/build.gradle.kts`):

1. Runs **`assembleReleaseBuild`**, which assembles **`:app:assembleRelease`**.

Invoke:

```bash
./gradlew prepareRelease
```

Artifacts: **APK** under `app/build/outputs/apk/`, **AAB** under `app/build/outputs/bundle/`. Archive base name: **`1.2.1[1037]GMediaProxy`** (from `appVersionName` / `appVersionCode` in `app/build.gradle.kts`).

## Permissions and environment

The manifest requests **`INTERNET`**. The app registers a **notification listener** service and exports **`OnlineMusicService`** and **`OnlineMusicUpdateReceiver`**. **Notification access** must be granted in system settings — it is mandatory for MediaSession access.

Behavior is aimed at **Geely OneOS automotive IVI**, not a generic phone; a stock emulator may not expose the same binders or policies. Without notification access, **`MainActivity`** opens system notification-listener settings on launch and shows an in-app grant prompt until access is enabled; once granted, the settings UI is fully available.

## Naming

- **GMediaProxy** — product name (`rootProject.name` / archives base name).  
- **`GMediaProxyPub`** — public Git repository name.  
- **`com.geely.online.service`** — Android package (matches the OEM online-music service slot on the head unit).
