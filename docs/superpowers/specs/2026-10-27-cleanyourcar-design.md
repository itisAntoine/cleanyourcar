# CleanYourCar Design Specification

- **Date:** 2026-10-27
- **Target Device Reference:** XYAuto `YT3518C_00008_V008` (`MediaTek/AutoChips mt3561s`, `arm64-v8a`, `1024x600 @ 160dpi`, `2 GB RAM`, **Android 6.0 / API 23** reported as `"8.0"`)
- **Inspiration & Philosophy:** [`Gathin23/cleanyourtv`](https://github.com/Gathin23/cleanyourtv) adapted for an Apple CarPlay-style automotive head unit experience.

---

## 1. Intent & Why

Aftermarket Android car head units (such as the user's `YT3518C` / `mt3561s` board) suffer from severe memory pressure (~460 MB free RAM out of 2 GB), slow boot times, delayed Bluetooth A2DP connection, and cluttered, small-touch-target stock launchers (`com.android.launcher3` + background weather/OTA/sync bloatware).

Meanwhile, the user and their family have very focused daily driving habits:
1. **Speed & traffic camera alerts** are already handled by a dedicated hardware **VietMap HUD** mounted behind the steering wheel — the head unit does **not** need background speed/traffic overlays or heavy widgets.
2. **Navigation:** Viewing **Google Maps** (`com.google.android.apps.maps`) using offline map data stored on the head unit's 20.7 GB free storage, without draining the user's iPhone battery via continuous 5G hotspot. When a new destination is needed, the user wants three practical ways to start navigation:
   - **6-character Plus Code (Open Location Code) offline keypad** built right into the launcher.
   - **Auto-Burst Hotspot (Chớp Hotspot)** from iOS Shortcuts.
   - **Bluetooth BLE GATT bridge** from the iPhone.
3. **Live Google Maps inside a CarPlay Frame:** When Google Maps is open and actively navigating, instead of losing the CarPlay interface to a full-screen app, the launcher provides a **CarPlay Frame Overlay (`SYSTEM_ALERT_WINDOW`)** that keeps the **Left CarPlay Dock** and a **Right Music Player Card** (with a `[Thu gọn ❯]` collapse toggle) framing live Google Maps in the center.
4. **Music Playback (Two family habits unified in one card):**
   - **User:** Streams **YouTube Music from iPhone over Bluetooth** (`com.autochips.bluetooth`).
   - **Family members:** Play **MP3 music from external USB drives** (`com.acloud.stub.localmusic` on `udiskdev1`/`udiskdev3`).
   - Both sources broadcast track metadata and accept playback commands through the head unit's internal XYAuto broadcasts (`update.widget.*` and `xy.android.*`), so a single **Music Card** with a top segmented switch **`[ 📱 Bluetooth | 🔌 USB ]`** and large **`⏮ ⏯ ⏭`** buttons serves everyone.
5. **Safety & Utility:**
   - **Reverse Camera (`com.autochips.android.backcar`):** Hardware reverse trigger (`xy.android.mcu.backcar.in` / `out`) must take over 100% of the screen immediately when shifting into Reverse (`R`). All launcher overlays must hide within milliseconds during reverse gear.
   - **Front USB Dashcam (`com.fvsm.camera`):** 1-tap access from the Dock and Dashboard.
   - **Audio Equalizer / DSP (`com.xyauto.Settings`):** 1-tap access from the Dock and Dashboard.

---

## 2. Goals, Non-Goals, and Anti-Goals

### Goals
- **Ultra-lightweight Core Launcher (`:app`):**
  - Strictly **Java 11** source compatibility, targeting **Pure Android Framework SDK (`android.app.Activity`, `android.view.*`, `android.widget.*`)** with **zero runtime AndroidX/Material dependencies**.
  - Release APK size strictly **< 150 KB** (well under the 512 KB `cleanyourtv` gate) and runtime memory footprint **< 15 MB RAM**.
  - **Zero `INTERNET` permission** in `:app`.
  - Compatible with **Android 6.0 (API 23)** (`minSdk = 23`).
- **Complete 6-State CarPlay User Experience on `1024x600 @ 160dpi`:**
  - **State 1:** Home Split Dashboard (Left Dock + Left Music Card with `[📱 Bluetooth | 🔌 USB]` segmented toggle + Right Navigation Card & Car Utility Card).
  - **State 2:** Live Google Maps Mode (Left Dock + Live Google Maps in center ~`695x600` + Right Music-Only Card ~`255x600`, no Car Utility clutter).
  - **State 3:** Collapsed Live Google Maps Mode (Left Dock + Wide Live Google Maps ~`948x600` + Floating mini media pill `[🎵 ⏮ ⏯ ⏭ | ❮ Mở khung]` at top-right).
  - **State 4:** Page 2 App Grid (`4 columns × 2 rows` paged grid + App Library modal to show/hide/reorder installed apps).
  - **State 5:** Offline 6-Character Plus Code Keypad Modal (`23456789CFGHJMPQRVWX+` mathematical Open Location Code decoder to `geo:` / `google.navigation:` Intent).
  - **State 6:** Night Clock Screensaver (`ClockActivity` — pure black background, large clock, current song subtitle, tap anywhere to wake) + Instant Reverse Camera Yielding.
- **Modular Navigation Bridge Add-on (`addons/nav-bridge/`):**
  - Separate lightweight Android APK (~40 KB) that holds `BLUETOOTH`, `BLUETOOTH_ADMIN`, `ACCESS_FINE_LOCATION`, `INTERNET`, and `ACCESS_WIFI_STATE` permissions.
  - Provides both a **BLE GATT Server** and a **Local HTTP/UDP Hotspot Burst Receiver** that forwards incoming `(lat, lon, label)` coordinates to `CleanYourCar` via explicit broadcast/intent.
- **Safe ADB Debloat & Install Tooling (`tools/` & `examples/yt3518c-mt3561s/`):**
  - Shell scripts (`tools/disable.sh`, `tools/restore.sh`, `tools/build-and-install.sh`) tailored for Android 6.0 (API 23, where `cmd package` does not exist and `pm` / `am` / `dumpsys` must be used) and tested debloat list freeing ~550 MB RAM while protecting all MCU/CANBus/Backcar/Bluetooth/USB packages.

### Non-Goals
- No embedded Map SDK (Mapbox/Google Maps SDK View) inside the launcher process.
- No Apple MFi hardware CarPlay projection protocol inside the launcher.
- No Android AppWidgetHost hosting heavy third-party widgets.
- No weather polling, cloud accounts, analytics, or background internet sync.

### Anti-Goals (What Would Make This a Failure Even If It Works)
- **Breaking Reverse Camera (`com.autochips.android.backcar`)** or leaving floating overlay windows covering the reverse camera feed when shifting into `R`.
- **Uninstalling system packages (`pm uninstall`)** instead of reversible `pm disable-user`.
- **Adding `INTERNET` permission or heavy UI libraries (`appcompat`, `material`) to `:app`**, bloating the launcher APK or RAM usage.
- **Keeping `minSdk = 26`**, which causes `INSTALL_FAILED_OLDER_SDK` on the user's real Android 6.0 (SDK 23) head unit.

---

## 3. Constraints

1. **Hardware & OS Constraints (`YT3518C` / `mt3561s`):**
   - True SDK level is **API 23 (Android 6.0)** despite `ro.build.version.release` reporting `"8.0"`.
   - `cmd package` shell command does **not** exist on API 23; scripts must use `pm disable-user`, `pm enable`, `pm grant`, `am start`, and `dumpsys package`.
   - Screen geometry is fixed landscape **`1024x600` pixels at `160 dpi` (`mdpi`)**, meaning `1px = 1dp`.
2. **Codebase Constraints (`.github/copilot-instructions.md`):**
   - **100% Java** (Java 11 source/target compatibility) for all application and test code. No `.kt` source files.
   - Gradle `9.6.0` with AGP `9.4.1`, Kotlin DSL (`.gradle.kts`), configuration cache enabled (`org.gradle.configuration-cache=true`), and dependencies declared in `gradle/libs.versions.toml`.
   - Custom R8 keep rules placed in `app/src/main/keepRules/rules.keep`.

---

## 4. Approaches Considered

| Approach | Description | Trade-offs | Decision |
|---|---|---|---|
| **Approach 1 (Selected): Modular `cleanyourtv` Architecture** |- `:app` (`CleanYourCar`): Pure Android SDK Java, 0 runtime library dependencies, 0 `INTERNET` permission, <150 KB APK, includes Dashboard, App Grid, Plus Code Decoder, Night Clock, and `OverlayHudService` for Live Google Maps.<br>- `:navbridge` (or `addons/nav-bridge`): Separate tiny APK (~40 KB) handling BLE GATT Server & Hotspot Burst HTTP/UDP listener.<br>- `tools/` & `examples/yt3518c-mt3561s/`: Safe ADB debloat/restore/install scripts. | **Pros:** Strictly honors `cleanyourtv` zero-network launcher rule; smallest possible RAM & APK footprint; clean separation of permissions.<br>**Cons:** Builds and installs 2 small APKs via `build-and-install.sh`. | **Selected by user (`A`)** |
| **Approach 2: Monolithic Single APK** | Combines the Launcher and BLE/Hotspot receivers into a single `:app` APK. | **Pros:** Single APK.<br>**Cons:** Forces `INTERNET` and network sockets into the Home Launcher process, violating `cleanyourtv` core rules. | Rejected |
| **Approach 3: AndroidX + Material Components** | Uses `androidx.appcompat` and `com.google.android.material` for UI components. | **Pros:** Familiar Material widgets.<br>**Cons:** Bloats APK to >1.5 MB (exceeding 512 KB gate) and adds ~30–50 MB RAM overhead on a 2 GB RAM head unit. | Rejected |

---

## 5. Detailed Architecture & Component Design

### 5.1. Module & Directory Layout

```text
CleanYourCar/
├── app/                                  # Core CleanYourCar (0 INTERNET, <150 KB, minSdk 23)
│   └── src/
│       ├── main/
│       │   ├── AndroidManifest.xml
│       │   ├── java/com/minimal/carplaylauncher/
│       │   │   ├── MainActivity.java             # HOME activity (Dock + Pager: Dashboard & App Grid)
│       │   │   ├── ClockActivity.java            # State 6: Fullscreen black night clock screensaver
│       │   │   ├── NavDispatchActivity.java      # Receives coordinates from PlusCode/NavBridge -> starts Maps + Overlay
│       │   │   ├── OverlayHudService.java        # State 2 & 3: Left Dock + Right Music Card overlay over Google Maps
│       │   │   ├── HeadUnitMediaBridge.java      # XYAuto broadcast receiver/sender + standard MediaKey fallback
│       │   │   ├── CarIntentRouter.java          # Launches Maps, BT Music, USB Music, Dashcam, Equalizer, Settings
│       │   │   ├── PlusCodeCodec.java            # Pure-math Open Location Code (6/8/10-char) offline decoder
│       │   │   ├── PlusCodeDialog.java           # State 5: 20-key Plus Code input modal with city reference selector
│       │   │   ├── AppRepository.java            # Queries LAUNCHER apps, manages hidden/order state in SharedPreferences
│       │   │   ├── AppLibraryDialog.java         # Modal to show/hide and reorder apps on the grid
│       │   │   └── ThemeConfig.java              # Reads res/values/config.xml & optional /sdcard/carplay_theme.json
│       │   ├── keepRules/rules.keep
│       │   └── res/...
│       └── test/java/com/minimal/carplaylauncher/
│           ├── PlusCodeCodecTest.java            # Unit tests for 6-char & full Plus Code math decoding
│           ├── HeadUnitMediaBridgeTest.java      # Unit tests for XYAuto broadcast parsing & source switching
│           └── AppRepositoryOrderTest.java       # Unit tests for app pinning/hiding/ordering logic
├── addons/
│   └── nav-bridge/                       # Optional companion APK for BLE GATT & Hotspot Burst coordinate receiving
│       ├── build.gradle.kts
│       └── src/main/.../
│           ├── NavBridgeService.java     # Foreground/persistent listener for BLE GATT & UDP/HTTP port 48920
│           ├── BleGattNavServer.java     # BLE Peripheral Service UUID for iPhone coordinate push
│           ├── HotspotBurstServer.java   # Lightweight loopback/LAN HTTP & UDP listener for iOS Shortcut burst
│           └── BootReceiver.java         # Starts NavBridgeService on boot or hotspot/BT connect
├── examples/
│   └── yt3518c-mt3561s/
│       ├── debloat.txt                   # Verified safe debloat package list for XYAuto YT3518C (mt3561s)
│       └── README.md                     # Device notes & restore instructions
└── tools/
    ├── build-and-install.sh              # Builds release APKs, checks <512KB gate, installs & grants permissions via ADB
    ├── disable.sh                        # Safely disables packages in debloat.txt with backup record & core guard
    └── restore.sh                        # Re-enables all disabled packages and restores stock launcher if requested
```

---

### 5.2. UI & State Specifications (`1024×600 @ 160dpi`)

#### Persistent Left CarPlay Dock (`76dp` width × `600dp` height)
- **Top section:**
  - Digital clock (`HH:mm`, `16sp` bold) + status line (`BT • Offline` or `BT • Wi-Fi`, `10sp`).
- **Center section (4 pinned hardware/daily icons, `48dp × 48dp` rounded `13dp` squircles):**
  1. `🗺️ Google Maps` (`com.google.android.apps.maps`) — Launches Google Maps in **State 2 (Live Google Maps Mode)**.
  2. `🎵 Music` — When in Home, highlights/focuses Music Card; when in Live Google Maps Mode, toggles or opens the active music source (`Bluetooth` or `USB`).
  3. `📷 Front Dashcam` (`com.fvsm.camera`) — Opens front USB dashcam full screen (hiding overlays while dashcam is focused, or keeping Left Dock available).
  4. `🎚️ Equalizer` (`com.xyauto.Settings` -> `.ApsStartServiceViewActivity` / action `android.intent.action.xyaps`) — Opens the head unit's sound DSP/Equalizer.
- **Bottom section:**
  - Page toggle button `[ ⊞ ]` (`44dp × 44dp`): Switches between **State 1 (Dashboard)** and **State 4 (App Grid)**, or returns to Home from **State 2/3 (Live Google Maps Mode)**.

#### State 1: Home Split Dashboard (`MainActivity` Page 0)
- **Left Column (`~50%` of content area): Unified Music Card**
  - **Top:** Segmented source switch pill **`[ 📱 Nhạc Bluetooth | 🔌 Nhạc USB ]`**.
    - Tapping **`📱 Nhạc Bluetooth`**: Sets active source to `BT`, sends broadcast `xy.android.bluetooth` (and optionally opens `com.autochips.bluetooth/.MainBluetoothActivity` on double-tap or long-press).
    - Tapping **`🔌 Nhạc USB`**: Sets active source to `USB`, sends broadcast `xy.android.music` (and opens `com.acloud.stub.localmusic/.QtActivity` on double-tap or long-press so family members can pick folders if desired).
  - **Middle:** Music note badge + Track Title (`18sp` bold, default `"Sẵn sàng phát nhạc"`) + Subtitle (`"Nhạc Bluetooth • iPhone"` or `"Nhạc USB • GalaMusic"`).
  - **Bottom:** Large media transport bar **`[ ⏮ ]   [ ⏯ ]   [ ⏭ ]`** (`56dp` touch height).
- **Right Column Top (`~55%` height): Navigation Card**
  - Header tag + Current Vietnamese Date (`"Thứ ..., Ngày ... Tháng ..."`).
  - Two large side-by-side buttons:
    - **`[ 🗺️ Mở Google Maps Live ]`** -> Launches Google Maps + activates **State 2**.
    - **`[ 🔢 Nhập mã 6 ký tự ]`** -> Opens **State 5 (`PlusCodeDialog`)**.
- **Right Column Bottom (`~45%` height): Car Utility Card**
  - `2 × 2` grid of large buttons:
    - **`[ 📷 Camera Trước ]`** -> Launches `com.fvsm.camera`.
    - **`[ 🎚️ Chỉnh Equalizer ]`** -> Launches `com.xyauto.Settings` (APS/EQ).
    - **`[ 🔆 Độ sáng màn ]`** -> Cycles screen brightness (25% / 60% / 100%) or opens Display Settings.
    - **`[ 🌙 Màn hình chờ ]`** -> Launches **State 6 (`ClockActivity`)**.

#### State 2 & State 3: Live Google Maps Mode (`OverlayHudService`)
- **How it works on Android 6.0 (API 23):**
  - `OverlayHudService` uses `WindowManager` with `WindowManager.LayoutParams.TYPE_PHONE` / `TYPE_SYSTEM_ALERT` and `FLAG_NOT_FOCUSABLE | FLAG_LAYOUT_IN_SCREEN` so Google Maps underneath receives all map touch/drag/pinch gestures directly without focus theft.
  - Permission `android.permission.SYSTEM_ALERT_WINDOW` is declared in `AndroidManifest.xml` and automatically granted during installation via `appops set com.cleanyourcar.launcher SYSTEM_ALERT_WINDOW allow` in `tools/build-and-install.sh`.
  - **Two separate overlay windows** (so the center map area `[x: 76..769, y: 0..600]` is completely unobstructed and receives native touches directly):
    1. **Left Dock Window** (`76dp × 600dp`, `Gravity.LEFT | Gravity.TOP`): Identical to the Home Left Dock, plus a small floating `[🔢 Mã 6 ký tự]` button at bottom-left or integrated into the dock/panel.
    2. **Right Panel Window**:
       - **In State 2 (Expanded, `255dp × 600dp`, `Gravity.RIGHT | Gravity.TOP`):** Contains **only the Music Card** (Header with `[Thu gọn ❯]`, Album/Note icon, Track Title, Subtitle, large `[⏮] [⏯] [⏭]`, and segmented switch `[📱 Nhạc BT | 🔌 Nhạc USB]`, plus a compact `[🔢 Nhập mã 6 ký tự]` button). No Car Utility buttons are shown here per user decision.
       - **In State 3 (Collapsed, `230dp × 48dp`, `Gravity.RIGHT | Gravity.TOP` with margin):** A single floating pill `[ 🎵 ⏮  ⏯  ⏭ | ❮ Mở khung ]` giving Google Maps `948×600px` of view area.
- **Lifecycle & Auto-Hide Rules (CRITICAL for Safety):**
  - **Reverse Gear Priority:** `OverlayHudService` registers a high-priority `BroadcastReceiver` for:
    - `xy.android.mcu.backcar.in`, `xy.android.mcu.backcar.in2`, `xy.android.force.back.in`, `android.intent.action.START_ARM1_BACKCAR`, `xy.android.avin.come` -> **Immediately hides (`View.GONE`) all overlay windows**.
    - `xy.android.mcu.backcar.out`, `xy.android.force.back.out`, `com.autochips.avin.quit`, `xy.android.avin.leave` -> **Restores overlay windows if Live Maps mode was active**.
  - **Exiting Live Maps Mode:** Tapping `[ ⊞ ]` on the Left Dock (or pressing the physical Home button) brings `MainActivity` to the foreground and stops/hides `OverlayHudService`.

#### State 4: App Grid & App Library (`MainActivity` Page 1)
- Paged `4 × 2` grid (8 large app tiles per page, `68dp` icons, `13sp` labels) plus a final **`[ ➕ Thư viện App ]`** tile.
- Tapping an app opens it (if `com.google.android.apps.maps` is tapped, opens in State 2 Live Maps Mode).
- Long-pressing any tile or tapping **`[ ➕ Thư viện App ]`** opens `AppLibraryDialog` where the user can toggle visibility (show/hide apps from the grid) or move apps up/down in order.

#### State 5: Offline 6-Character Plus Code Keypad (`PlusCodeDialog`)
- Custom dark dialog with a **20-character valid Open Location Code keypad** (`2 3 4 5 6 7 8 9 C F G H J M P Q R V W X` + `+` + `⌫`).
- **Reference City Selector** at top-right (stored in `SharedPreferences`, defaults to **TP.HCM (`10.7769, 106.7009`)**, with quick presets for **Hà Nội (`21.0285, 105.8542`)**, **Đà Nẵng (`16.0544, 108.2022`)**, **Cần Thơ (`10.0452, 105.7469`)**, **Nha Trang (`12.2388, 109.1967`)**, **Đà Lạt (`11.9404, 108.4583`)**, **Vũng Tàu (`10.3460, 107.0843`)**, or last known GPS location if available from `LocationManager.getLastKnownLocation(GPS_PROVIDER)`).
- **Pure-Math Decoding (`PlusCodeCodec.java`):**
  - Accepts either a **short 6/7-character code** (e.g., `QMVX+2P` or `QMVX+2P5`) recovered against the reference latitude/longitude using the official Open Location Code base-20 place-value algorithm, OR a **full 8/10-character global code** (e.g., `7P28QMVX+2P`).
  - Launches `google.navigation:q=<lat>,<lon>` (with fallback to `geo:<lat>,<lon>?q=<lat>,<lon>`) in `com.google.android.apps.maps` and activates **State 2 (Live Google Maps Mode)**.

#### State 6: Night Clock Screensaver (`ClockActivity`)
- Pure `#000000` background, `68sp` thin white clock (`85%` alpha), date + current song title (`45%` alpha).
- Keeps screen on while active; single tap anywhere on screen finishes `ClockActivity` and returns to the previous state.

---

## 5.2.A. Design System & Visual Tokens (`glassmorphism` Skill Applied)

Following the installed **`glassmorphism`** design system skill (`.github/skills/glassmorphism/SKILL.md` & `DESIGN.md`), adapted for an automotive dark cockpit environment on Android 6.0 (`1024x600 @ 160dpi`, zero GPU blur overhead):

### Semantic Color Tokens (`ThemeConfig.java` & `res/values/colors.xml`)
- **Canvas Background (`bg_canvas`) & Optional Zero-Bloat Wallpaper Support:**
  - **Default (0 KB APK / 0 MB RAM):** Deep radial/linear gradient from `#0D1326` (top-left aurora glow) -> `#090D18` -> `#05070E` (bottom-right) so translucent glass cards have rich depth without needing real-time `RenderScript` blur.
  - **Optional Custom Wallpaper (`/sdcard/carplay_wallpaper.jpg` or `.webp` or System Wallpaper):** If a file exists at `/sdcard/carplay_wallpaper.jpg` (or `.png`/`.webp`), `ThemeConfig` decodes it downsampled to `1024x600` using `Bitmap.Config.RGB_565` (~1.2 MB RAM max, 0 KB added to APK) with a dark scrim overlay (`#8005070E`) so glassmorphism cards remain high-contrast.
- **Glass Surface Layers:**
  - `surface_dock`: `#D90F1626` (`85%` alpha dark slate glass) with `1dp` right border `#29FFFFFF` (`16%` luminous white edge).
  - `surface_card`: Top-to-bottom translucent gradient `#24FFFFFF` (`14%` white) -> `#12FFFFFF` (`7%` white) over `#CC111827` base, framed by a `1dp` luminous specular border `#33FFFFFF` (`20%` white).
  - `surface_card_interactive`: `#1FFFFFFF` (`12%` white) default, `#381856FF` (`22%` primary tint + `#801856FF` border) when pressed/focused.
  - `surface_recessed`: `#66000000` (`40%` black) for segmented pill tracks and media transport wells.
- **Brand & Semantic Accent Tokens (from `glassmorphism` skill):**
  - `color_primary`: `#1856FF` (Electric Glass Blue — active Bluetooth source pill, active indicator, primary focus border)
  - `color_secondary`: `#3A344E` (Deep Violet Slate — secondary glass accent)
  - `color_success`: `#07CA6B` (Luminous Emerald — Google Maps Live badge, active navigation CTA)
  - `color_warning`: `#E89558` (Warm Amber — Dashcam & Brightness accent)
  - `color_danger`: `#EA2143` (Crimson — USB Music active pill & Backspace key)
  - `text_primary`: `#FFFFFF` (Pure white, WCAG 2.2 AA high contrast > 12:1 against glass surfaces)
  - `text_secondary`: `#B8C4E0` (Frosted Ice Blue-Gray for subtitles, artist names, status labels)

### Typography, Bento Geometry & Component States
- **Bento Card Geometry:**
  - Card corner radius: `20dp` (`rounded-xl` automotive adaptation); inner button/tile radius: `13dp`; segmented pill radius: `10dp`.
  - Spacing rhythm: `14dp` outer padding, `12dp` gap between Bento cards, `16dp` internal card padding (`comfortable density mode`).
- **Explicit Interaction States (Required by `glassmorphism` skill):**
  - **Default:** Translucent fill (`#1FFFFFFF`) + `1dp` `#29FFFFFF` border.
  - **Pressed / Active (`state_pressed="true"`):** Luminous border brightens to `#99FFFFFF` or `#FF1856FF`, fill shifts to `#3D1856FF`.
  - **Focus-Visible (`state_focused="true"` for steering-wheel / D-pad keys):** `2dp` solid `#1856FF` outer ring.
  - **Disabled:** `40%` alpha with muted border `#14FFFFFF`.

---

### 5.3. Head Unit Media & Source Integration (`HeadUnitMediaBridge.java`)

Extracted directly from the head unit's `XYMusicWidget.apk` and `custom_broadcasts.txt`:
- **Incoming Metadata Broadcasts (registered dynamically in `MainActivity` & `OverlayHudService`):**
  - `update.widget.songname` / `update.widget.all`: Reads String extras (`"songname"`, `"artist"`, `"album"`, `"title"`) to update the song title and artist in real time.
  - `update.widget.playbtnstate`: Updates play/pause icon state.
  - `xy.android.bluetooth.connected` / `xy.android.bluetooth.disconnected` / `android.bluetooth.adapter.action.CONNECTION_STATE_CHANGED`: Updates Bluetooth status indicator in Dock and Music Card.
- **Outgoing Playback Control Broadcasts (when user taps `⏮`, `⏯`, `⏭`):**
  - Sends XYAuto broadcast (`xy.android.previousmedia`, `xy.android.playpause`, `xy.android.nextmedia`) **AND** dispatches standard Android `AudioManager.dispatchMediaKeyEvent` (`KEYCODE_MEDIA_PREVIOUS`, `KEYCODE_MEDIA_PLAY_PAUSE`, `KEYCODE_MEDIA_NEXT`) so both native head unit players (`GalaMusic` / `BTSuite`) and standard Android media apps respond reliably.
- **Source Switching (`[ 📱 Nhạc Bluetooth | 🔌 Nhạc USB ]`):**
  - Switching to **Bluetooth**: Sends `new Intent("xy.android.bluetooth")` and starts/brings up BT audio channel; long-pressing opens `com.autochips.bluetooth/.MainBluetoothActivity`.
  - Switching to **USB**: Sends `new Intent("xy.android.music")` (or starts `com.acloud.stub.localmusic/.QtActivity` if USB player isn't running yet); long-pressing opens `com.acloud.stub.localmusic/.QtActivity`.

---

### 5.4. Companion Add-on: `addons/nav-bridge` (BLE GATT + Hotspot Burst)

1. **Contract with `:app` (`CleanYourCar`):**
   - When `nav-bridge` receives coordinates from either BLE or Hotspot Burst, it sends an explicit Intent to `com.cleanyourcar.launcher/.NavDispatchActivity` with action `com.cleanyourcar.launcher.action.NAVIGATE` and extras `lat` (double), `lon` (double), `label` (String, optional), or `plus_code` (String, optional).
   - `NavDispatchActivity` validates the coordinates, starts Google Maps navigation (`google.navigation:q=lat,lon`), and starts `OverlayHudService` (State 2).
2. **Channel A — Bluetooth BLE GATT Server (`BleGattNavServer.java`):**
   - Advertises Service UUID `0000c910-0000-1000-8000-00805f9b34fb` with local name `CarPlayNav`.
   - Writable Characteristic UUID `0000c911-0000-1000-8000-00805f9b34fb` accepts UTF-8 strings in formats:
     - `"10.7769,106.7009"` (raw lat,lon)
     - `"QMVX+2P"` (Plus Code)
     - `"geo:10.7769,106.7009"` or Google Maps URL containing `@lat,lon` or `?q=lat,lon`.
3. **Channel B — Auto-Burst Hotspot Receiver (`HotspotBurstServer.java`):**
   - Listens on UDP port `48920` (for broadcast packets `255.255.255.255:48920` or `172.20.10.255:48920` from iOS Shortcut) and HTTP `GET/POST http://<car-ip>:48920/nav?q=10.7769,106.7009`.
   - Also monitors `WifiManager.NETWORK_STATE_CHANGED_ACTION`: when the head unit connects to the iPhone Hotspot (gateway `172.20.10.1`), it can optionally poll `http://172.20.10.1:48921/nav` once so the iOS Shortcut doesn't even need to know the car's IP.
   - Responds with HTTP `200 OK` (`{"status":"ok"}`) so the iOS Shortcut immediately turns off Personal Hotspot within 1 second of delivery.

---

### 5.5. Safe ADB Debloat & Install Scripts (`tools/` & `examples/yt3518c-mt3561s/`)

- **`examples/yt3518c-mt3561s/debloat.txt`:**
  - Includes the agreed **Group 2 (100% safe bloatware)** + **Group 3 Option B (Video players `com.acloud.stub.video`, `com.mxtech.videoplayer.ad`, `com.android.gallery3d`, and `com.android.vending` Play Store)**.
  - Keeps `com.google.android.gms` and `com.google.android.apps.maps` enabled for Google Maps.
  - Enforces a hardcoded **Protected Packages Allowlist** in `tools/disable.sh` that refuses to disable `com.autochips.android.backcar`, `com.autochips.bluetooth`, `com.android.bluetooth`, `com.xygala.canbus`, `com.xygala.pvcanset`, `com.xy.spl`, `com.xy.brakecheck`, `com.xyauto.Settings`, `com.fvsm.camera`, `com.acloud.stub.localmusic`, `com.acloud.stub.localradio`, `com.mediatek.usbchecker`, `com.android.systemui`, `com.android.settings`, `android`.
- **`tools/build-and-install.sh`:**
  - Compiles `:app:assembleRelease` (and `:navbridge:assembleRelease`), verifies `:app` release APK size is `< 524,288` bytes (512 KB).
  - Installs APK via `adb install -r`, grants `SYSTEM_ALERT_WINDOW` via `appops set`, launches `com.cleanyourcar.launcher/.MainActivity`, verifies it is the focused activity in `dumpsys activity activities`, and sets it as Home.

---

## 6. What Is Left to the Builder

- Exact color hex values, vector drawable paths, and subtle corner radius tuning within the dark automotive palette shown in the mockups.
- Internal helper class decomposition if a file grows beyond ~300 lines.
- Fallback behavior on emulators/devices that do not have XYAuto's proprietary packages installed (gracefully showing a toast or opening standard Android Settings/Bluetooth/Maps so the launcher can also be tested on any Android device or emulator).
