/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.wm.shell.bubbles;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Insets;
import android.graphics.Rect;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.WindowMetrics;

import androidx.annotation.VisibleForTesting;

/**
 * Keeps track of display size, configuration, and specific bubble sizes. One place for all
 * placement and positioning calculations to refer to.
 */
public class BubblePositioner {

    private WindowManager mWindowManager;
    private Rect mPositionRect;
    private int mOrientation;
    private Insets mInsets;

    public BubblePositioner(Context context, WindowManager windowManager) {
        mWindowManager = windowManager;
        update(Configuration.ORIENTATION_UNDEFINED);
    }

    public void update(int orientation) {
        WindowMetrics windowMetrics = mWindowManager.getCurrentWindowMetrics();
        mPositionRect = new Rect(windowMetrics.getBounds());
        WindowInsets metricInsets = windowMetrics.getWindowInsets();

        Insets insets = metricInsets.getInsetsIgnoringVisibility(WindowInsets.Type.navigationBars()
                | WindowInsets.Type.statusBars()
                | WindowInsets.Type.displayCutout());
        update(orientation, insets, windowMetrics.getBounds());
    }

    @VisibleForTesting
    public void update(int orientation, Insets insets, Rect bounds) {
        mOrientation = orientation;
        mInsets = insets;

        mPositionRect = new Rect(bounds);
        mPositionRect.left += mInsets.left;
        mPositionRect.top += mInsets.top;
        mPositionRect.right -= mInsets.right;
        mPositionRect.bottom -= mInsets.bottom;
    }

    /**
     * @return a rect of available screen space for displaying bubbles in the correct orientation,
     * accounting for system bars and cutouts.
     */
    public Rect getAvailableRect() {
        return mPositionRect;
    }

    /**
     * @return the current orientation.
     */
    public int getOrientation() {
        return mOrientation;
    }

    /**
     * @return the relevant insets (status bar, nav bar, cutouts).
     */
    public Insets getInsets() {
        return mInsets;
    }
}
