/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.server.wm;

import static android.app.WindowConfiguration.ACTIVITY_TYPE_HOME;
import static android.view.WindowManager.LayoutParams.TYPE_INPUT_METHOD;
import static android.view.WindowManagerPolicyConstants.NAV_BAR_BOTTOM;
import static android.view.WindowManagerPolicyConstants.NAV_BAR_LEFT;
import static android.view.WindowManagerPolicyConstants.NAV_BAR_RIGHT;

import android.content.Context;
import android.graphics.Rect;

/**
 * This class acts as a proxy for Navigation Bar experiments enabled with custom overlays
 * {@see OverlayManagerService}. By default with no overlays, this class will essentially do nothing
 * and pass the original resource data back. By default the navigation bar height/width is the same
 * as the frame height/width and therefore any offsets calculated will cancel out and do nothing.
 * TODO(b/113952590): Remove class once experiment in bug is completed
 */
public class NavigationBarExperiments {

    private int mNavigationBarHeight;
    private int mNavigationBarWidth;

    /**
     * This represents the height of the navigation bar buttons. With no experiments or overlays
     * enabled, the frame height is the same as the normal navigation bar height.
     */
    private int mNavigationBarFrameHeight;

    /**
     * This represents the width of the navigation bar buttons. With no experiments or overlays
     * enabled, the frame width is the same as the normal navigation bar width.
     */
    private int mNavigationBarFrameWidth;

    /**
     * Call when configuration change to refresh resource dimensions
     * @param systemUiContext to get the resource values
     */
    public void onConfigurationChanged(Context systemUiContext) {
        // Cache all the values again
        mNavigationBarHeight = systemUiContext.getResources().getDimensionPixelSize(
                com.android.internal.R.dimen.navigation_bar_height);
        mNavigationBarWidth = systemUiContext.getResources().getDimensionPixelSize(
                com.android.internal.R.dimen.navigation_bar_width);
        mNavigationBarFrameHeight = systemUiContext.getResources().getDimensionPixelSize(
                com.android.internal.R.dimen.navigation_bar_frame_height);
        mNavigationBarFrameWidth = systemUiContext.getResources().getDimensionPixelSize(
                com.android.internal.R.dimen.navigation_bar_frame_width);
    }

    public int getNavigationBarHeight() {
        return mNavigationBarHeight;
    }

    public int getNavigationBarWidth() {
        return mNavigationBarWidth;
    }

    public int getNavigationBarFrameHeight() {
        return mNavigationBarFrameHeight;
    }

    public int getNavigationBarFrameWidth() {
        return mNavigationBarFrameWidth;
    }

    /**
     * If navigation frame width/height is different than navigation bar width/height then only
     * offset the ime's and home activity's window rects depending on the navigation bar position to
     * add a gap where the navigation bar would have been drawn. With no experiments or overlays
     * enabled, the height/width is the same as the frame height/width and the offsets calculated
     * will be 0 and this function will do nothing.
     * @param navPosition position of navigation bar (left, right or bottom)
     * @param w the window that is being offset by experiment
     */
    public void offsetWindowFramesForNavBar(int navPosition, WindowState w) {
        if (w.getAttrs().type != TYPE_INPUT_METHOD && w.getActivityType() != ACTIVITY_TYPE_HOME) {
            return;
        }

        final WindowFrames windowFrames = w.getWindowFrames();
        final Rect cf = windowFrames.mContentFrame;
        switch (navPosition) {
            case NAV_BAR_BOTTOM:
                int navHeight = getNavigationBarFrameHeight() - getNavigationBarHeight();
                if (navHeight > 0) {
                    cf.bottom -= navHeight;
                }
                break;
            case NAV_BAR_LEFT:
            case NAV_BAR_RIGHT:
                int navWidth = getNavigationBarFrameWidth() - getNavigationBarWidth();
                if (navWidth > 0) {
                    if (navPosition == NAV_BAR_LEFT) {
                        cf.left += navWidth;
                    } else {
                        cf.right -= navWidth;
                    }
                }
                break;
        }
    }
}
