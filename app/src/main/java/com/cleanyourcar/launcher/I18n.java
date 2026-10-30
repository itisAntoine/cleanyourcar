package com.cleanyourcar.launcher;

import android.content.Context;

/**
 * Ultra-lightweight multi-language dictionary supporting 6 popular languages
 * (Tiếng Việt, English, 中文, 日本語, 한국어, Español) with live instant switching.
 */
public final class I18n {
    public static final int LANG_VI = 0;
    public static final int LANG_EN = 1;
    public static final int LANG_ZH = 2;
    public static final int LANG_JA = 3;
    public static final int LANG_KO = 4;
    public static final int LANG_ES = 5;

    public static final String[] LANGUAGE_NAMES = new String[] {
            "Tiếng Việt",
            "English",
            "中文 (简体)",
            "日本語",
            "한국어",
            "Español"
    };

    public static final int KEY_GOOGLE_MAPS = 0;
    public static final int KEY_BT_MUSIC = 1;
    public static final int KEY_USB_MUSIC = 2;
    public static final int KEY_PLUS_CODE = 3;
    public static final int KEY_DASHCAM = 4;
    public static final int KEY_EQUALIZER = 5;
    public static final int KEY_BRIGHTNESS = 6;
    public static final int KEY_NIGHT_SCREEN = 7;
    public static final int KEY_NAV_SUBTITLE = 8;
    public static final int KEY_APP_LIBRARY = 9;
    public static final int KEY_UI_SETTINGS = 10;
    public static final int KEY_SETTINGS_TITLE = 11;
    public static final int KEY_CLOSE = 12;
    public static final int KEY_THEME_ROW = 13;
    public static final int KEY_OPACITY_ROW = 14;
    public static final int KEY_DOCK_SIDE_ROW = 15;
    public static final int KEY_DASH_LAYOUT_ROW = 16;
    public static final int KEY_GRID_COLS_ROW = 17;
    public static final int KEY_LANGUAGE_ROW = 18;
    public static final int KEY_HOME_BTN = 19;
    public static final int KEY_COLLAPSE_BTN = 20;
    public static final int KEY_EXPAND_BTN = 21;
    public static final int KEY_GO_NOW = 22;
    public static final int KEY_OFFLINE_STATUS = 23;
    public static final int KEY_MUSIC_READY = 24;
    public static final int KEY_SETUP_TITLE = 25;
    public static final int KEY_SETUP_ENTRY = 26;
    public static final int KEY_SETUP_PARKED = 27;
    public static final int KEY_SETUP_AAOS = 28;
    public static final int KEY_SETUP_PROFILE = 29;
    public static final int KEY_SETUP_CHECK = 30;
    public static final int KEY_SETUP_IMPORT = 31;
    public static final int KEY_SETUP_EXPORT = 32;
    public static final int KEY_SETUP_ROLES = 33;
    public static final int KEY_SETUP_HOME = 34;
    public static final int KEY_SETUP_REFERENCE = 35;
    public static final int KEY_SETUP_DONE = 36;
    public static final int KEY_APPLY = 37;
    public static final int KEY_CANCEL = 38;
    public static final int KEY_NOT_SET = 39;
    public static final int KEY_CLEAR_CHOICE = 40;
    public static final int KEY_SAVED = 41;
    public static final int KEY_SAVE_FAILED = 42;
    public static final int KEY_READ_ONLY = 43;
    public static final int KEY_ROLE_UNAVAILABLE = 44;
    public static final int KEY_APP_NOT_FOUND = 45;
    public static final int KEY_INVALID_DESTINATION = 46;
    public static final int KEY_DEST_NOT_SENT = 47;
    public static final int KEY_NEED_REFERENCE = 48;
    public static final int KEY_CHOOSE_CITY = 49;
    public static final int KEY_USE_CURRENT_LOCATION = 50;
    public static final int KEY_NO_REFERENCE = 51;
    public static final int KEY_LOCATION_DENIED = 52;
    public static final int KEY_HOME_DONE = 53;
    public static final int KEY_HOME_DECLINED = 54;
    public static final int KEY_HOME_UNAVAILABLE = 55;
    public static final int KEY_MATCH_EXACT = 56;
    public static final int KEY_MATCH_PARTIAL = 57;
    public static final int KEY_MATCH_CONFLICT = 58;
    public static final int KEY_MATCH_UNKNOWN = 59;
    public static final int KEY_CAPABILITY_CLAIMS = 60;
    public static final int KEY_CAPABILITY_BLOCKED = 61;
    public static final int KEY_PROFILE_CHANGES = 62;
    public static final int KEY_PROFILE_APPLIED = 63;
    public static final int KEY_PROFILE_TOO_LARGE = 64;
    public static final int KEY_PROFILE_MALFORMED = 65;
    public static final int KEY_PROFILE_UNSUPPORTED = 66;
    public static final int KEY_PROFILE_APP_TOO_OLD = 67;
    public static final int KEY_PROFILE_TOO_MANY = 68;
    public static final int KEY_EXPORT_DONE = 69;
    public static final int KEY_FILE_ERROR = 70;
    public static final int KEY_ROLE_NAVIGATION = 71;
    public static final int KEY_ROLE_BT_MUSIC = 72;
    public static final int KEY_ROLE_LOCAL_MUSIC = 73;
    public static final int KEY_ROLE_RADIO = 74;
    public static final int KEY_ROLE_DASHCAM = 75;
    public static final int KEY_ROLE_EQUALIZER = 76;
    public static final int KEY_ROLE_SETTINGS = 77;
    public static final int KEY_STRATEGY_GOOGLE = 78;
    public static final int KEY_STRATEGY_GEO = 79;
    public static final int KEY_STRATEGY_OPEN = 80;
    public static final int KEY_PICK_APP = 81;
    public static final int KEY_PLATFORM = 82;
    public static final int KEY_HUD_PAUSED = 83;
    public static final int KEY_HUD_NO_PERMISSION = 84;
    public static final int KEY_HUD_REVERSE = 85;

    private I18n() {
    }

    public static String tr(Context context, int key) {
        return t(ThemeConfig.getLanguageId(context), key);
    }

    public static String t(int langId, int key) {
        switch (langId) {
            case LANG_EN:
                return en(key);
            case LANG_ZH:
                return zh(key);
            case LANG_JA:
                return ja(key);
            case LANG_KO:
                return ko(key);
            case LANG_ES:
                return es(key);
            case LANG_VI:
            default:
                return vi(key);
        }
    }

    private static String vi(int key) {
        switch (key) {
            case KEY_GOOGLE_MAPS: return "Google Maps";
            case KEY_BT_MUSIC: return "Nhạc Bluetooth";
            case KEY_USB_MUSIC: return "Nhạc USB";
            case KEY_PLUS_CODE: return "Nhập điểm đến";
            case KEY_DASHCAM: return "Camera trước";
            case KEY_EQUALIZER: return "Equalizer";
            case KEY_BRIGHTNESS: return "Độ sáng";
            case KEY_NIGHT_SCREEN: return "Màn hình đêm";
            case KEY_NAV_SUBTITLE: return "Dẫn đường ngoại tuyến & cầu nối iPhone";
            case KEY_APP_LIBRARY: return "Thư viện App";
            case KEY_UI_SETTINGS: return "Tùy biến UI";
            case KEY_SETTINGS_TITLE: return "Tùy biến giao diện & cài đặt";
            case KEY_CLOSE: return "Đóng ✕";
            case KEY_THEME_ROW: return "Phong cách màu (Theme)";
            case KEY_OPACITY_ROW: return "Độ trong suốt mặt kính (Opacity)";
            case KEY_DOCK_SIDE_ROW: return "Vị trí Thanh Dock";
            case KEY_DASH_LAYOUT_ROW: return "Bố cục Trang Dashboard";
            case KEY_GRID_COLS_ROW: return "Số cột Lưới ứng dụng";
            case KEY_LANGUAGE_ROW: return "Ngôn ngữ (Language)";
            case KEY_HOME_BTN: return "Màn hình chính";
            case KEY_COLLAPSE_BTN: return "Thu gọn";
            case KEY_EXPAND_BTN: return "❮ Mở khung";
            case KEY_GO_NOW: return "ĐI NGAY";
            case KEY_OFFLINE_STATUS: return "Ngoại tuyến";
            case KEY_MUSIC_READY: return "Sẵn sàng phát nhạc";
            default: return setupVi(key);
        }
    }

    private static String en(int key) {
        switch (key) {
            case KEY_GOOGLE_MAPS: return "Google Maps";
            case KEY_BT_MUSIC: return "Bluetooth Music";
            case KEY_USB_MUSIC: return "USB Music";
            case KEY_PLUS_CODE: return "Enter Destination";
            case KEY_DASHCAM: return "Front Camera";
            case KEY_EQUALIZER: return "Equalizer";
            case KEY_BRIGHTNESS: return "Brightness";
            case KEY_NIGHT_SCREEN: return "Night Clock";
            case KEY_NAV_SUBTITLE: return "Offline navigation & iPhone bridge";
            case KEY_APP_LIBRARY: return "App Library";
            case KEY_UI_SETTINGS: return "Customize UI";
            case KEY_SETTINGS_TITLE: return "Interface & Cockpit Settings";
            case KEY_CLOSE: return "Close ✕";
            case KEY_THEME_ROW: return "Color Theme";
            case KEY_OPACITY_ROW: return "Glass Opacity";
            case KEY_DOCK_SIDE_ROW: return "Dock Position";
            case KEY_DASH_LAYOUT_ROW: return "Dashboard Layout";
            case KEY_GRID_COLS_ROW: return "App Grid Columns";
            case KEY_LANGUAGE_ROW: return "Language";
            case KEY_HOME_BTN: return "Home";
            case KEY_COLLAPSE_BTN: return "Collapse";
            case KEY_EXPAND_BTN: return "❮ Expand";
            case KEY_GO_NOW: return "GO NOW";
            case KEY_OFFLINE_STATUS: return "Offline";
            case KEY_MUSIC_READY: return "Ready to play";
            default: return setupEn(key);
        }
    }

    private static String zh(int key) {
        switch (key) {
            case KEY_GOOGLE_MAPS: return "Google 地图";
            case KEY_BT_MUSIC: return "蓝牙音乐";
            case KEY_USB_MUSIC: return "U盘音乐";
            case KEY_PLUS_CODE: return "输入目的地";
            case KEY_DASHCAM: return "行车记录仪";
            case KEY_EQUALIZER: return "均衡器";
            case KEY_BRIGHTNESS: return "屏幕亮度";
            case KEY_NIGHT_SCREEN: return "夜间时钟";
            case KEY_NAV_SUBTITLE: return "离线导航与 iPhone 互联";
            case KEY_APP_LIBRARY: return "应用库";
            case KEY_UI_SETTINGS: return "界面定制";
            case KEY_SETTINGS_TITLE: return "界面与座舱设置";
            case KEY_CLOSE: return "关闭 ✕";
            case KEY_THEME_ROW: return "主题色彩";
            case KEY_OPACITY_ROW: return "玻璃透明度";
            case KEY_DOCK_SIDE_ROW: return "Dock 栏位置";
            case KEY_DASH_LAYOUT_ROW: return "仪表盘布局";
            case KEY_GRID_COLS_ROW: return "应用网格列数";
            case KEY_LANGUAGE_ROW: return "语言 (Language)";
            case KEY_HOME_BTN: return "主页";
            case KEY_COLLAPSE_BTN: return "收起";
            case KEY_EXPAND_BTN: return "❮ 展开";
            case KEY_GO_NOW: return "立即出发";
            case KEY_OFFLINE_STATUS: return "离线";
            case KEY_MUSIC_READY: return "准备播放音乐";
            default: return en(key);
        }
    }

    private static String ja(int key) {
        switch (key) {
            case KEY_GOOGLE_MAPS: return "Google マップ";
            case KEY_BT_MUSIC: return "Bluetooth 音楽";
            case KEY_USB_MUSIC: return "USB 音楽";
            case KEY_PLUS_CODE: return "目的地を入力";
            case KEY_DASHCAM: return "ドラレコ";
            case KEY_EQUALIZER: return "イコライザー";
            case KEY_BRIGHTNESS: return "画面の明るさ";
            case KEY_NIGHT_SCREEN: return "夜間時計";
            case KEY_NAV_SUBTITLE: return "オフラインナビ & iPhone 連携";
            case KEY_APP_LIBRARY: return "アプリ一覧";
            case KEY_UI_SETTINGS: return "UI カスタム";
            case KEY_SETTINGS_TITLE: return "UI とコックピット設定";
            case KEY_CLOSE: return "閉じる ✕";
            case KEY_THEME_ROW: return "カラーテーマ";
            case KEY_OPACITY_ROW: return "ガラス透明度";
            case KEY_DOCK_SIDE_ROW: return "Dock の位置";
            case KEY_DASH_LAYOUT_ROW: return "ダッシュボード配置";
            case KEY_GRID_COLS_ROW: return "グリッド列数";
            case KEY_LANGUAGE_ROW: return "言語 (Language)";
            case KEY_HOME_BTN: return "ホーム";
            case KEY_COLLAPSE_BTN: return "折りたたむ";
            case KEY_EXPAND_BTN: return "❮ 展開";
            case KEY_GO_NOW: return "ナビ開始";
            case KEY_OFFLINE_STATUS: return "オフライン";
            case KEY_MUSIC_READY: return "再生準備完了";
            default: return en(key);
        }
    }

    private static String ko(int key) {
        switch (key) {
            case KEY_GOOGLE_MAPS: return "Google 지도";
            case KEY_BT_MUSIC: return "블루투스 음악";
            case KEY_USB_MUSIC: return "USB 음악";
            case KEY_PLUS_CODE: return "목적지 입력";
            case KEY_DASHCAM: return "전방 카메라";
            case KEY_EQUALIZER: return "이퀄라이저";
            case KEY_BRIGHTNESS: return "화면 밝기";
            case KEY_NIGHT_SCREEN: return "야간 시계";
            case KEY_NAV_SUBTITLE: return "오프라인 내비게이션 및 iPhone 연동";
            case KEY_APP_LIBRARY: return "앱 보관함";
            case KEY_UI_SETTINGS: return "UI 설정";
            case KEY_SETTINGS_TITLE: return "인터페이스 및 차량 설정";
            case KEY_CLOSE: return "닫기 ✕";
            case KEY_THEME_ROW: return "테마 색상";
            case KEY_OPACITY_ROW: return "유리 투명도";
            case KEY_DOCK_SIDE_ROW: return "독(Dock) 위치";
            case KEY_DASH_LAYOUT_ROW: return "대시보드 레이아웃";
            case KEY_GRID_COLS_ROW: return "앱 그리드 열 수";
            case KEY_LANGUAGE_ROW: return "언어 (Language)";
            case KEY_HOME_BTN: return "홈";
            case KEY_COLLAPSE_BTN: return "접기";
            case KEY_EXPAND_BTN: return "❮ 펼치기";
            case KEY_GO_NOW: return "길안내 시작";
            case KEY_OFFLINE_STATUS: return "오프라인";
            case KEY_MUSIC_READY: return "음악 재생 준비";
            default: return en(key);
        }
    }

    private static String es(int key) {
        switch (key) {
            case KEY_GOOGLE_MAPS: return "Google Maps";
            case KEY_BT_MUSIC: return "Música Bluetooth";
            case KEY_USB_MUSIC: return "Música USB";
            case KEY_PLUS_CODE: return "Ingresar Destino";
            case KEY_DASHCAM: return "Cámara Frontal";
            case KEY_EQUALIZER: return "Ecualizador";
            case KEY_BRIGHTNESS: return "Brillo";
            case KEY_NIGHT_SCREEN: return "Reloj Nocturno";
            case KEY_NAV_SUBTITLE: return "Navegación sin conexión y puente iPhone";
            case KEY_APP_LIBRARY: return "Biblioteca App";
            case KEY_UI_SETTINGS: return "Ajustes UI";
            case KEY_SETTINGS_TITLE: return "Ajustes de Interfaz y Cabina";
            case KEY_CLOSE: return "Cerrar ✕";
            case KEY_THEME_ROW: return "Tema de Color";
            case KEY_OPACITY_ROW: return "Opacidad del Cristal";
            case KEY_DOCK_SIDE_ROW: return "Posición del Dock";
            case KEY_DASH_LAYOUT_ROW: return "Diseño del Panel";
            case KEY_GRID_COLS_ROW: return "Columnas de Apps";
            case KEY_LANGUAGE_ROW: return "Idioma (Language)";
            case KEY_HOME_BTN: return "Inicio";
            case KEY_COLLAPSE_BTN: return "Ocultar";
            case KEY_EXPAND_BTN: return "❮ Mostrar";
            case KEY_GO_NOW: return "IR AHORA";
            case KEY_OFFLINE_STATUS: return "Sin conexión";
            case KEY_MUSIC_READY: return "Listo para reproducir";
            default: return en(key);
        }
    }

    /** Device setup, role routing, and location reference texts. */
    private static String setupVi(int key) {
        switch (key) {
            case KEY_SETUP_TITLE: return "Thiết lập thiết bị";
            case KEY_SETUP_ENTRY: return "Thiết lập thiết bị (ứng dụng, Home, vị trí)";
            case KEY_SETUP_PARKED: return "Chỉ thiết lập khi xe đã dừng đỗ an toàn.";
            case KEY_SETUP_AAOS: return "Android Automotive: hệ thống có thể chặn một số chức năng; ứng dụng sẽ không vượt qua các giới hạn này.";
            case KEY_SETUP_PROFILE: return "Hồ sơ thiết bị";
            case KEY_SETUP_CHECK: return "Kiểm tra thiết bị này";
            case KEY_SETUP_IMPORT: return "Nhập hồ sơ…";
            case KEY_SETUP_EXPORT: return "Xuất cấu hình…";
            case KEY_SETUP_ROLES: return "Ứng dụng cho từng chức năng";
            case KEY_SETUP_HOME: return "Đặt làm màn hình chính (tùy chọn)";
            case KEY_SETUP_REFERENCE: return "Điểm tham chiếu cho Plus Code ngắn";
            case KEY_SETUP_DONE: return "Xong";
            case KEY_APPLY: return "Áp dụng";
            case KEY_CANCEL: return "Hủy";
            case KEY_NOT_SET: return "Chưa chọn";
            case KEY_CLEAR_CHOICE: return "Bỏ chọn";
            case KEY_SAVED: return "Đã lưu";
            case KEY_SAVE_FAILED: return "Không lưu được cấu hình.";
            case KEY_READ_ONLY: return "Cấu hình do phiên bản mới hơn tạo nên chỉ đọc. Hãy cập nhật ứng dụng để thay đổi.";
            case KEY_ROLE_UNAVAILABLE: return "Chưa chọn ứng dụng cho chức năng này. Mở Thiết lập thiết bị.";
            case KEY_APP_NOT_FOUND: return "Không mở được ứng dụng đã chọn.";
            case KEY_INVALID_DESTINATION: return "Mã hoặc tọa độ không hợp lệ.";
            case KEY_DEST_NOT_SENT: return "Đã mở ứng dụng bản đồ; ứng dụng này không nhận được điểm đến.";
            case KEY_NEED_REFERENCE: return "Plus Code ngắn cần thành phố hoặc vị trí hiện tại. Hãy chọn thành phố hoặc nhập mã đầy đủ.";
            case KEY_CHOOSE_CITY: return "Chọn thành phố";
            case KEY_USE_CURRENT_LOCATION: return "Dùng vị trí hiện tại";
            case KEY_NO_REFERENCE: return "Không dùng (chỉ mã đầy đủ)";
            case KEY_LOCATION_DENIED: return "Không có quyền vị trí. Hãy chọn thành phố.";
            case KEY_HOME_DONE: return "Đã đặt làm màn hình chính.";
            case KEY_HOME_DECLINED: return "Chưa đặt làm màn hình chính. Ứng dụng vẫn mở được từ danh sách ứng dụng.";
            case KEY_HOME_UNAVAILABLE: return "Hệ thống này không cho đổi màn hình chính tại đây.";
            case KEY_MATCH_EXACT: return "Khớp hồ sơ: %s";
            case KEY_MATCH_PARTIAL: return "Khớp một phần. Thiếu thông tin: %s";
            case KEY_MATCH_CONFLICT: return "Nhiều hồ sơ cùng khớp; hãy chọn một.";
            case KEY_MATCH_UNKNOWN: return "Chưa có hồ sơ cho thiết bị này. Dùng chế độ chung.";
            case KEY_CAPABILITY_CLAIMS: return "Hồ sơ khai báo (chưa kiểm chứng trên máy này):";
            case KEY_CAPABILITY_BLOCKED: return "Bị chặn: %s";
            case KEY_PROFILE_CHANGES: return "Thay đổi sẽ áp dụng:";
            case KEY_PROFILE_APPLIED: return "Đã áp dụng hồ sơ.";
            case KEY_PROFILE_TOO_LARGE: return "Tệp hồ sơ quá lớn.";
            case KEY_PROFILE_MALFORMED: return "Tệp hồ sơ không đúng định dạng.";
            case KEY_PROFILE_UNSUPPORTED: return "Hồ sơ dùng bộ điều hợp, cách mở hoặc phiên bản không được hỗ trợ.";
            case KEY_PROFILE_APP_TOO_OLD: return "Hồ sơ cần phiên bản ứng dụng mới hơn.";
            case KEY_PROFILE_TOO_MANY: return "Hồ sơ có quá nhiều gói ứng dụng.";
            case KEY_EXPORT_DONE: return "Đã xuất cấu hình.";
            case KEY_FILE_ERROR: return "Không đọc/ghi được tệp.";
            case KEY_ROLE_NAVIGATION: return "Dẫn đường";
            case KEY_ROLE_BT_MUSIC: return "Nhạc Bluetooth";
            case KEY_ROLE_LOCAL_MUSIC: return "Nhạc USB/cục bộ";
            case KEY_ROLE_RADIO: return "Radio";
            case KEY_ROLE_DASHCAM: return "Camera hành trình";
            case KEY_ROLE_EQUALIZER: return "Equalizer";
            case KEY_ROLE_SETTINGS: return "Cài đặt";
            case KEY_STRATEGY_GOOGLE: return "Dẫn đường (google.navigation)";
            case KEY_STRATEGY_GEO: return "Dẫn đường (liên kết geo)";
            case KEY_STRATEGY_OPEN: return "Chỉ mở ứng dụng";
            case KEY_PICK_APP: return "Chọn ứng dụng";
            case KEY_PLATFORM: return "Nền tảng: %s";
            case KEY_HUD_PAUSED: return "Khung nhạc trên bản đồ (HUD) đang tạm dừng cho đến khi máy này được kiểm tra camera lùi và bố cục bản đồ. Bản đồ mở toàn màn hình.";
            case KEY_HUD_NO_PERMISSION: return "Khung nhạc trên bản đồ cần quyền hiển thị trên ứng dụng khác. Bản đồ mở toàn màn hình.";
            case KEY_HUD_REVERSE: return "Khung nhạc trên bản đồ bị ẩn khi lùi xe hoặc khi chưa rõ trạng thái số lùi.";
            default: return "";
        }
    }

    private static String setupEn(int key) {
        switch (key) {
            case KEY_SETUP_TITLE: return "Device setup";
            case KEY_SETUP_ENTRY: return "Device setup (apps, Home, location)";
            case KEY_SETUP_PARKED: return "Use setup only while parked.";
            case KEY_SETUP_AAOS: return "Android Automotive: the system may block some features; this app respects those limits.";
            case KEY_SETUP_PROFILE: return "Device profile";
            case KEY_SETUP_CHECK: return "Check this device";
            case KEY_SETUP_IMPORT: return "Import profile…";
            case KEY_SETUP_EXPORT: return "Export settings…";
            case KEY_SETUP_ROLES: return "App for each function";
            case KEY_SETUP_HOME: return "Set as Home screen (optional)";
            case KEY_SETUP_REFERENCE: return "Reference for short Plus Codes";
            case KEY_SETUP_DONE: return "Done";
            case KEY_APPLY: return "Apply";
            case KEY_CANCEL: return "Cancel";
            case KEY_NOT_SET: return "Not set";
            case KEY_CLEAR_CHOICE: return "Clear choice";
            case KEY_SAVED: return "Saved";
            case KEY_SAVE_FAILED: return "Could not save settings.";
            case KEY_READ_ONLY: return "Settings were saved by a newer app version and are read-only. Update the app to change them.";
            case KEY_ROLE_UNAVAILABLE: return "No app chosen for this function. Open Device setup.";
            case KEY_APP_NOT_FOUND: return "The chosen app could not be opened.";
            case KEY_INVALID_DESTINATION: return "Invalid code or coordinates.";
            case KEY_DEST_NOT_SENT: return "Map app opened; it cannot receive the destination.";
            case KEY_NEED_REFERENCE: return "A short Plus Code needs a city or current location. Choose a city or enter the full code.";
            case KEY_CHOOSE_CITY: return "Choose city";
            case KEY_USE_CURRENT_LOCATION: return "Use current location";
            case KEY_NO_REFERENCE: return "None (full codes only)";
            case KEY_LOCATION_DENIED: return "Location permission denied. Choose a city instead.";
            case KEY_HOME_DONE: return "Set as Home screen.";
            case KEY_HOME_DECLINED: return "Not set as Home. The app still opens from the app list.";
            case KEY_HOME_UNAVAILABLE: return "This system does not allow changing the Home app here.";
            case KEY_MATCH_EXACT: return "Matched profile: %s";
            case KEY_MATCH_PARTIAL: return "Partial match. Missing facts: %s";
            case KEY_MATCH_CONFLICT: return "Several profiles match; choose one.";
            case KEY_MATCH_UNKNOWN: return "No profile for this device. Using generic mode.";
            case KEY_CAPABILITY_CLAIMS: return "Profile claims (not verified on this unit):";
            case KEY_CAPABILITY_BLOCKED: return "Blocked: %s";
            case KEY_PROFILE_CHANGES: return "Changes to apply:";
            case KEY_PROFILE_APPLIED: return "Profile applied.";
            case KEY_PROFILE_TOO_LARGE: return "The profile file is too large.";
            case KEY_PROFILE_MALFORMED: return "The profile file is not valid.";
            case KEY_PROFILE_UNSUPPORTED: return "The profile uses an unsupported adapter, strategy, or schema.";
            case KEY_PROFILE_APP_TOO_OLD: return "The profile needs a newer app version.";
            case KEY_PROFILE_TOO_MANY: return "The profile lists too many packages.";
            case KEY_EXPORT_DONE: return "Settings exported.";
            case KEY_FILE_ERROR: return "Could not read or write the file.";
            case KEY_ROLE_NAVIGATION: return "Navigation";
            case KEY_ROLE_BT_MUSIC: return "Bluetooth music";
            case KEY_ROLE_LOCAL_MUSIC: return "Local/USB music";
            case KEY_ROLE_RADIO: return "Radio";
            case KEY_ROLE_DASHCAM: return "Dashcam";
            case KEY_ROLE_EQUALIZER: return "Equalizer";
            case KEY_ROLE_SETTINGS: return "Settings";
            case KEY_STRATEGY_GOOGLE: return "Navigate (google.navigation)";
            case KEY_STRATEGY_GEO: return "Navigate (geo link)";
            case KEY_STRATEGY_OPEN: return "Open app only";
            case KEY_PICK_APP: return "Choose app";
            case KEY_PLATFORM: return "Platform: %s";
            case KEY_HUD_PAUSED: return "The music frame over the map (HUD) is paused until this unit passes reverse-camera and map-layout checks. Maps opens full screen.";
            case KEY_HUD_NO_PERMISSION: return "The music frame over the map needs the display-over-other-apps permission. Maps opens full screen.";
            case KEY_HUD_REVERSE: return "The music frame over the map is hidden while reversing or when the reverse state is unknown.";
            default: return vi(key);
        }
    }
}
