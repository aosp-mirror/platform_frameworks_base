package com.android.server.status;

import android.content.Context;
import android.util.AttributeSet;
import android.view.Display;
import android.view.KeyEvent;
import android.view.WindowManager;
import android.widget.LinearLayout;


public class TrackingView extends LinearLayout {
    final Display mDisplay;
    StatusBarService mService;
    boolean mTracking;
    int mStartX, mStartY;

    public TrackingView(Context context, AttributeSet attrs) {
        super(context, attrs);
        mDisplay = ((WindowManager)context.getSystemService(
                Context.WINDOW_SERVICE)).getDefaultDisplay();
    }
    
    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);
        mService.updateAvailableHeight();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        boolean down = event.getAction() == KeyEvent.ACTION_DOWN;
        switch (event.getKeyCode()) {
        case KeyEvent.KEYCODE_BACK:
            if (down) {
                mService.deactivate();
            }
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        mService.onTrackingViewAttached();
    }
}
