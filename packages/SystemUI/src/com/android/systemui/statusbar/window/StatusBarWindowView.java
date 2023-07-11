/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.systemui.statusbar.window;

import static android.view.MotionEvent.ACTION_DOWN;
import static android.view.MotionEvent.ACTION_MOVE;
import static android.view.MotionEvent.ACTION_UP;
import static android.view.WindowInsets.Type.systemBars;

import android.content.Context;
import android.graphics.Insets;
import android.util.AttributeSet;
import android.view.DisplayCutout;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowInsets;
import android.widget.FrameLayout;

/**
 * Status bar view.
 */
public class StatusBarWindowView extends FrameLayout {

    public static final String TAG = "PhoneStatusBarWindowView";
    public static final boolean DEBUG = false;

    private int mLeftInset = 0;
    private int mRightInset = 0;
    private int mTopInset = 0;

    private float mTouchDownY = 0;

    public StatusBarWindowView(Context context, AttributeSet attrs) {
        super(context, attrs);
        setClipChildren(false);
    }

    @Override
    public WindowInsets onApplyWindowInsets(WindowInsets windowInsets) {
        final Insets insets = windowInsets.getInsetsIgnoringVisibility(systemBars());
        mLeftInset = insets.left;
        mRightInset = insets.right;
        mTopInset = 0;
        DisplayCutout displayCutout = getRootWindowInsets().getDisplayCutout();
        if (displayCutout != null) {
            mTopInset = displayCutout.getWaterfallInsets().top;
        }
        applyMargins();
        return windowInsets;
    }

    /**
     * This is specifically for pulling down the status bar as a consistent motion in the visual
     * immersive mode. In the visual immersive mode, after the system detects a system gesture
     * motion from the top, we show permanent bars, and then forward the touch events from the
     * focused window to the status bar window. However, since the first relayed event is out of
     * bound of the status bar view, in order for the touch event to be correctly dispatched down,
     * we jot down the position Y of the initial touch down event, offset it to 0 in the y-axis,
     * and calculate the movement based on first touch down position.
     */
    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        if (ev.getAction() == ACTION_DOWN && ev.getRawY() > getHeight()) {
            mTouchDownY = ev.getRawY();
            ev.setLocation(ev.getRawX(), mTopInset);
        } else if (ev.getAction() == ACTION_MOVE && mTouchDownY != 0) {
            ev.setLocation(ev.getRawX(), mTopInset + ev.getRawY() - mTouchDownY);
        } else if (ev.getAction() == ACTION_UP) {
            mTouchDownY = 0;
        }
        return super.dispatchTouchEvent(ev);
    }

    private void applyMargins() {
        final int count = getChildCount();
        for (int i = 0; i < count; i++) {
            View child = getChildAt(i);
            if (child.getLayoutParams() instanceof LayoutParams) {
                LayoutParams lp = (LayoutParams) child.getLayoutParams();
                if (lp.rightMargin != mRightInset || lp.leftMargin != mLeftInset
                        || lp.topMargin != mTopInset) {
                    lp.rightMargin = mRightInset;
                    lp.leftMargin = mLeftInset;
                    lp.topMargin = mTopInset;
                    child.requestLayout();
                }
            }
        }
    }
}
