package com.cleanyourcar.launcher.integration;

import android.content.Context;
import android.media.AudioManager;
import android.os.SystemClock;
import android.view.KeyEvent;

/** Standard media key dispatch through the public {@link AudioManager} API. */
public final class AudioManagerMediaKeyTransport implements GenericAndroidAdapter.MediaKeyTransport {

    @Override
    public void dispatch(Context context, int keyCode) {
        AudioManager am = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        if (am == null) {
            throw new IllegalStateException("audio service unavailable");
        }
        long now = SystemClock.uptimeMillis();
        am.dispatchMediaKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0));
        am.dispatchMediaKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0));
    }
}
