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

package com.android.keyguard;

import android.graphics.Color;

import com.android.settingslib.Utils;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.util.ViewController;

/**
 * Controls the color of a GradientTextClock.
 */
public class AnimatableClockController extends ViewController<AnimatableClockView> {

    private final StatusBarStateController mStatusBarStateController;
    private final int[] mDozingColors = new int[] {Color.WHITE, Color.WHITE};
    private int[] mLockScreenColors = new int[2];

    private boolean mIsDozing;

    public AnimatableClockController(
            AnimatableClockView view,
            StatusBarStateController statusBarStateController) {
        super(view);
        mStatusBarStateController = statusBarStateController;
        mIsDozing = mStatusBarStateController.isDozing();
    }

    @Override
    protected void onViewAttached() {
        mStatusBarStateController.addCallback(mStatusBarStateListener);
        mIsDozing = mStatusBarStateController.isDozing();
        refreshTime();
        initColors();
    }

    @Override
    protected void onViewDetached() {
        mStatusBarStateController.removeCallback(mStatusBarStateListener);
    }

    /**
     * Updates the time for this view.
     */
    public void refreshTime() {
        mView.refreshTime();
    }

    private void initColors() {
        mLockScreenColors[0] = Utils.getColorAttrDefaultColor(getContext(),
                com.android.systemui.R.attr.wallpaperTextColor);
        mLockScreenColors[1] = Utils.getColorAttrDefaultColor(getContext(),
                        com.android.systemui.R.attr.wallpaperTextColorSecondary);
        mView.setColors(mDozingColors, mLockScreenColors);
        mView.animateDoze(mIsDozing, false);
    }

    private final StatusBarStateController.StateListener mStatusBarStateListener =
            new StatusBarStateController.StateListener() {
                @Override
                public void onDozingChanged(boolean isDozing) {
                    mIsDozing = isDozing;
                    mView.animateDoze(mIsDozing, true);
                }
            };
}
