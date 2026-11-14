package com.painel.flutuante;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

public class PanicAccessibilityService extends AccessibilityService {

    private static final long MAX_GAP_MS = 700L;
    private int count = 0;
    private long lastPress = 0;

    @Override
    protected void onServiceConnected() {
        AccessibilityServiceInfo info = getServiceInfo();
        if (info != null) {
            info.flags |= AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS;
            setServiceInfo(info);
        }
    }

    @Override
    protected boolean onKeyEvent(KeyEvent event) {
        if (event.getKeyCode() == KeyEvent.KEYCODE_VOLUME_DOWN
                && event.getAction() == KeyEvent.ACTION_DOWN
                && event.getRepeatCount() == 0) {

            if (FloatingService.running && FloatingService.minimized) {
                long now = SystemClock.uptimeMillis();
                if (now - lastPress > MAX_GAP_MS) count = 0;
                lastPress = now;
                count++;

                if (count >= 3) {
                    count = 0;
                    FloatingService s = FloatingService.instance;
                    if (s != null) s.triggerPanic();
                    return true;
                }
            } else {
                count = 0;
            }
        }
        return false;
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) { }

    @Override
    public void onInterrupt() { }
}
