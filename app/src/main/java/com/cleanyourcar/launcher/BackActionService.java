package com.cleanyourcar.launcher;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.view.accessibility.AccessibilityEvent;

/**
 * Lightweight AccessibilityService allowing the floating CarPlay Dock's [↩ Back] button
 * to dispatch a real system GLOBAL_ACTION_BACK to Google Maps (or any foreground app).
 */
public class BackActionService extends AccessibilityService {
    private static volatile BackActionService instance;

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
    }

    @Override
    public boolean onUnbind(Intent intent) {
        instance = null;
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        instance = null;
        super.onDestroy();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
    }

    @Override
    public void onInterrupt() {
    }

    public static boolean performSystemBack() {
        BackActionService svc = instance;
        if (svc != null) {
            try {
                return svc.performGlobalAction(GLOBAL_ACTION_BACK);
            } catch (Throwable ignored) {
            }
        }
        return false;
    }
}
