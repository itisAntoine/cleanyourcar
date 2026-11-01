package com.cleanyourcar.launcher;

import android.app.Activity;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
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
 * State 6: Night Clock Screensaver.
 * Pure black (#000000) anti-glare display with large thin time, Vietnamese date,
 * and current song title; single tap anywhere returns to CleanYourCar.
 */
public class ClockActivity extends Activity implements HeadUnitAdapter.Listener {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private IntegrationController integration;
    private TextView clockText;
    private TextView subText;

    private final Runnable tickRunnable = new Runnable() {
        @Override
        public void run() {
            updateClock();
            handler.postDelayed(this, 10_000L);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setBackgroundColor(0xFF000000);
        root.setFitsSystemWindows(true);
        root.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });

        clockText = new TextView(this);
        clockText.setTextColor(0xD9FFFFFF);
        clockText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 76f);
        clockText.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        clockText.setGravity(Gravity.CENTER);
        root.addView(clockText);

        subText = new TextView(this);
        subText.setTextColor(0x73B8C4E0);
        subText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f);
        subText.setGravity(Gravity.CENTER);
        root.addView(subText);

        TextView hint = new TextView(this);
        hint.setText("(Chạm bất kỳ đâu để quay lại CarPlay)");
        hint.setTextColor(0x40FFFFFF);
        hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        hint.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        hlp.topMargin = dp(24);
        root.addView(hint, hlp);

        setContentView(root);
        updateClock();
    }

    @Override
    protected void onResume() {
        super.onResume();
        integration = IntegrationController.create(this);
        integration.start(this, this);
        handler.post(tickRunnable);
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(tickRunnable);
        if (integration != null) {
            integration.stop();
        }
        super.onPause();
    }

    private void updateClock() {
        Date now = new Date();
        SimpleDateFormat tf = new SimpleDateFormat("HH:mm", Locale.getDefault());
        clockText.setText(tf.format(now));
        MediaState st = integration != null ? integration.mediaState() : MediaState.unknown();
        subText.setText(VietnameseDateFormatter.formatToday(now) + "  •  🎵 "
                + MediaStateText.title(st, I18n.tr(this, I18n.KEY_MUSIC_READY)));
    }

    @Override
    public void onMediaStateChanged(MediaState state) {
        updateClock();
    }

    @Override
    public void onReverseStateChanged(MediaState.ReverseState state) {
        if (state == MediaState.ReverseState.ACTIVE) {
            finish();
        }
    }

    @Override
    public void onHealthChanged(HeadUnitAdapter.Health health, String reason) {
    }

    private int dp(float value) {
        return AdaptiveUi.dp(this, value);
    }
}
