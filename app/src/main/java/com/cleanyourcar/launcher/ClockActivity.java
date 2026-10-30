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

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * State 6: Night Clock Screensaver.
 * Pure black (#000000) anti-glare display with large thin time, Vietnamese date,
 * and current song title; single tap anywhere returns to CleanYourCar.
 */
public class ClockActivity extends Activity implements HeadUnitMediaBridge.Listener {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final HeadUnitMediaBridge mediaBridge = new HeadUnitMediaBridge();
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
        hlp.topMargin = 24;
        root.addView(hint, hlp);

        setContentView(root);
        updateClock();
    }

    @Override
    protected void onResume() {
        super.onResume();
        mediaBridge.register(this, this);
        handler.post(tickRunnable);
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(tickRunnable);
        mediaBridge.unregister(this);
        super.onPause();
    }

    private void updateClock() {
        Date now = new Date();
        SimpleDateFormat tf = new SimpleDateFormat("HH:mm", Locale.getDefault());
        clockText.setText(tf.format(now));
        HeadUnitMediaBridge.MediaState st = HeadUnitMediaBridge.getSharedState();
        subText.setText(VietnameseDateFormatter.formatToday(now) + "  •  🎵 " + st.title);
    }

    @Override
    public void onMediaStateChanged(HeadUnitMediaBridge.MediaState state) {
        updateClock();
    }

    @Override
    public void onReverseGearChanged(boolean inReverse) {
        if (inReverse) {
            finish();
        }
    }
}
