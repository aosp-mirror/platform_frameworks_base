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

package com.android.systemui.statusbar.phone;

import static android.view.MotionEvent.ACTION_DOWN;
import static android.view.MotionEvent.ACTION_MOVE;
import static android.view.MotionEvent.ACTION_UP;
import static android.view.WindowInsets.Type.systemBars;

import static com.android.systemui.ScreenDecorations.DisplayCutoutView.boundsFromDirection;

import android.content.Context;
import android.graphics.Insets;
import android.graphics.Point;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.util.Pair;
import android.view.Display;
import android.view.DisplayCutout;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowInsets;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;

import com.android.systemui.util.leak.RotationUtils;

/**
 * Status bar view.
 */
public class StatusBarWindowView extends FrameLayout {

    public static final String TAG = "PhoneStatusBarWindowView";
    public static final boolean DEBUG = StatusBar.DEBUG;

    private int mLeftInset = 0;
    private int mRightInset = 0;
    private int mTopInset = 0;

    private float mTouchDownY = 0;

    public StatusBarWindowView(Context context, AttributeSet attrs) {
        super(context, attrs);
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

    /**
     * Compute the padding needed for status bar related views, e.g., PhoneStatusBar,
     * QuickStatusBarHeader and KeyguardStatusBarView).
     *
     * @param cutout
     * @param cornerCutoutPadding
     * @param roundedCornerContentPadding
     * @return
     */
    @NonNull
    public static Pair<Integer, Integer> paddingNeededForCutoutAndRoundedCorner(
            DisplayCutout cutout, Pair<Integer, Integer> cornerCutoutPadding,
            int roundedCornerContentPadding) {
        if (cutout == null) {
            return new Pair<>(roundedCornerContentPadding, roundedCornerContentPadding);
        }

        // padding needed for corner cutout.
        int leftCornerCutoutPadding = cutout.getSafeInsetLeft();
        int rightCornerCutoutPadding = cutout.getSafeInsetRight();
        if (cornerCutoutPadding != null) {
            leftCornerCutoutPadding = Math.max(leftCornerCutoutPadding, cornerCutoutPadding.first);
            rightCornerCutoutPadding = Math.max(rightCornerCutoutPadding,
                    cornerCutoutPadding.second);
        }

        return new Pair<>(
                Math.max(leftCornerCutoutPadding, roundedCornerContentPadding),
                Math.max(rightCornerCutoutPadding, roundedCornerContentPadding));
    }


    /**
     * Compute the corner cutout margins in portrait mode
     */
    public static Pair<Integer, Integer> cornerCutoutMargins(DisplayCutout cutout,
            Display display) {
        return statusBarCornerCutoutMargins(cutout, display, RotationUtils.ROTATION_NONE, 0);
    }

    /**
     * Compute the corner cutout margins in the given orientation (exactRotation)
     */
    public static Pair<Integer, Integer> statusBarCornerCutoutMargins(DisplayCutout cutout,
            Display display, int exactRotation, int statusBarHeight) {
        if (cutout == null) {
            return null;
        }
        Point size = new Point();
        display.getRealSize(size);

        Rect bounds = new Rect();
        switch (exactRotation) {
            case RotationUtils.ROTATION_LANDSCAPE:
                boundsFromDirection(cutout, Gravity.LEFT, bounds);
                break;
            case RotationUtils.ROTATION_SEASCAPE:
                boundsFromDirection(cutout, Gravity.RIGHT, bounds);
                break;
            case RotationUtils.ROTATION_NONE:
                boundsFromDirection(cutout, Gravity.TOP, bounds);
                break;
            case RotationUtils.ROTATION_UPSIDE_DOWN:
                // we assume the cutout is always on top in portrait mode
                return null;
        }

        if (statusBarHeight >= 0 && bounds.top > statusBarHeight) {
            return null;
        }

        if (bounds.left <= 0) {
            return new Pair<>(bounds.right, 0);
        }

        if (bounds.right >= size.x) {
            return new Pair<>(0, size.x - bounds.left);
        }

        return null;
    }
}
