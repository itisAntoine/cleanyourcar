package com.cleanyourcar.launcher;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.cleanyourcar.launcher.integration.HeadUnitAdapter;
import com.cleanyourcar.launcher.integration.IntegrationController;
import com.cleanyourcar.launcher.integration.MediaState;
import com.cleanyourcar.launcher.layout.LayoutPolicy;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * CleanYourCar Home Activity. Designed at 1024x600 @ 160dpi; sizes are dp and fitted to the
 * current window by {@link LayoutPolicy}.
 * Pure Android Framework SDK (zero AppCompat/Material dependencies, zero INTERNET permission).
 */
public class MainActivity extends Activity implements HeadUnitAdapter.Listener {
    private static final int PAGE_DASHBOARD = 0;
    private static final int PAGE_APP_GRID = 1;

    private IntegrationController integration;
    private final AppRepository appRepository = new AppRepository();
    private final Handler clockHandler = new Handler(Looper.getMainLooper());

    private int activePage = PAGE_DASHBOARD;
    private int currentGridSubPage = 0;

    private FrameLayout hostLayout;
    private LayoutPolicy.LayoutSpec spec;
    private LinearLayout rootLayout;
    private FrameLayout contentContainer;
    private View dashboardView;
    private View appGridView;

    private TextView dockClockText;
    private TextView dockStatusText;
    private TextView dockPageToggleBtn;

    private TextView btSegBtn;
    private TextView usbSegBtn;
    private TextView songTitleText;
    private TextView songSubText;
    private TextView playPauseBtn;
    private TextView dateHeaderText;

    private GridLayout appsGridLayout;
    private TextView pageDotsText;
    private GestureDetector swipeDetector;

    private final Runnable refitRunnable = new Runnable() {
        @Override
        public void run() {
            refit();
        }
    };

    private final Runnable clockTicker = new Runnable() {
        @Override
        public void run() {
            updateClockAndDate();
            clockHandler.postDelayed(this, 10_000L);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setupSwipeDetector();
        hostLayout = new FrameLayout(this);
        AdaptiveUi.applySystemInsetsAsPadding(hostLayout, refitRunnable);
        hostLayout.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            @Override
            public void onLayoutChange(View v, int left, int top, int right, int bottom,
                    int oldLeft, int oldTop, int oldRight, int oldBottom) {
                if (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop) {
                    v.post(refitRunnable);
                }
            }
        });
        setContentView(hostLayout);
        spec = AdaptiveUi.specFor(this);
        rebuildFullUi();
        if (savedInstanceState == null) {
            SetupActivity.maybeAutoOpen(this);
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        CarIntentRouter.stopOverlayService(this);
        showPage(PAGE_DASHBOARD);
    }

    @Override
    protected void onResume() {
        super.onResume();
        CarIntentRouter.stopOverlayService(this);
        integration = IntegrationController.create(this);
        integration.start(this, this);
        clockHandler.post(clockTicker);
        if (activePage == PAGE_APP_GRID) {
            populateAppGrid();
        }
    }

    @Override
    protected void onPause() {
        clockHandler.removeCallbacks(clockTicker);
        if (integration != null) {
            integration.stop();
        }
        super.onPause();
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        if (swipeDetector != null && swipeDetector.onTouchEvent(ev)) {
            return true;
        }
        return super.dispatchTouchEvent(ev);
    }

    private void setupSwipeDetector() {
        swipeDetector = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onFling(MotionEvent e1, MotionEvent e2, float velocityX, float velocityY) {
                if (e1 == null || e2 == null) return false;
                float dx = e2.getX() - e1.getX();
                float dy = e2.getY() - e1.getY();
                if (Math.abs(dx) > 120 && Math.abs(dx) > Math.abs(dy) * 1.5f) {
                    if (dx < 0) {
                        onSwipeLeft();
                    } else {
                        onSwipeRight();
                    }
                    return true;
                }
                return false;
            }
        });
    }

    private void onSwipeLeft() {
        if (activePage == PAGE_DASHBOARD) {
            currentGridSubPage = 0;
            showPage(PAGE_APP_GRID);
        } else {
            List<AppRepository.AppEntry> apps = appRepository.loadVisibleApps(this);
            int perPage = spec.appSlotsPerPage;
            int totalPages = Math.max(1, (int) Math.ceil((double) Math.max(1, apps.size()) / perPage));
            if (currentGridSubPage + 1 < totalPages) {
                currentGridSubPage++;
                populateAppGrid();
            }
        }
    }

    private void onSwipeRight() {
        if (activePage == PAGE_APP_GRID) {
            if (currentGridSubPage > 0) {
                currentGridSubPage--;
                populateAppGrid();
            } else {
                showPage(PAGE_DASHBOARD);
            }
        }
    }

    private LayoutPolicy.LayoutSpec currentSpec() {
        if (hostLayout != null && hostLayout.getWidth() > 0 && hostLayout.getHeight() > 0) {
            return AdaptiveUi.specFor(this, hostLayout);
        }
        return AdaptiveUi.specFor(this);
    }

    /** Rebuilds only when the window, insets, font scale or grid preference change the fit. */
    private void refit() {
        if (hostLayout == null || hostLayout.getWidth() == 0 || hostLayout.getHeight() == 0) return;
        LayoutPolicy.LayoutSpec next = AdaptiveUi.specFor(this, hostLayout);
        if (!next.equals(spec)) {
            spec = next;
            rebuildFullUi();
        }
    }

    private void rebuildFullUi() {
        ThemeConfig.Palette p = ThemeConfig.getActivePalette(this);
        boolean dockLeft = ThemeConfig.isDockLeft(this);

        rootLayout = new LinearLayout(this);
        rootLayout.setOrientation(LinearLayout.HORIZONTAL);
        rootLayout.setBackground(ThemeConfig.loadCanvasBackground(this));

        View dockView = buildLeftDock(p);
        LinearLayout.LayoutParams dockLp = new LinearLayout.LayoutParams(
                dp(spec.dockWidthDp), ViewGroup.LayoutParams.MATCH_PARENT);
        dockLp.topMargin = dp(spec.marginDp);
        dockLp.bottomMargin = dp(spec.marginDp);
        if (dockLeft) {
            dockLp.leftMargin = dp(spec.marginDp);
        } else {
            dockLp.rightMargin = dp(spec.marginDp);
        }

        contentContainer = new FrameLayout(this);
        LinearLayout.LayoutParams contentLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);

        dashboardView = buildDashboardView(p);
        appGridView = buildAppGridView(p);

        contentContainer.addView(dashboardView, contentChildParams());
        contentContainer.addView(appGridView, contentChildParams());

        if (dockLeft) {
            rootLayout.addView(dockView, dockLp);
            rootLayout.addView(contentContainer, contentLp);
        } else {
            rootLayout.addView(contentContainer, contentLp);
            rootLayout.addView(dockView, dockLp);
        }

        hostLayout.removeAllViews();
        hostLayout.addView(rootLayout, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        updateClockAndDate();
        onMediaStateChanged(integration != null ? integration.mediaState() : MediaState.unknown());
        showPage(activePage);
    }

    /** Full width, or centered at the capped content width on ultrawide windows. */
    private FrameLayout.LayoutParams contentChildParams() {
        if (spec.isContentCapped()) {
            return new FrameLayout.LayoutParams(dp(spec.contentWidthDp + 2 * spec.marginDp),
                    ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER_HORIZONTAL);
        }
        return new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
    }

    private void showPage(int page) {
        activePage = page;
        if (dashboardView != null) {
            dashboardView.setVisibility(page == PAGE_DASHBOARD ? View.VISIBLE : View.GONE);
        }
        if (appGridView != null) {
            appGridView.setVisibility(page == PAGE_APP_GRID ? View.VISIBLE : View.GONE);
            if (page == PAGE_APP_GRID) {
                populateAppGrid();
            }
        }
        if (dockPageToggleBtn != null) {
            ThemeConfig.Palette p = ThemeConfig.getActivePalette(this);
            int fill = (page == PAGE_APP_GRID) ? p.accentPrimary : p.tileDefault;
            dockPageToggleBtn.setBackground(ThemeConfig.createInteractiveTileDrawable(
                    dpf(12f), fill, p.tilePressed, p.borderLuminous, p.accentPrimary));
        }
    }

    private View buildLeftDock(final ThemeConfig.Palette p) {
        FrameLayout dockFrame = new FrameLayout(this);
        dockFrame.setPadding(dp(spec.dockSidePaddingDp), dp(spec.dockPaddingDp),
                dp(spec.dockSidePaddingDp), dp(spec.dockPaddingDp));
        dockFrame.setBackground(ThemeConfig.createGlassCardDrawable(
                999f, p.dockTop, p.dockBottom, p.borderLuminous, 1));
        LinearLayout dock = new LinearLayout(this);
        dock.setOrientation(LinearLayout.VERTICAL);
        dock.setGravity(Gravity.CENTER_HORIZONTAL);
        dockFrame.addView(AdaptiveUi.fillingScroll(this, dock), new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        dockClockText = new TextView(this);
        dockClockText.setTextColor(p.textPrimary);
        dockClockText.setTextSize(TypedValue.COMPLEX_UNIT_SP, spec.dockClockSp);
        dockClockText.setTypeface(Typeface.DEFAULT_BOLD);
        dockClockText.setGravity(Gravity.CENTER);
        dock.addView(dockClockText);

        dockStatusText = new TextView(this);
        dockStatusText.setText("Bluetooth");
        dockStatusText.setTextColor(p.textSecondary);
        dockStatusText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        dockStatusText.setGravity(Gravity.CENTER);
        dockStatusText.setSingleLine(true);
        dockStatusText.setEllipsize(TextUtils.TruncateAt.END);
        dock.addView(dockStatusText);

        LinearLayout iconsCol = new LinearLayout(this);
        iconsCol.setOrientation(LinearLayout.VERTICAL);
        iconsCol.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams colLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);

        iconsCol.addView(createDockDroplet(p, VectorIcons.ICON_GOOGLE_MAPSPIN, false, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                CarIntentRouter.launchGoogleMapsLive(MainActivity.this);
            }
        }));
        iconsCol.addView(createDockDroplet(p, VectorIcons.ICON_MUSIC, true, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (activePage != PAGE_DASHBOARD) {
                    showPage(PAGE_DASHBOARD);
                } else {
                    CarIntentRouter.launchMusicSourceApp(MainActivity.this, musicLaunchSource());
                }
            }
        }));
        iconsCol.addView(createDockDroplet(p, VectorIcons.ICON_CAMERA, false, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                CarIntentRouter.launchDashcam(MainActivity.this);
            }
        }));
        iconsCol.addView(createDockDroplet(p, VectorIcons.ICON_EQUALIZER, false, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                CarIntentRouter.launchEqualizer(MainActivity.this);
            }
        }));
        dock.addView(iconsCol, colLp);

        FrameLayout gridToggleWrap = new FrameLayout(this);
        gridToggleWrap.setBackground(ThemeConfig.createInteractiveTileDrawable(
                999f, p.tileDefault, p.tilePressed, p.borderLuminous, p.accentPrimary));
        View gridIcon = VectorIcons.createIconView(this, VectorIcons.ICON_GRID, p.textPrimary, 26);
        FrameLayout.LayoutParams gilp = new FrameLayout.LayoutParams(dp(26), dp(26), Gravity.CENTER);
        gridToggleWrap.addView(gridIcon, gilp);
        gridToggleWrap.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showPage(activePage == PAGE_DASHBOARD ? PAGE_APP_GRID : PAGE_DASHBOARD);
            }
        });
        gridToggleWrap.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                openSettingsModal();
                return true;
            }
        });
        dock.addView(gridToggleWrap, new LinearLayout.LayoutParams(
                dp(spec.dockToggleDp), dp(spec.dockToggleDp)));
        return dockFrame;
    }

    private View createDockDroplet(
            ThemeConfig.Palette p, int iconId, boolean highlighted, View.OnClickListener onClick) {
        FrameLayout droplet = new FrameLayout(this);
        int fillTop = highlighted ? 0x66FFFFFF : 0x26FFFFFF;
        int fillBot = highlighted ? p.accentPrimary : 0x0AFFFFFF;
        droplet.setBackground(ThemeConfig.createGlassCardDrawable(
                999f, fillTop, fillBot, p.borderLuminous, 1));
        if (iconId == VectorIcons.ICON_GOOGLE_MAPSPIN) {
            try {
                String mapsPkg = CarIntentRouter.getTargetPackage(
                        this, com.cleanyourcar.launcher.config.RoleSelection.Role.NAVIGATION);
                android.graphics.drawable.Drawable realIcon = getPackageManager().getApplicationIcon(mapsPkg);
                if (realIcon != null) {
                    ImageView iv = new ImageView(this);
                    iv.setImageDrawable(realIcon);
                    droplet.addView(iv, new FrameLayout.LayoutParams(dp(36), dp(36), Gravity.CENTER));
                } else {
                    View icon = VectorIcons.createIconView(this, iconId, p.textPrimary, 28);
                    droplet.addView(icon, new FrameLayout.LayoutParams(dp(28), dp(28), Gravity.CENTER));
                }
            } catch (Throwable t) {
                View icon = VectorIcons.createIconView(this, iconId, p.textPrimary, 28);
                droplet.addView(icon, new FrameLayout.LayoutParams(dp(28), dp(28), Gravity.CENTER));
            }
        } else {
            View icon = VectorIcons.createIconView(this, iconId, p.textPrimary, 26);
            droplet.addView(icon, new FrameLayout.LayoutParams(dp(26), dp(26), Gravity.CENTER));
        }
        droplet.setOnClickListener(onClick);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                dp(spec.dockButtonDp), dp(spec.dockButtonDp));
        lp.topMargin = dp(spec.dockButtonMarginDp);
        lp.bottomMargin = dp(spec.dockButtonMarginDp);
        droplet.setLayoutParams(lp);
        return droplet;
    }

    private View buildDashboardView(final ThemeConfig.Palette p) {
        boolean stacked = spec.mode == LayoutPolicy.Mode.STACKED;
        LinearLayout dash = new LinearLayout(this);
        dash.setOrientation(stacked ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
        int margin = dp(spec.marginDp);
        dash.setPadding(margin, margin, margin, margin);

        View musicCol = buildMusicBentoColumn(p);
        View rightCol = buildRightBentoColumn(p);

        LinearLayout.LayoutParams firstLp = dashColumnParams(stacked);
        LinearLayout.LayoutParams secondLp = dashColumnParams(stacked);
        if (stacked) {
            firstLp.bottomMargin = margin;
        } else {
            firstLp.rightMargin = margin;
        }

        if (ThemeConfig.isSwapDashboardColumns(this)) {
            dash.addView(rightCol, firstLp);
            dash.addView(musicCol, secondLp);
        } else {
            dash.addView(musicCol, firstLp);
            dash.addView(rightCol, secondLp);
        }
        return AdaptiveUi.fillingScroll(this, dash);
    }

    private static LinearLayout.LayoutParams dashColumnParams(boolean stacked) {
        return stacked
                ? new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f)
                : new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1.0f);
    }

    private View buildMusicBentoColumn(final ThemeConfig.Palette p) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(20), dp(18), dp(20), dp(18));
        card.setBackground(ThemeConfig.createGlassCardDrawable(
                dpf(24f), p.cardTop, p.cardBottom, p.borderLuminous, 1));

        // Top Segmented Pill: [Nhạc Bluetooth | Nhạc USB] (0% Emoji, Convex Liquid Pill)
        LinearLayout segBar = new LinearLayout(this);
        segBar.setOrientation(LinearLayout.HORIZONTAL);
        segBar.setPadding(dp(4), dp(4), dp(4), dp(4));
        segBar.setBackground(ThemeConfig.createGlassCardDrawable(
                999f, p.wellBackground, p.wellBackground, p.borderSubtle, 1));

        btSegBtn = new TextView(this);
        btSegBtn.setText(I18n.tr(this, I18n.KEY_BT_MUSIC));
        btSegBtn.setGravity(Gravity.CENTER);
        btSegBtn.setPadding(dp(10), dp(14), dp(10), dp(14));
        btSegBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f);
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
                CarIntentRouter.launchMusicSourceApp(MainActivity.this, HeadUnitMediaBridge.SOURCE_BT);
                return true;
            }
        });

        usbSegBtn = new TextView(this);
        usbSegBtn.setText(I18n.tr(this, I18n.KEY_USB_MUSIC));
        usbSegBtn.setGravity(Gravity.CENTER);
        usbSegBtn.setPadding(dp(10), dp(14), dp(10), dp(14));
        usbSegBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f);
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
                CarIntentRouter.launchMusicSourceApp(MainActivity.this, HeadUnitMediaBridge.SOURCE_USB);
                return true;
            }
        });

        segBar.addView(btSegBtn, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        segBar.addView(usbSegBtn, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        card.addView(segBar);

        // Middle Track Info Row
        LinearLayout midRow = new LinearLayout(this);
        midRow.setOrientation(LinearLayout.HORIZONTAL);
        midRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams midLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);

        FrameLayout albumArt = new FrameLayout(this);
        albumArt.setBackground(ThemeConfig.createGlassCardDrawable(
                dpf(22f), 0x66FFFFFF, p.accentPrimary, p.borderLuminous, 1));
        View musicVec = VectorIcons.createIconView(this, VectorIcons.ICON_MUSIC, p.textPrimary, 34);
        albumArt.addView(musicVec, new FrameLayout.LayoutParams(dp(34), dp(34), Gravity.CENTER));
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(dp(76), dp(76));
        alp.rightMargin = dp(18);
        albumArt.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                CarIntentRouter.launchMusicSourceApp(MainActivity.this, musicLaunchSource());
            }
        });
        midRow.addView(albumArt, alp);

        LinearLayout textCol = new LinearLayout(this);
        textCol.setOrientation(LinearLayout.VERTICAL);

        songTitleText = new TextView(this);
        songTitleText.setText(I18n.tr(this, I18n.KEY_MUSIC_READY));
        songTitleText.setTextColor(p.textPrimary);
        songTitleText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f);
        songTitleText.setTypeface(Typeface.DEFAULT_BOLD);
        songTitleText.setMaxLines(2);
        songTitleText.setEllipsize(TextUtils.TruncateAt.END);
        textCol.addView(songTitleText);

        songSubText = new TextView(this);
        songSubText.setText("YouTube Music — iPhone Bluetooth");
        songSubText.setTextColor(p.textSecondary);
        songSubText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f);
        songSubText.setSingleLine(true);
        songSubText.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        subLp.topMargin = dp(6);
        textCol.addView(songSubText, subLp);

        midRow.addView(textCol, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        card.addView(midRow, midLp);

        // Liquid Transport Controls: [Prev] [Play/Pause] [Next]
        LinearLayout transport = new LinearLayout(this);
        transport.setOrientation(LinearLayout.HORIZONTAL);
        transport.setGravity(Gravity.CENTER);

        View prevBtn = createLiquidTransportButton(p, VectorIcons.ICON_PREV, false, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                runMediaCommand(HeadUnitAdapter.MediaCommand.PREVIOUS);
            }
        });
        playPauseBtn = new TextView(this);
        FrameLayout playWrap = createLiquidPlayButton(p, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                runMediaCommand(HeadUnitAdapter.MediaCommand.PLAY_PAUSE);
            }
        });
        View nextBtn = createLiquidTransportButton(p, VectorIcons.ICON_NEXT, false, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                runMediaCommand(HeadUnitAdapter.MediaCommand.NEXT);
            }
        });

        LinearLayout.LayoutParams t1 = new LinearLayout.LayoutParams(0, dp(spec.transportHeightDp), 1f);
        t1.rightMargin = dp(12);
        LinearLayout.LayoutParams t2 = new LinearLayout.LayoutParams(0, dp(spec.transportHeightDp), 1f);
        t2.rightMargin = dp(12);
        LinearLayout.LayoutParams t3 = new LinearLayout.LayoutParams(0, dp(spec.transportHeightDp), 1f);

        transport.addView(prevBtn, t1);
        transport.addView(playWrap, t2);
        transport.addView(nextBtn, t3);
        card.addView(transport);

        return card;
    }

    private View playIconView;

    private FrameLayout createLiquidPlayButton(ThemeConfig.Palette p, View.OnClickListener onClick) {
        FrameLayout wrap = new FrameLayout(this);
        wrap.setBackground(ThemeConfig.createInteractiveTileDrawable(
                999f, p.accentPrimary, p.tilePressed, p.borderLuminous, p.textPrimary));
        playIconView = VectorIcons.createIconView(this, VectorIcons.ICON_PLAY, p.textPrimary, 28);
        wrap.addView(playIconView, new FrameLayout.LayoutParams(dp(28), dp(28), Gravity.CENTER));
        wrap.setOnClickListener(onClick);
        return wrap;
    }

    private View createLiquidTransportButton(
            ThemeConfig.Palette p, int iconId, boolean primary, View.OnClickListener onClick) {
        FrameLayout wrap = new FrameLayout(this);
        int fill = primary ? p.accentPrimary : p.tileDefault;
        wrap.setBackground(ThemeConfig.createInteractiveTileDrawable(
                999f, fill, p.tilePressed, p.borderLuminous, p.accentPrimary));
        View icon = VectorIcons.createIconView(this, iconId, p.textPrimary, 26);
        wrap.addView(icon, new FrameLayout.LayoutParams(dp(26), dp(26), Gravity.CENTER));
        wrap.setOnClickListener(onClick);
        return wrap;
    }

    private View buildRightBentoColumn(final ThemeConfig.Palette p) {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);

        // Top Liquid Pane: Large Date + Large Google Maps & Enter Destination Buttons
        LinearLayout navCard = new LinearLayout(this);
        navCard.setOrientation(LinearLayout.VERTICAL);
        navCard.setPadding(dp(20), dp(18), dp(20), dp(18));
        navCard.setBackground(ThemeConfig.createGlassCardDrawable(
                dpf(24f), p.cardTop, p.cardBottom, p.borderLuminous, 1));
        LinearLayout.LayoutParams navLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 1.12f);
        navLp.bottomMargin = dp(14);

        LinearLayout dateBlock = new LinearLayout(this);
        dateBlock.setOrientation(LinearLayout.VERTICAL);
        dateBlock.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);

        dateHeaderText = new TextView(this);
        dateHeaderText.setText(VietnameseDateFormatter.formatToday(new Date()));
        dateHeaderText.setTextColor(p.textPrimary);
        dateHeaderText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 30f);
        dateHeaderText.setTypeface(Typeface.DEFAULT_BOLD);
        dateBlock.addView(dateHeaderText);
        navCard.addView(dateBlock, dlp);

        LinearLayout navButtonsRow = new LinearLayout(this);
        navButtonsRow.setOrientation(LinearLayout.HORIZONTAL);

        View mapsLiveBtn = createLiquidActionLens(
                p,
                I18n.tr(this, I18n.KEY_GOOGLE_MAPS),
                VectorIcons.ICON_GOOGLE_MAPSPIN,
                p.accentPrimary,
                p.borderLuminous,
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        CarIntentRouter.launchGoogleMapsLive(MainActivity.this);
                    }
                });
        View plusCodeBtn = createLiquidActionLens(
                p,
                I18n.tr(this, I18n.KEY_PLUS_CODE),
                VectorIcons.ICON_HASH,
                p.tileDefault,
                p.borderLuminous,
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        PlusCodeDialog.show(MainActivity.this, false);
                    }
                });

        LinearLayout.LayoutParams b1 = new LinearLayout.LayoutParams(0, dp(spec.navButtonHeightDp), 1f);
        b1.rightMargin = dp(10);
        LinearLayout.LayoutParams b2 = new LinearLayout.LayoutParams(0, dp(spec.navButtonHeightDp), 1f);
        navButtonsRow.addView(mapsLiveBtn, b1);
        navButtonsRow.addView(plusCodeBtn, b2);
        navCard.addView(navButtonsRow);
        col.addView(navCard, navLp);

        // Bottom Liquid Pane: Cockpit Lens Controls (Removed "Độ sáng" button as requested)
        LinearLayout utilCard = new LinearLayout(this);
        utilCard.setOrientation(LinearLayout.VERTICAL);
        utilCard.setPadding(dp(20), dp(16), dp(20), dp(16));
        utilCard.setBackground(ThemeConfig.createGlassCardDrawable(
                dpf(24f), p.cardTop, p.cardBottom, p.borderLuminous, 1));
        LinearLayout.LayoutParams utilLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0.88f);

        LinearLayout row1 = new LinearLayout(this);
        row1.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams r1Lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        r1Lp.bottomMargin = dp(10);

        View camBtn = createLiquidActionLens(p, I18n.tr(this, I18n.KEY_DASHCAM), VectorIcons.ICON_CAMERA, p.tileDefault, p.borderLuminous, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                CarIntentRouter.launchDashcam(MainActivity.this);
            }
        });
        View eqBtn = createLiquidActionLens(p, I18n.tr(this, I18n.KEY_EQUALIZER), VectorIcons.ICON_EQUALIZER, p.tileDefault, p.borderLuminous, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                CarIntentRouter.launchEqualizer(MainActivity.this);
            }
        });
        LinearLayout.LayoutParams u1 = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
        u1.rightMargin = dp(10);
        LinearLayout.LayoutParams u2 = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
        row1.addView(camBtn, u1);
        row1.addView(eqBtn, u2);
        utilCard.addView(row1, r1Lp);

        View nightBtn = createLiquidActionLens(p, I18n.tr(this, I18n.KEY_NIGHT_SCREEN), VectorIcons.ICON_MOON, p.tileDefault, p.borderLuminous, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(MainActivity.this, ClockActivity.class));
            }
        });
        LinearLayout.LayoutParams r2Lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        utilCard.addView(nightBtn, r2Lp);

        col.addView(utilCard, utilLp);
        return col;
    }

    private View createLiquidActionLens(
            ThemeConfig.Palette p, String label, int iconId, int normalFill, int borderColor, View.OnClickListener onClick) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(12), dp(10), dp(12), dp(10));
        row.setMinimumHeight(dp(spec.actionRowMinHeightDp));
        row.setBackground(ThemeConfig.createInteractiveTileDrawable(
                dpf(18f), normalFill, p.tilePressed, borderColor, p.accentPrimary));
        row.setOnClickListener(onClick);

        if (iconId == VectorIcons.ICON_GOOGLE_MAPSPIN) {
            try {
                String mapsPkg = CarIntentRouter.getTargetPackage(
                        this, com.cleanyourcar.launcher.config.RoleSelection.Role.NAVIGATION);
                android.graphics.drawable.Drawable realIcon = getPackageManager().getApplicationIcon(mapsPkg);
                if (realIcon != null) {
                    ImageView iv = new ImageView(this);
                    iv.setImageDrawable(realIcon);
                    LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(dp(32), dp(32));
                    ilp.rightMargin = dp(8);
                    row.addView(iv, ilp);
                } else {
                    View icon = VectorIcons.createIconView(this, iconId, p.textPrimary, 24);
                    LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(dp(24), dp(24));
                    ilp.rightMargin = dp(8);
                    row.addView(icon, ilp);
                }
            } catch (Throwable ignored) {
                View icon = VectorIcons.createIconView(this, iconId, p.textPrimary, 24);
                LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(dp(24), dp(24));
                ilp.rightMargin = dp(8);
                row.addView(icon, ilp);
            }
        } else {
            View icon = VectorIcons.createIconView(this, iconId, p.textPrimary, 24);
            LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(dp(24), dp(24));
            ilp.rightMargin = dp(8);
            row.addView(icon, ilp);
        }

        TextView tv = new TextView(this);
        tv.setText(label);
        tv.setTextColor(p.textPrimary);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16.5f);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setMaxLines(2);
        row.addView(tv, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        return row;
    }

    private View buildAppGridView(ThemeConfig.Palette p) {
        LinearLayout wrapper = new LinearLayout(this);
        wrapper.setOrientation(LinearLayout.VERTICAL);
        wrapper.setPadding(dp(7), dp(7), dp(7), dp(14));

        appsGridLayout = new GridLayout(this);
        ScrollView gridScroll = AdaptiveUi.fillingScroll(this, appsGridLayout);
        LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        wrapper.addView(gridScroll, glp);

        pageDotsText = new TextView(this);
        pageDotsText.setTextColor(p.textSecondary);
        pageDotsText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
        pageDotsText.setGravity(Gravity.CENTER);
        wrapper.addView(pageDotsText);

        return wrapper;
    }

    private void populateAppGrid() {
        if (appsGridLayout == null) return;
        ThemeConfig.Palette p = ThemeConfig.getActivePalette(this);
        int cols = spec.gridColumns;
        appsGridLayout.removeAllViews();
        appsGridLayout.setColumnCount(cols);

        List<AppRepository.AppEntry> apps = appRepository.loadVisibleApps(this);
        int appSlotsPerPage = spec.appSlotsPerPage;
        int totalPages = Math.max(1, (int) Math.ceil((double) Math.max(1, apps.size()) / appSlotsPerPage));
        if (currentGridSubPage >= totalPages) {
            currentGridSubPage = totalPages - 1;
        }
        int start = currentGridSubPage * appSlotsPerPage;
        int end = Math.min(apps.size(), start + appSlotsPerPage);

        for (int i = start; i < end; i++) {
            final AppRepository.AppEntry entry = apps.get(i);
            View tile = createAppTileView(p, entry.label, entry.icon, VectorIcons.ICON_GRID, new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    // A navigation app opened from the grid keeps the framing overlay.
                    CarIntentRouter.launchGridApp(MainActivity.this, entry.packageName);
                }
            }, new View.OnLongClickListener() {
                @Override
                public boolean onLongClick(View v) {
                    openAppLibraryModal();
                    return true;
                }
            });
            addTileToGrid(tile, cols);
        }

        // Always include [Thư viện App] and [Tùy biến UI] tiles with crisp VectorIcons
        View libTile = createAppTileView(p, I18n.tr(this, I18n.KEY_APP_LIBRARY), null, VectorIcons.ICON_PLUS, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openAppLibraryModal();
            }
        }, null);
        addTileToGrid(libTile, cols);

        View settingsTile = createAppTileView(p, I18n.tr(this, I18n.KEY_UI_SETTINGS), null, VectorIcons.ICON_SLIDERS, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openSettingsModal();
            }
        }, null);
        addTileToGrid(settingsTile, cols);

        if (pageDotsText != null) {
            pageDotsText.setText("Trang " + (currentGridSubPage + 1) + "/" + totalPages
                    + " — Vuốt ngang để về màn hình chính");
            pageDotsText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
        }
    }

    private void addTileToGrid(View tile, int cols) {
        GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
        lp.width = 0;
        lp.height = dp(spec.gridTileHeightDp);
        lp.columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1, 1f);
        lp.rowSpec = GridLayout.spec(GridLayout.UNDEFINED, 1);
        lp.setMargins(dp(7), dp(7), dp(7), dp(7));
        appsGridLayout.addView(tile, lp);
    }

    private View createAppTileView(
            ThemeConfig.Palette p,
            String label,
            android.graphics.drawable.Drawable iconDrawable,
            int fallbackIconId,
            View.OnClickListener onClick,
            View.OnLongClickListener onLongClick) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        box.setPadding(dp(12), dp(16), dp(12), dp(16));
        box.setBackground(ThemeConfig.createInteractiveTileDrawable(
                dpf(24f), p.cardTop, p.tilePressed, p.borderLuminous, p.accentPrimary));
        box.setOnClickListener(onClick);
        if (onLongClick != null) {
            box.setOnLongClickListener(onLongClick);
        }

        if (iconDrawable != null) {
            ImageView iv = new ImageView(this);
            iv.setImageDrawable(iconDrawable);
            box.addView(iv, new LinearLayout.LayoutParams(dp(78), dp(78)));
        } else {
            FrameLayout lens = new FrameLayout(this);
            lens.setBackground(ThemeConfig.createGlassCardDrawable(
                    999f, 0x66FFFFFF, p.accentPrimary, p.borderLuminous, 1));
            View vec = VectorIcons.createIconView(this, fallbackIconId, p.textPrimary, 34);
            lens.addView(vec, new FrameLayout.LayoutParams(dp(34), dp(34), Gravity.CENTER));
            box.addView(lens, new LinearLayout.LayoutParams(dp(78), dp(78)));
        }

        TextView tv = new TextView(this);
        tv.setText(label);
        tv.setTextColor(p.textPrimary);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16.5f);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setGravity(Gravity.CENTER);
        tv.setSingleLine(true);
        tv.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp.topMargin = dp(10);
        box.addView(tv, tlp);

        return box;
    }

    private void openAppLibraryModal() {
        AppLibraryDialog.show(this, appRepository, new AppLibraryDialog.OnChangedListener() {
            @Override
            public void onAppsUpdated() {
                populateAppGrid();
            }
        });
    }

    private void openSettingsModal() {
        LauncherSettingsDialog.show(this, new LauncherSettingsDialog.OnSettingsChangedListener() {
            @Override
            public void onSettingsChanged() {
                spec = currentSpec();
                rebuildFullUi();
            }
        });
    }

    private void updateClockAndDate() {
        Date now = new Date();
        if (dockClockText != null) {
            dockClockText.setText(new SimpleDateFormat("HH:mm", Locale.getDefault()).format(now));
        }
        if (dateHeaderText != null) {
            dateHeaderText.setText(VietnameseDateFormatter.formatToday(now));
        }
    }

    private void runMediaCommand(HeadUnitAdapter.MediaCommand command) {
        HeadUnitAdapter.CommandResult result = integration != null
                ? integration.execute(command)
                : HeadUnitAdapter.CommandResult.unavailable(
                        HeadUnitAdapter.CommandResult.REASON_NOT_STARTED);
        MediaStateText.reportResult(this, result);
    }

    private int musicLaunchSource() {
        return HeadUnitMediaBridge.toLegacySource(integration != null
                ? integration.musicLaunchSource() : MediaState.Source.BLUETOOTH);
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
        if (playIconView != null) {
            playIconView.setBackground(VectorIcons.createDrawable(
                    state.playback == MediaState.Playback.PLAYING
                            ? VectorIcons.ICON_PAUSE : VectorIcons.ICON_PLAY,
                    p.textPrimary,
                    28));
        }
        if (dockStatusText != null) {
            dockStatusText.setText(MediaStateText.connection(state, "Bluetooth",
                    I18n.tr(this, I18n.KEY_OFFLINE_STATUS)));
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
    }

    @Override
    public void onHealthChanged(HeadUnitAdapter.Health health, String reason) {
        MediaStateText.reportHealth(this, health, reason);
    }

    private int dp(float value) {
        return AdaptiveUi.dp(this, value);
    }

    private float dpf(float value) {
        return AdaptiveUi.dpf(this, value);
    }
}
