/*
 * Copyright (C) 2012 The Android Open Source Project
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

package com.android.systemui.shade;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.widget.FrameLayout;

import com.android.systemui.R;
import com.android.systemui.statusbar.phone.TapAgainView;

/** The shade view. */
public final class NotificationPanelView extends FrameLayout {
    static final boolean DEBUG = false;

    private final Paint mAlphaPaint = new Paint();

    private int mCurrentPanelAlpha;
    private boolean mDozing;
    private RtlChangeListener mRtlChangeListener;
    private NotificationPanelViewController.TouchHandler mTouchHandler;
    private OnConfigurationChangedListener mOnConfigurationChangedListener;

    public NotificationPanelView(Context context, AttributeSet attrs) {
        super(context, attrs);
        setWillNotDraw(!DEBUG);
        mAlphaPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.MULTIPLY));

        setBackgroundColor(Color.TRANSPARENT);
    }

    @Override
    public void onRtlPropertiesChanged(int layoutDirection) {
        if (mRtlChangeListener != null) {
            mRtlChangeListener.onRtlPropertielsChanged(layoutDirection);
        }
    }

    @Override
    public boolean shouldDelayChildPressedState() {
        return true;
    }

    @Override
    protected void dispatchDraw(Canvas canvas) {
        super.dispatchDraw(canvas);
        if (mCurrentPanelAlpha != 255) {
            canvas.drawRect(0, 0, canvas.getWidth(), canvas.getHeight(), mAlphaPaint);
        }
    }

    float getCurrentPanelAlpha() {
        return mCurrentPanelAlpha;
    }

    void setPanelAlphaInternal(float alpha) {
        mCurrentPanelAlpha = (int) alpha;
        mAlphaPaint.setARGB(mCurrentPanelAlpha, 255, 255, 255);
        invalidate();
    }

    public void setDozing(boolean dozing) {
        mDozing = dozing;
    }

    @Override
    public boolean hasOverlappingRendering() {
        return !mDozing;
    }

    void setRtlChangeListener(RtlChangeListener listener) {
        mRtlChangeListener = listener;
    }

    public TapAgainView getTapAgainView() {
        return findViewById(R.id.shade_falsing_tap_again);
    }

    /** Sets the touch handler for this view. */
    public void setOnTouchListener(NotificationPanelViewController.TouchHandler touchHandler) {
        super.setOnTouchListener(touchHandler);
        mTouchHandler = touchHandler;
    }

    void setOnConfigurationChangedListener(OnConfigurationChangedListener listener) {
        mOnConfigurationChangedListener = listener;
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent event) {
        return mTouchHandler.onInterceptTouchEvent(event);
    }

    @Override
    public void dispatchConfigurationChanged(Configuration newConfig) {
        super.dispatchConfigurationChanged(newConfig);
        mOnConfigurationChangedListener.onConfigurationChanged(newConfig);
    }

    /** Callback for right-to-left setting changes. */
    interface RtlChangeListener {
        /** Called when right-to-left setting changes. */
        void onRtlPropertielsChanged(int layoutDirection);
    }

    /** Callback for config changes. */
    interface OnConfigurationChangedListener {
        /** Called when configuration changes. */
        void onConfigurationChanged(Configuration newConfig);
    }
}
