# CleanYourCar Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the ultra-lightweight Java-only `CleanYourCar` (`:app`, <150 KB, 0 `INTERNET`, `minSdk = 23`, Glassmorphism Bento UI), the companion `addons/nav-bridge` (`:navbridge`) BLE/Hotspot coordinate receiver, and the safe ADB debloat/install scripts (`tools/` & `examples/yt3518c-mt3561s/`) for the XYAuto `YT3518C` (`mt3561s`) Android 6.0 head unit.

**Architecture:** `:app` is a pure Android Framework SDK (`android.app.Activity`, `android.view.*`, `WindowManager`) launcher with zero runtime AndroidX/Material dependencies, implementing all 6 CarPlay screen states (Home Split Dashboard, Live Google Maps Overlay Mode, Collapsed Overlay Mode, 4×2 Paged App Grid + App Library, Offline 6-Char Plus Code Keypad, and Night Clock Screensaver with instant Reverse Gear camera yielding). `:navbridge` (`addons/nav-bridge`) is a separate tiny companion APK that receives destination coordinates over Bluetooth BLE GATT or iOS Shortcut Hotspot Burst and dispatches them to `com.cleanyourcar.launcher/.NavDispatchActivity`.

**Tech Stack:** Java 11, Android SDK (`minSdk = 23`, `targetSdk = 37`, `compileSdk = 37`), Gradle 9.6.0 + AGP 9.4.1 (Kotlin DSL `.gradle.kts`), JUnit 4 (`libs.junit`) for host JVM unit tests, POSIX shell scripts (`tools/*.sh`) for ADB automation.

**Spec:** `docs/superpowers/specs/2026-10-27-cleanyourcar-design.md`

## Global Constraints

- **Java-Only Application & Test Code:** Strictly Java 11 (`sourceCompatibility = JavaVersion.VERSION_11`, `targetCompatibility = JavaVersion.VERSION_11`). Zero `.kt` source files.
- **SDK Floor:** `minSdk = 23` (Android 6.0 Marshmallow, matching the physical `YT3518C` head unit's `ro.build.version.sdk = 23`).
- **Zero Runtime Dependencies in `:app`:** Do NOT enable `libs.appcompat` or `libs.material` in `app/build.gradle.kts`; enable only `testImplementation(libs.junit)` for local unit tests.
- **Zero `INTERNET` Permission in `:app`:** `app/src/main/AndroidManifest.xml` must never declare `android.permission.INTERNET`.
- **APK Size Gate:** `:app` release APK (`app/build/outputs/apk/release/app-release-unsigned.apk` or signed release APK) must be `< 524,288` bytes (512 KB), targeting `< 150 KB`.
- **Glassmorphism Design System Tokens (`.github/skills/glassmorphism/DESIGN.md`):**
  - `color_primary = #1856FF`, `color_secondary = #3A344E`, `color_success = #07CA6B`, `color_warning = #E89558`, `color_danger = #EA2143`, `text_primary = #FFFFFF`, `text_secondary = #B8C4E0`.
  - Screen geometry optimized for `1024x600 @ 160dpi` (`mdpi`, `1dp = 1px`).
- **Reverse Camera Safety Priority:** Every floating window in `OverlayHudService` must immediately hide (`View.GONE`) upon receiving any reverse/AV-in broadcast (`xy.android.mcu.backcar.in`, `xy.android.mcu.backcar.in2`, `xy.android.force.back.in`, `android.intent.action.START_ARM1_BACKCAR`, `xy.android.avin.come`).

## Review Focus

1. **Short Plus Code crossing grid boundaries or lowercase/whitespace input** (e.g., `" qmvx+2p "` or a code near a `20°` / `1°` cell edge relative to the reference city) -> `PlusCodeCodec.decode(input, refLat, refLon)` must normalize case/whitespace, recover the nearest valid global Open Location Code within `±0.5°` of `(refLat, refLon)`, and return `null` instead of throwing on malformed strings.
2. **Malformed or URL-wrapped coordinates sent from iPhone over BLE / Hotspot Burst** (e.g., `"https://www.google.com/maps/place/.../@10.7769,106.7009,17z"`, `"geo:10.7769,106.7009?q=10.7769,106.7009(Ben+Thanh)"`, `"QMVX+2P"`, or garbage `""`) -> `NavPayloadParser.parse(raw, refLat, refLon)` must extract valid `[-90..90, -180..180]` coordinates or decode a short/full Plus Code, and reject out-of-range/garbage inputs cleanly.
3. **XYAuto music broadcast variations & missing extras** (`update.widget.songname` vs `update.widget.all` with null/empty `"songname"` or `"artist"`, or rapid source toggling between `BT` and `USB`) -> `HeadUnitMediaBridge` must preserve the last non-empty track title or fall back to `"Sẵn sàng phát nhạc"`, and map `update.widget.playbtnstate` integer/boolean/string extras without crashing.
4. **Reverse gear triggered while Live Google Maps Overlay (`OverlayHudService`) is visible or collapsed** -> `OverlayHudService.shouldHideForCarEvent(action)` must return `true` for all 5 reverse/AV-in actions and `false` for `xy.android.mcu.backcar.out` / `com.autochips.avin.quit` / `xy.android.avin.leave`, restoring the exact prior overlay state (State 2 Expanded vs State 3 Collapsed).
5. **Protected head-unit packages accidentally placed in a debloat list** (e.g., `com.autochips.android.backcar` or `com.xygala.canbus`) -> `tools/disable.sh` must refuse to run and exit non-zero before disabling a single package if any protected package appears in the input file.

---

### Task 1: Project Configuration (`minSdk = 23`, Pure Android Theme, Glassmorphism Tokens & Optional Wallpaper Loader)

**Files:**
- Modify: `app/build.gradle.kts`
- Modify: `app/src/main/res/values/colors.xml`
- Modify: `app/src/main/res/values/strings.xml`
- Modify: `app/src/main/res/values/themes.xml`
- Modify: `app/src/main/res/values-night/themes.xml`
- Create: `app/src/main/res/values/config.xml`
- Create: `app/src/main/java/com/minimal/carplaylauncher/ThemeConfig.java`
- Modify: `app/src/test/java/com/minimal/carplaylauncher/ExampleUnitTest.java` -> Replace with `ThemeConfigTest.java`

**Interfaces:**
- Produces:
  - `com.cleanyourcar.launcher.ThemeConfig`:
    - `public static final int COLOR_PRIMARY = 0xFF1856FF;`
    - `public static final int COLOR_SECONDARY = 0xFF3A344E;`
    - `public static final int COLOR_SUCCESS = 0xFF07CA6B;`
    - `public static final int COLOR_WARNING = 0xFFE89558;`
    - `public static final int COLOR_DANGER = 0xFFEA2143;`
    - `public static final int COLOR_TEXT_PRIMARY = 0xFFFFFFFF;`
    - `public static final int COLOR_TEXT_SECONDARY = 0xFFB8C4E0;`
    - `public static int calculateInSampleSize(int rawWidth, int rawHeight, int reqWidth, int reqHeight)`
    - `public static Drawable createGlassCardDrawable(float radiusPx, int fillTopColor, int fillBottomColor, int strokeColor, int strokeWidthPx)`
    - `public static Drawable loadCanvasBackground(Context context)` (loads `/sdcard/carplay_wallpaper.jpg` or `.webp` in `RGB_565` downsampled to `1024x600` with `#8005070E` scrim if present; otherwise returns the zero-RAM Aurora Glass radial/linear `GradientDrawable`).

- [ ] **Step 1: Write the failing unit test `app/src/test/java/com/minimal/carplaylauncher/ThemeConfigTest.java`**

```java
package com.cleanyourcar.launcher;

import org.junit.Test;
import static org.junit.Assert.*;

public class ThemeConfigTest {
    @Test
    public void glassmorphismTokens_matchDesignSpec() {
        assertEquals(0xFF1856FF, ThemeConfig.COLOR_PRIMARY);
        assertEquals(0xFF3A344E, ThemeConfig.COLOR_SECONDARY);
        assertEquals(0xFF07CA6B, ThemeConfig.COLOR_SUCCESS);
        assertEquals(0xFFE89558, ThemeConfig.COLOR_WARNING);
        assertEquals(0xFFEA2143, ThemeConfig.COLOR_DANGER);
        assertEquals(0xFFFFFFFF, ThemeConfig.COLOR_TEXT_PRIMARY);
        assertEquals(0xFFB8C4E0, ThemeConfig.COLOR_TEXT_SECONDARY);
    }

    @Test
    public void calculateInSampleSize_downsamples4kWallpaperFor1024x600() {
        assertEquals(1, ThemeConfig.calculateInSampleSize(1024, 600, 1024, 600));
        assertEquals(2, ThemeConfig.calculateInSampleSize(2560, 1440, 1024, 600));
        assertEquals(4, ThemeConfig.calculateInSampleSize(4096, 2400, 1024, 600));
    }
}
```

- [ ] **Step 2: Update `app/build.gradle.kts` and Android XML resources so unit tests can compile without Material/AppCompat**

In `app/build.gradle.kts`:
- Change `minSdk = 26` to `minSdk = 23`.
- Uncomment `testImplementation(libs.junit)` while keeping `appcompat` and `material` commented out.
- In `res/values/themes.xml` and `res/values-night/themes.xml`, change parent from `Theme.MaterialComponents.DayNight.DarkActionBar` to `android:Theme.Material.NoActionBar` with `<item name="android:windowBackground">@color/bg_canvas</item>` and `<item name="android:windowFullscreen">true</item>`.
- Define Glassmorphism colors in `res/values/colors.xml`, Vietnamese UI labels in `res/values/strings.xml`, and default dock/grid config in `res/values/config.xml`.

- [ ] **Step 3: Run test to verify it fails before `ThemeConfig.java` exists**

Run: `./gradlew :app:testDebugUnitTest --tests "com.cleanyourcar.launcher.ThemeConfigTest"`
Expected: FAIL with compilation error (`cannot find symbol class ThemeConfig`).

- [ ] **Step 4: Implement `ThemeConfig.java` and delete `ExampleUnitTest.java`**

Implement `com.cleanyourcar.launcher.ThemeConfig` with the Glassmorphism constants, power-of-two `calculateInSampleSize`, programmatic `GradientDrawable` builders for Bento glass cards/buttons/pills, and `RGB_565` optional `/sdcard/carplay_wallpaper.*` loader.

- [ ] **Step 5: Run unit test and assemble debug APK to verify resource linking passes without AppCompat/Material**

Run: `./gradlew :app:testDebugUnitTest --tests "com.cleanyourcar.launcher.ThemeConfigTest" :app:assembleDebug`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 6: Commit**

```bash
git add app/build.gradle.kts app/src/main/res/ app/src/main/java/com/minimal/carplaylauncher/ThemeConfig.java app/src/test/java/com/minimal/carplaylauncher/
git commit -m "feat(theme): configure minSdk 23 and pure SDK glassmorphism tokens"
```

---

### Task 2: Pure-Math Offline Plus Code Decoder & Navigation Payload Parser (`PlusCodeCodec` & `NavPayloadParser`)

**Files:**
- Create: `app/src/main/java/com/minimal/carplaylauncher/PlusCodeCodec.java`
- Create: `app/src/main/java/com/minimal/carplaylauncher/NavPayloadParser.java`
- Test: `app/src/test/java/com/minimal/carplaylauncher/PlusCodeCodecTest.java`

**Interfaces:**
- Produces:
  - `com.cleanyourcar.launcher.PlusCodeCodec`:
    - `public static final String ALPHABET = "23456789CFGHJMPQRVWX";`
    - `public static final class LatLon { public final double lat; public final double lon; public final String label; public LatLon(double lat, double lon, String label) }`
    - `public static final class CityPreset { public final String name; public final double lat; public final double lon; }`
    - `public static final CityPreset[] VIETNAM_PRESETS` (TP.HCM `10.7769, 106.7009`, Hà Nội `21.0285, 105.8542`, Đà Nẵng `16.0544, 108.2022`, Cần Thơ `10.0452, 105.7469`, Nha Trang `12.2388, 109.1967`, Đà Lạt `11.9404, 108.4583`, Vũng Tàu `10.3460, 107.0843`)
    - `public static boolean isValidCode(String rawCode)`
    - `public static LatLon decode(String rawCode, double refLat, double refLon)` (decodes both full codes like `"7P28QMVX+2P"` and short 6/7-char codes like `"QMVX+2P"` recovered near `refLat, refLon`; returns `null` if invalid).
  - `com.cleanyourcar.launcher.NavPayloadParser`:
    - `public static PlusCodeCodec.LatLon parse(String rawPayload, double refLat, double refLon)` (parses raw `"lat,lon"`, `"geo:lat,lon?q=..."`, Google Maps URLs containing `@lat,lon` or `q=lat,lon`, or Plus Codes).

- [ ] **Step 1: Write the failing test `app/src/test/java/com/minimal/carplaylauncher/PlusCodeCodecTest.java`**

```java
package com.cleanyourcar.launcher;

import org.junit.Test;
import static org.junit.Assert.*;

public class PlusCodeCodecTest {
    @Test
    public void decode_fullOpenLocationCode_returnsAccurateCoordinates() {
        PlusCodeCodec.LatLon pos = PlusCodeCodec.decode("7P28QMVX+2P", 10.7769, 106.7009);
        assertNotNull(pos);
        assertEquals(10.7925625, pos.lat, 0.001);
        assertEquals(106.6993125, pos.lon, 0.001);
    }

    @Test
    public void decode_short6CharCode_recoversUsingReferenceCityAndHandlesWhitespaceCase() {
        PlusCodeCodec.LatLon pos = PlusCodeCodec.decode(" qmvx+2p ", 10.7769, 106.7009);
        assertNotNull(pos);
        assertEquals(10.7925625, pos.lat, 0.001);
        assertEquals(106.6993125, pos.lon, 0.001);
    }

    @Test
    public void decode_invalidCode_returnsNullWithoutThrowing() {
        assertNull(PlusCodeCodec.decode("", 10.7769, 106.7009));
        assertNull(PlusCodeCodec.decode("INVALID!", 10.7769, 106.7009));
        assertNull(PlusCodeCodec.decode("1111+11", 10.7769, 106.7009));
    }

    @Test
    public void navPayloadParser_handlesRawCoordsGeoUriGoogleMapsUrlAndPlusCode() {
        PlusCodeCodec.LatLon raw = NavPayloadParser.parse("10.7721, 106.6983", 10.7769, 106.7009);
        assertNotNull(raw);
        assertEquals(10.7721, raw.lat, 0.0001);
        assertEquals(106.6983, raw.lon, 0.0001);

        PlusCodeCodec.LatLon url = NavPayloadParser.parse(
                "https://www.google.com/maps/place/Ben+Thanh/@10.7725,106.6980,17z/data=!3m1",
                10.7769, 106.7009);
        assertNotNull(url);
        assertEquals(10.7725, url.lat, 0.0001);
        assertEquals(106.6980, url.lon, 0.0001);

        PlusCodeCodec.LatLon plus = NavPayloadParser.parse("QMVX+2P", 10.7769, 106.7009);
        assertNotNull(plus);
        assertEquals(10.7925625, plus.lat, 0.001);

        assertNull(NavPayloadParser.parse("999.0,999.0", 10.7769, 106.7009));
        assertNull(NavPayloadParser.parse("not-a-location", 10.7769, 106.7009));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.cleanyourcar.launcher.PlusCodeCodecTest"`
Expected: FAIL (`cannot find symbol class PlusCodeCodec`).

- [ ] **Step 3: Implement `PlusCodeCodec.java` and `NavPayloadParser.java`**

In `PlusCodeCodec.java`, implement the standard base-20 Open Location Code recovery + decoding math:
- Full codes have 8 digits before `+` (place values `20.0`, `1.0`, `0.05`, `0.0025` degrees) and 2+ digits after `+` (`0.000125` degrees for pair 5), shifted by `-90.0` lat and `-180.0` lon, returning the center of the decoded cell.
- Short codes with 4 digits before `+` (e.g., `QMVX+2P`) recover the prefix by rounding `refLat + 90.0` and `refLon + 180.0` to the nearest `1.0°` grid square and adjusting by `±1.0°` if the decoded center is more than `0.5°` away from `refLat` / `refLon`.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.cleanyourcar.launcher.PlusCodeCodecTest"`
Expected: PASS (4 tests passed).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/minimal/carplaylauncher/PlusCodeCodec.java app/src/main/java/com/minimal/carplaylauncher/NavPayloadParser.java app/src/test/java/com/minimal/carplaylauncher/PlusCodeCodecTest.java
git commit -m "feat(nav): add offline Plus Code math decoder and payload parser"
```

---

### Task 3: XYAuto Head-Unit Media Bridge, Car Intent Router & App Repository (`HeadUnitMediaBridge`, `CarIntentRouter`, `AppRepository`)

**Files:**
- Create: `app/src/main/java/com/minimal/carplaylauncher/HeadUnitMediaBridge.java`
- Create: `app/src/main/java/com/minimal/carplaylauncher/CarIntentRouter.java`
- Create: `app/src/main/java/com/minimal/carplaylauncher/AppRepository.java`
- Test: `app/src/test/java/com/minimal/carplaylauncher/HeadUnitMediaBridgeTest.java`
- Test: `app/src/test/java/com/minimal/carplaylauncher/AppRepositoryOrderTest.java`

**Interfaces:**
- Produces:
  - `com.cleanyourcar.launcher.HeadUnitMediaBridge`:
    - `public static final int SOURCE_BT = 0;`
    - `public static final int SOURCE_USB = 1;`
    - `public static final String ACTION_WIDGET_SONGNAME = "update.widget.songname";`
    - `public static final String ACTION_WIDGET_ALL = "update.widget.all";`
    - `public static final String ACTION_WIDGET_PLAYSTATE = "update.widget.playbtnstate";`
    - `public static final String ACTION_XY_PLAY_PAUSE = "xy.android.playpause";`
    - `public static final String ACTION_XY_NEXT = "xy.android.nextmedia";`
    - `public static final String ACTION_XY_PREV = "xy.android.previousmedia";`
    - `public static final String ACTION_XY_SOURCE_BT = "xy.android.bluetooth";`
    - `public static final String ACTION_XY_SOURCE_USB = "xy.android.music";`
    - `public interface Listener { void onMediaStateChanged(MediaState state); }`
    - `public static final class MediaState { public final int activeSource; public final String title; public final String subtitle; public final boolean playing; public final boolean btConnected; }`
    - `public static MediaState applyBroadcast(MediaState current, String action, Map<String, Object> extras)` (pure state transition testable on JVM)
    - `public static boolean isReverseEnterAction(String action)` and `public static boolean isReverseExitAction(String action)`
    - `public void setSource(Context context, int source)`
    - `public void sendPlayPause(Context context)`, `public void sendNext(Context context)`, `public void sendPrevious(Context context)`
  - `com.cleanyourcar.launcher.CarIntentRouter`:
    - Package constants for `YT3518C`:
      - `PKG_GOOGLE_MAPS = "com.google.android.apps.maps"`
      - `PKG_BT_MUSIC = "com.autochips.bluetooth"` (`CLS_BT_MAIN = "com.autochips.bluetooth.MainBluetoothActivity"`)
      - `PKG_USB_MUSIC = "com.acloud.stub.localmusic"` (`CLS_USB_MAIN = "com.acloud.stub.localmusic.QtActivity"`)
      - `PKG_DASHCAM = "com.fvsm.camera"` (`CLS_DASHCAM_MAIN = "com.fvsm.camera.MainActivity"`)
      - `PKG_EQUALIZER = "com.xyauto.Settings"` (`ACTION_XY_APS = "android.intent.action.xyaps"`)
      - `PKG_RADIO = "com.acloud.stub.localradio"`
    - `public static void launchGoogleMapsLive(Context context)`
    - `public static void launchCoordinatesInMapsLive(Context context, double lat, double lon, String label)`
    - `public static void launchDashcam(Context context)`
    - `public static void launchEqualizer(Context context)`
    - `public static void launchMusicSourceApp(Context context, int source)`
    - `public static int cycleBrightness(Context context)`
  - `com.cleanyourcar.launcher.AppRepository`:
    - `public static final class AppEntry { public final String packageName; public final String className; public final String label; public final Drawable icon; public final boolean hidden; }`
    - `public static List<String> orderAndFilterPackages(List<String> installedPkgs, List<String> savedOrder, Set<String> hiddenPkgs, String selfPkg)` (pure helper testable on JVM)
    - `public List<AppEntry> loadVisibleApps(Context context)` / `public List<AppEntry> loadAllApps(Context context)`
    - `public void toggleHidden(Context context, String packageName)` / `public void moveApp(Context context, String packageName, int direction)`

- [ ] **Step 1: Write the failing unit tests `HeadUnitMediaBridgeTest.java` and `AppRepositoryOrderTest.java`**

```java
package com.cleanyourcar.launcher;

import org.junit.Test;
import java.util.HashMap;
import java.util.Map;
import static org.junit.Assert.*;

public class HeadUnitMediaBridgeTest {
    @Test
    public void applyBroadcast_updatesSongTitleArtistAndPlayStateWithoutCrashingOnMissingExtras() {
        HeadUnitMediaBridge.MediaState initial = HeadUnitMediaBridge.MediaState.initial();
        Map<String, Object> extras = new HashMap<>();
        extras.put("songname", "Chạy Ngay Đi");
        extras.put("artist", "Sơn Tùng M-TP");

        HeadUnitMediaBridge.MediaState updated = HeadUnitMediaBridge.applyBroadcast(
                initial, HeadUnitMediaBridge.ACTION_WIDGET_SONGNAME, extras);
        assertEquals("Chạy Ngay Đi", updated.title);
        assertTrue(updated.subtitle.contains("Sơn Tùng M-TP"));

        // Empty songname broadcast must preserve last valid title
        Map<String, Object> emptyExtras = new HashMap<>();
        HeadUnitMediaBridge.MediaState preserved = HeadUnitMediaBridge.applyBroadcast(
                updated, HeadUnitMediaBridge.ACTION_WIDGET_SONGNAME, emptyExtras);
        assertEquals("Chạy Ngay Đi", preserved.title);

        Map<String, Object> playExtras = new HashMap<>();
        playExtras.put("playstate", 1);
        HeadUnitMediaBridge.MediaState playing = HeadUnitMediaBridge.applyBroadcast(
                preserved, HeadUnitMediaBridge.ACTION_WIDGET_PLAYSTATE, playExtras);
        assertTrue(playing.playing);
    }

    @Test
    public void reverseGearActions_areRecognizedAccurately() {
        assertTrue(HeadUnitMediaBridge.isReverseEnterAction("xy.android.mcu.backcar.in"));
        assertTrue(HeadUnitMediaBridge.isReverseEnterAction("xy.android.mcu.backcar.in2"));
        assertTrue(HeadUnitMediaBridge.isReverseEnterAction("xy.android.force.back.in"));
        assertTrue(HeadUnitMediaBridge.isReverseEnterAction("android.intent.action.START_ARM1_BACKCAR"));
        assertTrue(HeadUnitMediaBridge.isReverseEnterAction("xy.android.avin.come"));

        assertTrue(HeadUnitMediaBridge.isReverseExitAction("xy.android.mcu.backcar.out"));
        assertTrue(HeadUnitMediaBridge.isReverseExitAction("com.autochips.avin.quit"));
        assertFalse(HeadUnitMediaBridge.isReverseEnterAction("xy.android.mcu.backcar.out"));
    }
}
```

And in `AppRepositoryOrderTest.java`:
```java
package com.cleanyourcar.launcher;

import org.junit.Test;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import static org.junit.Assert.*;

public class AppRepositoryOrderTest {
    @Test
    public void orderAndFilterPackages_respectsSavedOrderExcludesHiddenAndSelf() {
        List<String> installed = Arrays.asList(
                "com.cleanyourcar.launcher",
                "com.android.settings",
                "com.google.android.apps.maps",
                "com.fvsm.camera",
                "com.acloud.stub.localmusic");
        List<String> savedOrder = Arrays.asList(
                "com.google.android.apps.maps",
                "com.acloud.stub.localmusic");
        HashSet<String> hidden = new HashSet<>(Collections.singletonList("com.android.settings"));

        List<String> result = AppRepository.orderAndFilterPackages(
                installed, savedOrder, hidden, "com.cleanyourcar.launcher");

        assertEquals(Arrays.asList(
                "com.google.android.apps.maps",
                "com.acloud.stub.localmusic",
                "com.fvsm.camera"), result);
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests "com.cleanyourcar.launcher.HeadUnitMediaBridgeTest" --tests "com.cleanyourcar.launcher.AppRepositoryOrderTest"`
Expected: FAIL (`cannot find symbol`).

- [ ] **Step 3: Implement `HeadUnitMediaBridge.java`, `CarIntentRouter.java`, and `AppRepository.java`**

Implement all three classes with clean fallbacks when running on a standard Android device/emulator (e.g., if `com.xyauto.Settings` is not installed, `launchEqualizer` falls back to `android.media.action.DISPLAY_AUDIO_EFFECT_CONTROL_PANEL` or `Settings.ACTION_SOUND_SETTINGS`).

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :app:testDebugUnitTest`
Expected: PASS (all unit tests green).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/minimal/carplaylauncher/ app/src/test/java/com/minimal/carplaylauncher/
git commit -m "feat(core): add XYAuto media bridge, car intent router, and app repository"
```

---

### Task 4: All 6 CarPlay UI States & Manifest Registration (`MainActivity`, `OverlayHudService`, `ClockActivity`, `NavDispatchActivity`, `PlusCodeDialog`, `AppLibraryDialog`)

**Files:**
- Create: `app/src/main/java/com/minimal/carplaylauncher/PlusCodeDialog.java`
- Create: `app/src/main/java/com/minimal/carplaylauncher/AppLibraryDialog.java`
- Create: `app/src/main/java/com/minimal/carplaylauncher/ClockActivity.java`
- Create: `app/src/main/java/com/minimal/carplaylauncher/OverlayHudService.java`
- Create: `app/src/main/java/com/minimal/carplaylauncher/NavDispatchActivity.java`
- Create: `app/src/main/java/com/minimal/carplaylauncher/MainActivity.java`
- Modify: `app/src/main/AndroidManifest.xml`
- Modify: `app/src/main/keepRules/rules.keep`

**Interfaces:**
- Consumes: `ThemeConfig`, `PlusCodeCodec`, `NavPayloadParser`, `HeadUnitMediaBridge`, `CarIntentRouter`, `AppRepository`.
- Produces:
  - `MainActivity` (`MAIN` + `HOME` + `DEFAULT` + `LAUNCHER`, landscape, `singleTask`):
    - Persistent Left CarPlay Dock (`76dp` width): Live clock (`HH:mm`), connection badge, 4 squircle buttons (`🗺️ Maps`, `🎵 Music`, `📷 Cam Trước`, `🎚️ Equalizer`), bottom page toggle `[ ⊞ ]`.
    - **State 1 (Dashboard View):** Left Glass Bento Music Card (top segmented pill `[ 📱 Nhạc Bluetooth | 🔌 Nhạc USB ]`, track title & subtitle, transport bar `⏮ ⏯ ⏭`); Right Top Navigation Bento Card (Vietnamese date, `[ 🗺️ Maps Live ]`, `[ 🔢 Mã 6 ký tự ]`); Right Bottom Car Utility Bento Card (`2×2`: `📷 Cam Trước`, `🎚️ Equalizer`, `🔆 Độ sáng`, `🌙 Màn chờ`).
    - **State 4 (App Grid View):** Paged `4×2` grid of visible apps + `[ ➕ Thư viện App ]` tile opening `AppLibraryDialog`.
  - `OverlayHudService`:
    - **State 2 (Live Google Maps Expanded):** Left Dock overlay window (`76dp × match_parent`) + Right Music-Only overlay window (`260dp × match_parent`, containing `[Thu gọn ❯]`, `[ 📱 BT | 🔌 USB ]`, track info, `⏮ ⏯ ⏭`, and `[ 🔢 Nhập mã 6 ký tự ]`).
    - **State 3 (Live Google Maps Collapsed):** Collapses right panel into a top-right floating pill (`[ 🎵 ⏮ ⏯ ⏭ | ❮ Mở khung ]`).
    - Instant hide on any `HeadUnitMediaBridge.isReverseEnterAction(action)` and restore on `isReverseExitAction(action)`.
  - `PlusCodeDialog` (**State 5**): 20-key Plus Code keypad (`23456789CFGHJMPQRVWX` + `+` + `⌫`), city preset selector (`PlusCodeCodec.VIETNAM_PRESETS`), and `[ 🧭 CHỈ ĐƯỜNG NGAY ]` CTA.
  - `ClockActivity` (**State 6**): Fullscreen `#000000` night clock with date and current track subtitle; single tap anywhere exits.
  - `NavDispatchActivity`: Exported activity handling `com.cleanyourcar.launcher.action.NAVIGATE` (with `lat`/`lon` or `q`/`payload` extras from `addons/nav-bridge`) and `geo:` intents -> launches Google Maps + `OverlayHudService`.

- [ ] **Step 1: Implement `PlusCodeDialog.java`, `AppLibraryDialog.java`, and `ClockActivity.java`**

Build programmatic Glassmorphism UI views using `ThemeConfig` drawables so zero heavy XML inflation or third-party libraries are needed.

- [ ] **Step 2: Implement `OverlayHudService.java` and `NavDispatchActivity.java`**

In `OverlayHudService.java`:
- Use `WindowManager.LayoutParams.TYPE_PHONE` (on API <= 25) and `TYPE_APPLICATION_OVERLAY` (on API >= 26) with `FLAG_NOT_FOCUSABLE | FLAG_LAYOUT_IN_SCREEN` and `PixelFormat.TRANSLUCENT`.
- Guard `windowManager.addView(...)` with `Settings.canDrawOverlays(this)` (and catch `SecurityException` gracefully if `appops` hasn't been granted yet).
- Register reverse-gear and media broadcast receivers in `onCreate` and unregister in `onDestroy`.

- [ ] **Step 3: Implement `MainActivity.java` and register all components in `app/src/main/AndroidManifest.xml` & `app/src/main/keepRules/rules.keep`**

Ensure `app/src/main/AndroidManifest.xml` declares:
- `android.permission.SYSTEM_ALERT_WINDOW`
- `android.permission.BLUETOOTH`
- `android.permission.ACCESS_WIFI_STATE`
- `android.permission.WRITE_SETTINGS`
- **NO `android.permission.INTERNET`**
- `<queries>` block for Android 11+ compatibility (`MAIN`/`LAUNCHER`, `com.google.android.apps.maps`, `com.autochips.bluetooth`, `com.acloud.stub.localmusic`, `com.fvsm.camera`, `com.xyauto.Settings`).

- [ ] **Step 4: Build debug & release APKs, run lint & unit tests, and verify `:app` APK size < 150 KB**

Run: `./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleRelease`
Expected: `BUILD SUCCESSFUL`, zero lint errors, and `stat -f %z app/build/outputs/apk/release/*.apk` under `153,600` bytes (150 KB).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/
git commit -m "feat(launcher): implement 6-state CarPlay Glassmorphism UI and live Maps overlay"
```

---

### Task 5: Companion Add-on `addons/nav-bridge` (BLE GATT Server & Auto-Burst Hotspot Receiver)

**Files:**
- Create: `addons/nav-bridge/build.gradle.kts`
- Create: `addons/nav-bridge/src/main/AndroidManifest.xml`
- Create: `addons/nav-bridge/src/main/java/com/minimal/carplaylauncher/navbridge/NavBridgeProtocol.java`
- Create: `addons/nav-bridge/src/main/java/com/minimal/carplaylauncher/navbridge/BleGattNavServer.java`
- Create: `addons/nav-bridge/src/main/java/com/minimal/carplaylauncher/navbridge/HotspotBurstServer.java`
- Create: `addons/nav-bridge/src/main/java/com/minimal/carplaylauncher/navbridge/NavBridgeService.java`
- Create: `addons/nav-bridge/src/main/java/com/minimal/carplaylauncher/navbridge/BootReceiver.java`
- Create: `addons/nav-bridge/src/test/java/com/minimal/carplaylauncher/navbridge/NavBridgeProtocolTest.java`
- Modify: `settings.gradle.kts` (include `:addons:nav-bridge` or project dir mapping)

**Interfaces:**
- Produces:
  - `com.cleanyourcar.navbridge.NavBridgeProtocol`:
    - `public static final UUID SERVICE_UUID = UUID.fromString("0000c910-0000-1000-8000-00805f9b34fb");`
    - `public static final UUID CHAR_NAV_UUID = UUID.fromString("0000c911-0000-1000-8000-00805f9b34fb");`
    - `public static final int PORT = 48920;`
    - `public static final String TARGET_PKG = "com.cleanyourcar.launcher";`
    - `public static final String TARGET_ACT = "com.cleanyourcar.launcher.NavDispatchActivity";`
    - `public static final String ACTION_NAVIGATE = "com.cleanyourcar.launcher.action.NAVIGATE";`
    - `public static String extractQueryFromHttpOrRaw(String requestOrDatagram)` (extracts payload from `GET /nav?q=... HTTP/1.1`, `POST` body, or raw UDP/BLE text).

- [ ] **Step 1: Register `:navbridge` (`project(":navbridge").projectDir = file("addons/nav-bridge")`) in `settings.gradle.kts` and create `addons/nav-bridge/build.gradle.kts` + failing test `NavBridgeProtocolTest.java`**

```java
package com.cleanyourcar.navbridge;

import org.junit.Test;
import static org.junit.Assert.*;

public class NavBridgeProtocolTest {
    @Test
    public void extractQueryFromHttpOrRaw_parsesHttpGetUrlEncodedAndRawBleStrings() {
        assertEquals("10.7769,106.7009",
                NavBridgeProtocol.extractQueryFromHttpOrRaw("GET /nav?q=10.7769%2C106.7009 HTTP/1.1\r\nHost: 172.20.10.2\r\n\r\n"));
        assertEquals("QMVX+2P",
                NavBridgeProtocol.extractQueryFromHttpOrRaw("GET /nav?q=QMVX%2B2P HTTP/1.1\r\n\r\n"));
        assertEquals("10.7769,106.7009",
                NavBridgeProtocol.extractQueryFromHttpOrRaw("  10.7769,106.7009 \n"));
    }
}
```

- [ ] **Step 2: Run `:navbridge:testDebugUnitTest` to verify it fails**

Run: `./gradlew :navbridge:testDebugUnitTest --tests "com.cleanyourcar.navbridge.NavBridgeProtocolTest"`
Expected: FAIL (`cannot find symbol class NavBridgeProtocol`).

- [ ] **Step 3: Implement `NavBridgeProtocol.java`, `BleGattNavServer.java`, `HotspotBurstServer.java`, `NavBridgeService.java`, `BootReceiver.java`, and `AndroidManifest.xml`**

In `HotspotBurstServer.java`:
- Listen on UDP port `48920` and TCP HTTP `127.0.0.1` / `0.0.0.0:48920`, replying `HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nConnection: close\r\n\r\n{"status":"ok"}` and immediately firing `ACTION_NAVIGATE` to `com.cleanyourcar.launcher/.NavDispatchActivity`.
In `BleGattNavServer.java`:
- Open a `BluetoothGattServer` with `SERVICE_UUID` and writable `CHAR_NAV_UUID`, advertising `CarPlayNav` when BLE advertising is supported by the chipset, and forwarding written UTF-8 chunks to `NavBridgeService`.

- [ ] **Step 4: Run tests, lint, and assemble `:navbridge`**

Run: `./gradlew :navbridge:testDebugUnitTest :navbridge:lintDebug :navbridge:assembleDebug :navbridge:assembleRelease`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Commit**

```bash
git add settings.gradle.kts addons/nav-bridge/
git commit -m "feat(navbridge): add BLE GATT and Hotspot Burst companion add-on"
```

---

### Task 6: Safe ADB Tooling (`tools/`) & Verified `YT3518C` Debloat Profile (`examples/yt3518c-mt3561s/`)

**Files:**
- Create: `examples/yt3518c-mt3561s/debloat.txt`
- Create: `examples/yt3518c-mt3561s/README.md`
- Create: `tools/disable.sh`
- Create: `tools/restore.sh`
- Create: `tools/build-and-install.sh`
- Create: `docs/ios-shortcut-setup.md`
- Test: `app/src/test/java/com/minimal/carplaylauncher/DebloatSafetyTest.java`

**Interfaces:**
- Produces:
  - `examples/yt3518c-mt3561s/debloat.txt`: Commented, categorized list of safe bloatware packages for `YT3518C_00008_V008` (`mt3561s`).
  - `tools/disable.sh`: Validates the input debloat list against a hardcoded protected-package list (refusing to run if any protected package is present), records disabled packages to `~/.cleanyourcar/disabled-<timestamp>.txt`, and runs `pm disable-user <pkg>` (compatible with Android 6.0 API 23).
  - `tools/restore.sh`: Re-enables all packages from a backup list or `debloat.txt` via `pm enable <pkg>` and restores `com.android.launcher3` if requested.
  - `tools/build-and-install.sh`: Builds `:app` and `:navbridge`, enforces the `< 524,288` byte APK size gate, installs both APKs over ADB, grants `SYSTEM_ALERT_WINDOW` via `appops`, starts `MainActivity` and `NavBridgeService`, and verifies `MainActivity` is focused before optionally disabling stock `com.android.launcher3`.

- [ ] **Step 1: Write the failing test `app/src/test/java/com/minimal/carplaylauncher/DebloatSafetyTest.java`**

```java
package com.cleanyourcar.launcher;

import org.junit.Test;
import java.io.File;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import static org.junit.Assert.*;

public class DebloatSafetyTest {
    private static final Set<String> PROTECTED_PACKAGES = new HashSet<>(Arrays.asList(
            "android",
            "com.android.systemui",
            "com.android.settings",
            "com.android.phone",
            "com.android.server.telecom",
            "com.android.packageinstaller",
            "com.android.defcontainer",
            "com.android.inputmethod.latin",
            "com.android.shell",
            "com.android.providers.settings",
            "com.android.providers.media",
            "com.android.externalstorage",
            "com.android.location.fused",
            "com.autochips.android.backcar",
            "com.autochips.bluetooth",
            "com.android.bluetooth",
            "com.xygala.canbus",
            "com.xygala.pvcanset",
            "com.xy.spl",
            "com.xy.brakecheck",
            "com.xyauto.Settings",
            "com.fvsm.camera",
            "com.acloud.stub.localmusic",
            "com.acloud.stub.localradio",
            "com.mediatek.usbchecker",
            "com.google.android.apps.maps",
            "com.google.android.gms"
    ));

    @Test
    public void yt3518cDebloatList_neverContainsProtectedCarOrSystemPackages() throws Exception {
        File debloatFile = new File("../examples/yt3518c-mt3561s/debloat.txt");
        if (!debloatFile.exists()) {
            debloatFile = new File("examples/yt3518c-mt3561s/debloat.txt");
        }
        assertTrue("debloat.txt must exist", debloatFile.exists());

        List<String> lines = Files.readAllLines(debloatFile.toPath());
        int activeCount = 0;
        for (String raw : lines) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            activeCount++;
            assertFalse("Protected package must NEVER be in debloat.txt: " + line,
                    PROTECTED_PACKAGES.contains(line));
        }
        assertTrue("debloat.txt should contain at least 15 bloatware packages", activeCount >= 15);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.cleanyourcar.launcher.DebloatSafetyTest"`
Expected: FAIL (`debloat.txt must exist`).

- [ ] **Step 3: Create `examples/yt3518c-mt3561s/debloat.txt`, `examples/yt3518c-mt3561s/README.md`, `tools/disable.sh`, `tools/restore.sh`, `tools/build-and-install.sh`, and `docs/ios-shortcut-setup.md`**

Make `tools/*.sh` executable (`chmod +x tools/*.sh`) and validate syntax with `bash -n tools/*.sh` plus a dry-run test of `tools/disable.sh` against a temporary file containing `com.autochips.android.backcar` to prove the safety guard rejects it.

- [ ] **Step 4: Run full verification suite (`testDebugUnitTest`, `lintDebug`, `assembleRelease`, shell syntax & guard check)**

Run: `./gradlew :app:testDebugUnitTest :navbridge:testDebugUnitTest :app:lintDebug :navbridge:lintDebug :app:assembleRelease :navbridge:assembleRelease && bash -n tools/*.sh`
Expected: `BUILD SUCCESSFUL`, all tests pass, zero lint errors.

- [ ] **Step 5: Commit**

```bash
git add examples/ tools/ docs/ app/src/test/java/com/minimal/carplaylauncher/DebloatSafetyTest.java
git commit -m "feat(tools): add YT3518C safe debloat list, ADB scripts, and safety tests"
```
