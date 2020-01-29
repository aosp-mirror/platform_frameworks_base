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
import android.view.View;
import android.view.WindowInsets;
import android.widget.FrameLayout;

/**
 * Status bar view.
 */
public class StatusBarWindowView extends FrameLayout {

    public static final String TAG = "PhoneStatusBarWindowView";
    public static final boolean DEBUG = StatusBar.DEBUG;

    private int mLeftInset = 0;
    private int mRightInset = 0;
    private int mTopInset = 0;

    public StatusBarWindowView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    public WindowInsets onApplyWindowInsets(WindowInsets windowInsets) {
        final Insets insets = windowInsets.getInsetsIgnoringVisibility(systemBars());
        mLeftInset = 0;
        mRightInset = 0;
        mTopInset = 0;
        DisplayCutout displayCutout = getRootWindowInsets().getDisplayCutout();
        if (displayCutout != null) {
            mTopInset = displayCutout.getWaterfallInsets().top;
            mLeftInset = displayCutout.getSafeInsetLeft();
            mRightInset = displayCutout.getSafeInsetRight();
        }
        mLeftInset = Math.max(insets.left, mLeftInset);
        mRightInset = Math.max(insets.right, mRightInset);
        applyMargins();
        return windowInsets;
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
    public static Pair<Integer, Integer> paddingNeededForCutoutAndRoundedCorner(
            DisplayCutout cutout, Pair<Integer, Integer> cornerCutoutPadding,
            int roundedCornerContentPadding) {
        if (cutout == null) {
            return new Pair<>(roundedCornerContentPadding, roundedCornerContentPadding);
        }

        // compute the padding needed for corner cutout.
        final int leftMargin = cutout.getSafeInsetLeft();
        final int rightMargin = cutout.getSafeInsetRight();
        int leftCornerCutoutPadding = 0;
        int rightCornerCutoutPadding = 0;
        if (cornerCutoutPadding != null) {
            if (cornerCutoutPadding.first > leftMargin) {
                leftCornerCutoutPadding = cornerCutoutPadding.first - leftMargin;
            }
            if (cornerCutoutPadding.second > rightMargin) {
                rightCornerCutoutPadding = cornerCutoutPadding.second - rightMargin;
            }
        }

        // compute the padding needed for rounded corner
        int leftRoundedCornerPadding = 0;
        int rightRoundedCornerPadding = 0;
        if (roundedCornerContentPadding > leftMargin) {
            leftRoundedCornerPadding = roundedCornerContentPadding - leftMargin;
        }
        if (roundedCornerContentPadding > rightMargin) {
            rightRoundedCornerPadding = roundedCornerContentPadding - rightMargin;
        }

        return new Pair<>(
                Math.max(leftCornerCutoutPadding, leftRoundedCornerPadding),
                Math.max(rightCornerCutoutPadding, rightRoundedCornerPadding));
    }

    /**
     * Compute the corner cutout margins
     *
     * @param cutout
     * @param display
     * @return
     */
    public static Pair<Integer, Integer> cornerCutoutMargins(DisplayCutout cutout,
            Display display) {
        if (cutout == null) {
            return null;
        }
        Point size = new Point();
        display.getRealSize(size);

        Rect bounds = new Rect();
        boundsFromDirection(cutout, Gravity.TOP, bounds);

        if (bounds.left <= 0) {
            return new Pair<>(bounds.right, 0);
        }
        if (bounds.right >= size.x) {
            return new Pair<>(0, size.x - bounds.left);
        }
        return null;
    }
}
