/*
 * Copyright (C) 2008 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui.statusbar.phone;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.accessibility.AccessibilityEvent;
import android.widget.FrameLayout;

import com.android.systemui.R;
import com.android.systemui.statusbar.policy.FixedSizeDrawable;

public class PhoneStatusBarView extends FrameLayout {
    private static final String TAG = "PhoneStatusBarView";

    static final int DIM_ANIM_TIME = 400;
    
    PhoneStatusBar mService;
    boolean mTracking;
    int mStartX, mStartY;
    ViewGroup mNotificationIcons;
    ViewGroup mStatusIcons;
    
    boolean mNightMode = false;
    int mStartAlpha = 0, mEndAlpha = 0;
    long mEndTime = 0;

    Rect mButtonBounds = new Rect();
    boolean mCapturingEvents = true;

    public PhoneStatusBarView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        mNotificationIcons = (ViewGroup)findViewById(R.id.notificationIcons);
        mStatusIcons = (ViewGroup)findViewById(R.id.statusIcons);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        mService.onBarViewAttached();
    }
    
    @Override
    protected void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        mService.updateDisplaySize();
        boolean nightMode = (newConfig.uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
        if (mNightMode != nightMode) {
            mNightMode = nightMode;
            mStartAlpha = getCurAlpha();
            mEndAlpha = mNightMode ? 0x80 : 0x00;
            mEndTime = SystemClock.uptimeMillis() + DIM_ANIM_TIME;
            invalidate();
        }
    }

    int getCurAlpha() {
        long time = SystemClock.uptimeMillis();
        if (time > mEndTime) {
            return mEndAlpha;
        }
        return mEndAlpha
                - (int)(((mEndAlpha-mStartAlpha) * (mEndTime-time) / DIM_ANIM_TIME));
    }
    
    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        mService.updateExpandedViewPos(PhoneStatusBar.EXPANDED_LEAVE_ALONE);
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        super.onLayout(changed, l, t, r, b);
    }

    @Override
    protected void dispatchDraw(Canvas canvas) {
        super.dispatchDraw(canvas);
        int alpha = getCurAlpha();
        if (alpha != 0) {
            canvas.drawARGB(alpha, 0, 0, 0);
        }
        if (alpha != mEndAlpha) {
            invalidate();
        }
    }

    /**
     * Gets the left position of v in this view.  Throws if v is not
     * a child of this.
     */
    private int getViewOffset(View v) {
        int offset = 0;
        while (v != this) {
            offset += v.getLeft();
            ViewParent p = v.getParent();
            if (v instanceof View) {
                v = (View)p;
            } else {
                throw new RuntimeException(v + " is not a child of " + this);
            }
        }
        return offset;
    }

    /**
     * Ensure that, if there is no target under us to receive the touch,
     * that we process it ourself.  This makes sure that onInterceptTouchEvent()
     * is always called for the entire gesture.
     */
    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (!mCapturingEvents) {
            return false;
        }
        if (event.getAction() != MotionEvent.ACTION_DOWN) {
            mService.interceptTouchEvent(event);
        }
        return true;
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent event) {
        if (event.getAction() == MotionEvent.ACTION_DOWN) {
            if (mButtonBounds.contains((int)event.getX(), (int)event.getY())) {
                mCapturingEvents = false;
                return false;
            }
        }
        mCapturingEvents = true;
        return mService.interceptTouchEvent(event)
                ? true : super.onInterceptTouchEvent(event);
    }

    @Override
    public boolean onRequestSendAccessibilityEvent(View child, AccessibilityEvent event) {
        if (super.onRequestSendAccessibilityEvent(child, event)) {
            // The status bar is very small so augment the view that the user is touching
            // with the content of the status bar a whole. This way an accessibility service
            // may announce the current item as well as the entire content if appropriate.
            AccessibilityEvent record = AccessibilityEvent.obtain();
            onInitializeAccessibilityEvent(record);
            dispatchPopulateAccessibilityEvent(record);
            event.appendRecord(record);
            return true;
        }
        return false;
    }
}
