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
            default: return "";
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
            default: return vi(key);
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
}
