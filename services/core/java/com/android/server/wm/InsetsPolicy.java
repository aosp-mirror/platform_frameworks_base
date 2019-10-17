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

package com.android.server.wm;

import static android.app.WindowConfiguration.WINDOWING_MODE_FREEFORM;
import static android.app.WindowConfiguration.WINDOWING_MODE_SPLIT_SCREEN_PRIMARY;
import static android.view.WindowManager.LayoutParams.PRIVATE_FLAG_FORCE_STATUS_BAR_VISIBLE_TRANSPARENT;
import static android.view.WindowManager.LayoutParams.PRIVATE_FLAG_KEYGUARD;
import static android.view.WindowManager.LayoutParams.PRIVATE_FLAG_STATUS_FORCE_SHOW_NAVIGATION;

import android.annotation.Nullable;

/**
 * Policy that implements who gets control over the windows generating insets.
 */
class InsetsPolicy {

    private final InsetsStateController mStateController;
    private final DisplayContent mDisplayContent;
    private final DisplayPolicy mPolicy;

    InsetsPolicy(InsetsStateController stateController, DisplayContent displayContent) {
        mStateController = stateController;
        mDisplayContent = displayContent;
        mPolicy = displayContent.getDisplayPolicy();
    }

    /** Updates the target which can control system bars. */
    void updateBarControlTarget(@Nullable WindowState focusedWin) {
        mStateController.onBarControlTargetChanged(getTopControlTarget(focusedWin),
                getNavControlTarget(focusedWin));
    }

    private @Nullable InsetsControlTarget getTopControlTarget(@Nullable WindowState focusedWin) {
        if (areSystemBarsForciblyVisible() || isStatusBarForciblyVisible()) {
            return null;
        }
        return focusedWin;
    }

    private @Nullable InsetsControlTarget getNavControlTarget(@Nullable WindowState focusedWin) {
        if (areSystemBarsForciblyVisible() || isNavBarForciblyVisible()) {
            return null;
        }
        return focusedWin;
    }

    private boolean isStatusBarForciblyVisible() {
        final WindowState statusBar = mPolicy.getStatusBar();
        if (statusBar == null) {
            return false;
        }
        final int privateFlags = statusBar.mAttrs.privateFlags;

        // TODO: Pretend to the app that it's still able to control it?
        if ((privateFlags & PRIVATE_FLAG_FORCE_STATUS_BAR_VISIBLE_TRANSPARENT) != 0) {
            return true;
        }
        if ((privateFlags & PRIVATE_FLAG_KEYGUARD) != 0) {
            return true;
        }
        return false;
    }

    private boolean isNavBarForciblyVisible() {
        final WindowState statusBar = mPolicy.getStatusBar();
        if (statusBar == null) {
            return false;
        }
        if ((statusBar.mAttrs.privateFlags & PRIVATE_FLAG_STATUS_FORCE_SHOW_NAVIGATION) != 0) {
            return true;
        }
        return false;
    }

    private boolean areSystemBarsForciblyVisible() {
        final boolean isDockedStackVisible =
                mDisplayContent.isStackVisible(WINDOWING_MODE_SPLIT_SCREEN_PRIMARY);
        final boolean isFreeformStackVisible =
                mDisplayContent.isStackVisible(WINDOWING_MODE_FREEFORM);
        final boolean isResizing = mDisplayContent.getDockedDividerController().isResizing();

        // We need to force system bars when the docked stack is visible, when the freeform stack
        // is visible but also when we are resizing for the transitions when docked stack
        // visibility changes.
        return isDockedStackVisible || isFreeformStackVisible || isResizing;
    }

}
