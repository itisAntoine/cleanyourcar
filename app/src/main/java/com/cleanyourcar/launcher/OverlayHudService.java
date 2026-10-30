package com.cleanyourcar.launcher;

import android.app.Service;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.cleanyourcar.launcher.integration.HeadUnitAdapter;
import com.cleanyourcar.launcher.integration.IntegrationController;
import com.cleanyourcar.launcher.integration.MediaState;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * State 2 (Expanded) & State 3 (Collapsed): Live Google Maps Frame Overlay Service.
 * Keeps ONLY the Right Music-Only Card (or collapsed pill) on the right side so Google Maps'
 * left-side menu, search bar, and back buttons are 100% unobstructed, and yields 100% of the
 * screen immediately when Reverse gear (R) is engaged.
 */
public class OverlayHudService extends Service implements HeadUnitAdapter.Listener {
    public static final String ACTION_SHOW_EXPANDED = "com.cleanyourcar.launcher.OVERLAY_SHOW_EXPANDED";
    public static final String ACTION_SHOW_COLLAPSED = "com.cleanyourcar.launcher.OVERLAY_SHOW_COLLAPSED";
    public static final String ACTION_HIDE = "com.cleanyourcar.launcher.OVERLAY_HIDE";

    public static boolean shouldHideForCarEvent(String action) {
        return HeadUnitMediaBridge.isReverseEnterAction(action);
    }

    public static final class StateMachine {
        private boolean active;
        private boolean collapsed;
        private boolean inReverse;

        public void onCommand(String action) {
            if (ACTION_HIDE.equals(action)) {
                active = false;
            } else if (ACTION_SHOW_COLLAPSED.equals(action)) {
                active = true;
                collapsed = true;
            } else if (ACTION_SHOW_EXPANDED.equals(action)) {
                active = true;
                collapsed = false;
            }
        }

        public void onReverseGear(boolean reverse) {
            this.inReverse = reverse;
        }

        /**
         * Applies an adapter reverse state. UNKNOWN keeps the last trusted state (fail-closed:
         * it never clears an active reverse); HUD gating for unknown devices is decided elsewhere.
         */
        public void onReverseState(MediaState.ReverseState state) {
            if (state == MediaState.ReverseState.ACTIVE) {
                inReverse = true;
            } else if (state == MediaState.ReverseState.INACTIVE) {
                inReverse = false;
            }
        }

        public boolean isActive() {
            return active;
        }

        public boolean isCollapsed() {
            return collapsed;
        }

        public boolean shouldShowLeftDock() {
            return false;
        }

        public boolean shouldShowExpandedRightCard() {
            return active && !inReverse && !collapsed;
        }

        public boolean shouldShowCollapsedPill() {
            return active && !inReverse && collapsed;
        }

        public String computeOverscanSpec(boolean dockLeft) {
            if (!active || inReverse || collapsed) {
                return "0,0,0,0";
            }
            return "0,0,260,0";
        }
    }

    private final StateMachine stateMachine = new StateMachine();
    private IntegrationController integration;

    private WindowManager windowManager;
    private View rightPanelView;
    private View collapsedPillView;

    private TextView btSegBtn;
    private TextView usbSegBtn;
    private TextView songTitleText;
    private TextView songSubText;
    private TextView playPauseBtn;
    private TextView pillPlayPauseBtn;

    @Override
    public void onCreate() {
        super.onCreate();
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        integration = IntegrationController.create(this);
        integration.start(this, this);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = (intent != null && intent.getAction() != null)
                ? intent.getAction()
                : ACTION_SHOW_EXPANDED;
        stateMachine.onCommand(action);
        if (!stateMachine.isActive()) {
            removeAllWindows();
            stopSelf();
            return START_NOT_STICKY;
        }
        ensureWindowsBuilt();
        syncWindowVisibility();
        onMediaStateChanged(integration.mediaState());
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        PlusCodeDialog.dismissActive();
        integration.stop();
        removeAllWindows();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private boolean canDrawOverlayWindows() {
        if (Build.VERSION.SDK_INT >= 23) {
            return Settings.canDrawOverlays(this);
        }
        return true;
    }

    private int overlayWindowType() {
        return (Build.VERSION.SDK_INT >= 26)
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
    }

    private void ensureWindowsBuilt() {
        if (windowManager == null || !canDrawOverlayWindows()) return;
        ThemeConfig.Palette p = ThemeConfig.getActivePalette(this);

        if (rightPanelView == null) {
            rightPanelView = buildExpandedMusicPanel(p);
            WindowManager.LayoutParams rlp = new WindowManager.LayoutParams(
                    260,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    overlayWindowType(),
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT);
            rlp.gravity = Gravity.RIGHT | Gravity.TOP;
            safeAddView(rightPanelView, rlp);
        }

        if (collapsedPillView == null) {
            collapsedPillView = buildCollapsedPill(p);
            WindowManager.LayoutParams plp = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    overlayWindowType(),
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT);
            plp.gravity = Gravity.RIGHT | Gravity.TOP;
            plp.x = 12;
            plp.y = 12;
            safeAddView(collapsedPillView, plp);
        }
    }

    private void safeAddView(View view, WindowManager.LayoutParams lp) {
        try {
            windowManager.addView(view, lp);
        } catch (Throwable ignored) {
        }
    }

    private void removeAllWindows() {
        applySystemOverscan("0,0,0,0");
        if (windowManager == null) return;
        safeRemoveView(rightPanelView);
        safeRemoveView(collapsedPillView);
        rightPanelView = null;
        collapsedPillView = null;
    }

    private void safeRemoveView(View v) {
        if (v != null) {
            try {
                windowManager.removeView(v);
            } catch (Throwable ignored) {
            }
        }
    }

    private void syncWindowVisibility() {
        if (rightPanelView != null) {
            rightPanelView.setVisibility(stateMachine.shouldShowExpandedRightCard() ? View.VISIBLE : View.GONE);
        }
        if (collapsedPillView != null) {
            collapsedPillView.setVisibility(stateMachine.shouldShowCollapsedPill() ? View.VISIBLE : View.GONE);
        }
        applySystemOverscan(stateMachine.computeOverscanSpec(true));
    }

    private void applySystemOverscan(final String spec) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Runtime.getRuntime().exec(new String[] { "sh", "-c", "wm overscan " + spec });
                } catch (Throwable ignored) {
                }
            }
        }).start();
    }

    private void returnToHomeActivity() {
        stateMachine.onCommand(ACTION_HIDE);
        syncWindowVisibility();
        Intent home = new Intent(OverlayHudService.this, MainActivity.class);
        home.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(home);
        stopSelf();
    }

    private View buildExpandedMusicPanel(final ThemeConfig.Palette p) {
        LinearLayout wrapper = new LinearLayout(this);
        wrapper.setOrientation(LinearLayout.VERTICAL);
        wrapper.setPadding(12, 12, 12, 12);
        wrapper.setBackground(ThemeConfig.createGlassCardDrawable(
                0f, p.dockTop, p.dockBottom, p.borderLuminous, 1));

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(14, 14, 14, 14);
        card.setBackground(ThemeConfig.createGlassCardDrawable(
                20f, p.cardTop, p.cardBottom, p.borderLuminous, 1));
        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT);

        // Top Header: [Home] + [Thu gọn]
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        TextView homeChip = new TextView(this);
        homeChip.setText(I18n.tr(this, I18n.KEY_HOME_BTN));
        homeChip.setTextColor(p.textPrimary);
        homeChip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
        homeChip.setTypeface(Typeface.DEFAULT_BOLD);
        homeChip.setPadding(14, 8, 14, 8);
        homeChip.setBackground(ThemeConfig.createInteractiveTileDrawable(
                999f, p.accentPrimary, p.tilePressed, p.borderLuminous, p.textPrimary));
        homeChip.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                returnToHomeActivity();
            }
        });
        header.addView(homeChip);

        View spacer = new View(this);
        header.addView(spacer, new LinearLayout.LayoutParams(0, 1, 1f));

        TextView collapseBtn = new TextView(this);
        collapseBtn.setText(I18n.tr(this, I18n.KEY_COLLAPSE_BTN));
        collapseBtn.setTextColor(p.textPrimary);
        collapseBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
        collapseBtn.setTypeface(Typeface.DEFAULT_BOLD);
        collapseBtn.setPadding(14, 8, 14, 8);
        collapseBtn.setBackground(ThemeConfig.createInteractiveTileDrawable(
                999f, p.tileDefault, p.tilePressed, p.borderLuminous, p.accentPrimary));
        collapseBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                stateMachine.onCommand(ACTION_SHOW_COLLAPSED);
                syncWindowVisibility();
            }
        });
        header.addView(collapseBtn);
        card.addView(header);

        // Segmented Source Switch: [Bluetooth | USB]
        LinearLayout segBar = new LinearLayout(this);
        segBar.setOrientation(LinearLayout.HORIZONTAL);
        segBar.setPadding(4, 4, 4, 4);
        segBar.setBackground(ThemeConfig.createGlassCardDrawable(
                999f, p.wellBackground, p.wellBackground, p.borderSubtle, 1));
        LinearLayout.LayoutParams segLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        segLp.topMargin = 12;

        btSegBtn = new TextView(this);
        btSegBtn.setText("Bluetooth");
        btSegBtn.setGravity(Gravity.CENTER);
        btSegBtn.setPadding(8, 10, 8, 10);
        btSegBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f);
        btSegBtn.setTypeface(Typeface.DEFAULT_BOLD);
        btSegBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                runMediaCommand(HeadUnitAdapter.MediaCommand.SELECT_BLUETOOTH);
            }
        });
        btSegBtn.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                CarIntentRouter.launchMusicSourceApp(OverlayHudService.this, HeadUnitMediaBridge.SOURCE_BT);
                return true;
            }
        });

        usbSegBtn = new TextView(this);
        usbSegBtn.setText(I18n.tr(this, I18n.KEY_USB_MUSIC));
        usbSegBtn.setGravity(Gravity.CENTER);
        usbSegBtn.setPadding(8, 10, 8, 10);
        usbSegBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f);
        usbSegBtn.setTypeface(Typeface.DEFAULT_BOLD);
        usbSegBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                runMediaCommand(HeadUnitAdapter.MediaCommand.SELECT_LOCAL);
            }
        });
        usbSegBtn.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                CarIntentRouter.launchMusicSourceApp(OverlayHudService.this, HeadUnitMediaBridge.SOURCE_USB);
                return true;
            }
        });

        segBar.addView(btSegBtn, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        segBar.addView(usbSegBtn, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        card.addView(segBar, segLp);

        // Middle Track Info
        LinearLayout trackBox = new LinearLayout(this);
        trackBox.setOrientation(LinearLayout.VERTICAL);
        trackBox.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams trackLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);

        android.widget.FrameLayout noteBadge = new android.widget.FrameLayout(this);
        noteBadge.setBackground(ThemeConfig.createGlassCardDrawable(
                18f, 0x66FFFFFF, p.accentPrimary, p.borderLuminous, 1));
        View musicIcon = VectorIcons.createIconView(this, VectorIcons.ICON_MUSIC, p.textPrimary, 28);
        noteBadge.addView(musicIcon, new android.widget.FrameLayout.LayoutParams(28, 28, Gravity.CENTER));
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(60, 60);
        nlp.bottomMargin = 12;
        trackBox.addView(noteBadge, nlp);

        songTitleText = new TextView(this);
        songTitleText.setText(I18n.tr(this, I18n.KEY_MUSIC_READY));
        songTitleText.setTextColor(p.textPrimary);
        songTitleText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 21f);
        songTitleText.setTypeface(Typeface.DEFAULT_BOLD);
        songTitleText.setMaxLines(2);
        songTitleText.setEllipsize(TextUtils.TruncateAt.END);
        trackBox.addView(songTitleText);

        songSubText = new TextView(this);
        songSubText.setText("YouTube Music — iPhone");
        songSubText.setTextColor(p.textSecondary);
        songSubText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14.5f);
        songSubText.setSingleLine(true);
        songSubText.setEllipsize(TextUtils.TruncateAt.END);
        trackBox.addView(songSubText);
        card.addView(trackBox, trackLp);

        // Transport Controls: Vector Icons [Prev] [Play] [Next]
        LinearLayout transport = new LinearLayout(this);
        transport.setOrientation(LinearLayout.HORIZONTAL);
        transport.setGravity(Gravity.CENTER);
        transport.setPadding(8, 10, 8, 10);
        transport.setBackground(ThemeConfig.createGlassCardDrawable(
                999f, p.wellBackground, p.wellBackground, p.borderSubtle, 1));

        View prevBtn = createVectorTransportButton(VectorIcons.ICON_PREV, p.textPrimary, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                runMediaCommand(HeadUnitAdapter.MediaCommand.PREVIOUS);
            }
        });
        playPauseBtn = new TextView(this);
        overlayPlayIconView = VectorIcons.createIconView(this, VectorIcons.ICON_PLAY, p.textPrimary, 26);
        android.widget.FrameLayout playHolder = new android.widget.FrameLayout(this);
        playHolder.setPadding(8, 10, 8, 10);
        playHolder.addView(overlayPlayIconView, new android.widget.FrameLayout.LayoutParams(26, 26, Gravity.CENTER));
        playHolder.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                runMediaCommand(HeadUnitAdapter.MediaCommand.PLAY_PAUSE);
            }
        });
        View nextBtn = createVectorTransportButton(VectorIcons.ICON_NEXT, p.textPrimary, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                runMediaCommand(HeadUnitAdapter.MediaCommand.NEXT);
            }
        });

        transport.addView(prevBtn, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        transport.addView(playHolder, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        transport.addView(nextBtn, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        card.addView(transport);

        // Compact Plus Code button at bottom of Music Overlay
        TextView plusBtn = new TextView(this);
        plusBtn.setText(I18n.tr(this, I18n.KEY_PLUS_CODE));
        plusBtn.setTextColor(p.textPrimary);
        plusBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f);
        plusBtn.setTypeface(Typeface.DEFAULT_BOLD);
        plusBtn.setGravity(Gravity.CENTER);
        plusBtn.setPadding(12, 12, 12, 12);
        plusBtn.setBackground(ThemeConfig.createInteractiveTileDrawable(
                999f, p.tileDefault, p.tilePressed, p.borderLuminous, p.accentPrimary));
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        plp.topMargin = 10;
        plusBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                PlusCodeDialog.show(OverlayHudService.this, true);
            }
        });
        card.addView(plusBtn, plp);

        wrapper.addView(card, cardLp);
        return wrapper;
    }

    private View overlayPlayIconView;

    private View createVectorTransportButton(int iconId, int color, View.OnClickListener onClick) {
        android.widget.FrameLayout holder = new android.widget.FrameLayout(this);
        holder.setPadding(8, 10, 8, 10);
        View icon = VectorIcons.createIconView(this, iconId, color, 26);
        holder.addView(icon, new android.widget.FrameLayout.LayoutParams(26, 26, Gravity.CENTER));
        holder.setOnClickListener(onClick);
        return holder;
    }

    private TextView createTransportButton(ThemeConfig.Palette p, String symbol, View.OnClickListener onClick) {
        TextView tv = new TextView(this);
        tv.setText(symbol);
        tv.setTextColor(p.textPrimary);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f);
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(6, 6, 6, 6);
        tv.setOnClickListener(onClick);
        return tv;
    }

    private View buildCollapsedPill(ThemeConfig.Palette p) {
        LinearLayout pill = new LinearLayout(this);
        pill.setOrientation(LinearLayout.HORIZONTAL);
        pill.setGravity(Gravity.CENTER_VERTICAL);
        pill.setPadding(10, 8, 10, 8);
        pill.setBackground(ThemeConfig.createGlassCardDrawable(
                16f, p.dockTop, p.dockBottom, p.borderLuminous, 1));

        TextView homeBtn = new TextView(this);
        homeBtn.setText("⊞ Home");
        homeBtn.setTextColor(p.textPrimary);
        homeBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        homeBtn.setTypeface(Typeface.DEFAULT_BOLD);
        homeBtn.setPadding(10, 6, 10, 6);
        homeBtn.setBackground(ThemeConfig.createInteractiveTileDrawable(
                10f, p.tileDefault, p.tilePressed, p.borderSubtle, p.accentPrimary));
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        hlp.rightMargin = 6;
        homeBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                returnToHomeActivity();
            }
        });
        pill.addView(homeBtn, hlp);

        TextView prev = createTransportButton(p, "⏮", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                runMediaCommand(HeadUnitAdapter.MediaCommand.PREVIOUS);
            }
        });
        prev.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f);

        pillPlayPauseBtn = createTransportButton(p, "⏯", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                runMediaCommand(HeadUnitAdapter.MediaCommand.PLAY_PAUSE);
            }
        });
        pillPlayPauseBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f);

        TextView next = createTransportButton(p, "⏭", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                runMediaCommand(HeadUnitAdapter.MediaCommand.NEXT);
            }
        });
        next.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f);

        TextView expandBtn = new TextView(this);
        expandBtn.setText("❮ Mở khung");
        expandBtn.setTextColor(p.textPrimary);
        expandBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        expandBtn.setTypeface(Typeface.DEFAULT_BOLD);
        expandBtn.setPadding(10, 6, 10, 6);
        expandBtn.setBackground(ThemeConfig.createInteractiveTileDrawable(
                10f, p.accentPrimary, p.tilePressed, p.borderLuminous, p.textPrimary));
        LinearLayout.LayoutParams elp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        elp.leftMargin = 8;
        expandBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                stateMachine.onCommand(ACTION_SHOW_EXPANDED);
                syncWindowVisibility();
            }
        });

        pill.addView(prev);
        pill.addView(pillPlayPauseBtn);
        pill.addView(next);
        pill.addView(expandBtn, elp);
        return pill;
    }

    private void runMediaCommand(HeadUnitAdapter.MediaCommand command) {
        MediaStateText.reportResult(this, integration.execute(command));
    }

    @Override
    public void onMediaStateChanged(MediaState state) {
        if (state == null) return;
        ThemeConfig.Palette p = ThemeConfig.getActivePalette(this);
        if (songTitleText != null) {
            songTitleText.setText(MediaStateText.title(state, I18n.tr(this, I18n.KEY_MUSIC_READY)));
        }
        if (songSubText != null) {
            songSubText.setText(MediaStateText.subtitle(state, I18n.tr(this, I18n.KEY_BT_MUSIC),
                    I18n.tr(this, I18n.KEY_USB_MUSIC)));
        }
        if (overlayPlayIconView != null) {
            overlayPlayIconView.setBackground(VectorIcons.createDrawable(
                    state.playback == MediaState.Playback.PLAYING
                            ? VectorIcons.ICON_PAUSE : VectorIcons.ICON_PLAY,
                    p.textPrimary,
                    26));
        }

        if (btSegBtn != null && usbSegBtn != null) {
            boolean isBt = state.source == MediaState.Source.BLUETOOTH;
            boolean isUsb = state.source == MediaState.Source.LOCAL;
            btSegBtn.setTextColor(isBt ? p.textPrimary : p.textSecondary);
            btSegBtn.setBackground(isBt ? ThemeConfig.createGlassCardDrawable(
                    999f, 0x66FFFFFF, p.accentPrimary, p.borderLuminous, 1) : null);
            usbSegBtn.setTextColor(isUsb ? p.textPrimary : p.textSecondary);
            usbSegBtn.setBackground(isUsb ? ThemeConfig.createGlassCardDrawable(
                    999f, 0x66FFFFFF, p.accentDanger, p.borderLuminous, 1) : null);
        }
    }

    @Override
    public void onReverseStateChanged(MediaState.ReverseState state) {
        if (state == MediaState.ReverseState.ACTIVE) {
            PlusCodeDialog.dismissActive();
        }
        stateMachine.onReverseState(state);
        syncWindowVisibility();
    }

    @Override
    public void onHealthChanged(HeadUnitAdapter.Health health, String reason) {
        MediaStateText.reportHealth(this, health, reason);
    }
}
