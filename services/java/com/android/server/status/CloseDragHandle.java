package com.android.server.status;

import android.content.Context;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.widget.LinearLayout;


public class CloseDragHandle extends LinearLayout {
    StatusBarService mService;

    public CloseDragHandle(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    /**
     * Ensure that, if there is no target under us to receive the touch,
     * that we process it ourself.  This makes sure that onInterceptTouchEvent()
     * is always called for the entire gesture.
     */
    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (event.getAction() != MotionEvent.ACTION_DOWN) {
            mService.interceptTouchEvent(event);
        }
        return true;
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent event) {
        return mService.interceptTouchEvent(event)
                ? true : super.onInterceptTouchEvent(event);
    }
}

