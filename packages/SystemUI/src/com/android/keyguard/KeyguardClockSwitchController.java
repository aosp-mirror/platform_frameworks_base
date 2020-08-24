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

import android.app.WallpaperManager;
import android.view.View;
import android.view.ViewGroup;

import com.android.internal.colorextraction.ColorExtractor;
import com.android.keyguard.clock.ClockManager;
import com.android.systemui.colorextraction.SysuiColorExtractor;
import com.android.systemui.plugins.ClockPlugin;
import com.android.systemui.plugins.statusbar.StatusBarStateController;

import javax.inject.Inject;

/**
 * Injectable controller for {@link KeyguardClockSwitch}.
 */
public class KeyguardClockSwitchController {
    private static final boolean CUSTOM_CLOCKS_ENABLED = true;

    private final KeyguardClockSwitch mView;
    private final StatusBarStateController mStatusBarStateController;
    private final SysuiColorExtractor mColorExtractor;
    private final ClockManager mClockManager;
    private final KeyguardSliceViewController mKeyguardSliceViewController;

    private final StatusBarStateController.StateListener mStateListener =
            new StatusBarStateController.StateListener() {
                @Override
                public void onStateChanged(int newState) {
                    mView.updateBigClockVisibility(newState);
                }
            };

    /**
     * Listener for changes to the color palette.
     *
     * The color palette changes when the wallpaper is changed.
     */
    private final ColorExtractor.OnColorsChangedListener mColorsListener =
            new ColorExtractor.OnColorsChangedListener() {
        @Override
        public void onColorsChanged(ColorExtractor extractor, int which) {
            if ((which & WallpaperManager.FLAG_LOCK) != 0) {
                mView.updateColors(getGradientColors());
            }
        }
    };

    private ClockManager.ClockChangedListener mClockChangedListener = this::setClockPlugin;

    private final View.OnAttachStateChangeListener mOnAttachStateChangeListener =
            new View.OnAttachStateChangeListener() {
        @Override
        public void onViewAttachedToWindow(View v) {
            if (CUSTOM_CLOCKS_ENABLED) {
                mClockManager.addOnClockChangedListener(mClockChangedListener);
            }
            mStatusBarStateController.addCallback(mStateListener);
            mColorExtractor.addOnColorsChangedListener(mColorsListener);
            mView.updateColors(getGradientColors());
        }

        @Override
        public void onViewDetachedFromWindow(View v) {
            if (CUSTOM_CLOCKS_ENABLED) {
                mClockManager.removeOnClockChangedListener(mClockChangedListener);
            }
            mStatusBarStateController.removeCallback(mStateListener);
            mColorExtractor.removeOnColorsChangedListener(mColorsListener);
            mView.setClockPlugin(null, mStatusBarStateController.getState());
        }
    };

    @Inject
    public KeyguardClockSwitchController(KeyguardClockSwitch keyguardClockSwitch,
            StatusBarStateController statusBarStateController,
            SysuiColorExtractor colorExtractor, ClockManager clockManager,
            KeyguardSliceViewController keyguardSliceViewController) {
        mView = keyguardClockSwitch;
        mStatusBarStateController = statusBarStateController;
        mColorExtractor = colorExtractor;
        mClockManager = clockManager;
        mKeyguardSliceViewController = keyguardSliceViewController;
    }

    /**
     * Attach the controller to the view it relates to.
     */
    public void init() {
        if (mView.isAttachedToWindow()) {
            mOnAttachStateChangeListener.onViewAttachedToWindow(mView);
        }
        mView.addOnAttachStateChangeListener(mOnAttachStateChangeListener);

        mKeyguardSliceViewController.init();
    }

    /**
     * Set container for big clock face appearing behind NSSL and KeyguardStatusView.
     */
    public void setBigClockContainer(ViewGroup bigClockContainer) {
        mView.setBigClockContainer(bigClockContainer, mStatusBarStateController.getState());
    }

    private void setClockPlugin(ClockPlugin plugin) {
        mView.setClockPlugin(plugin, mStatusBarStateController.getState());
    }

    private ColorExtractor.GradientColors getGradientColors() {
        return mColorExtractor.getColors(WallpaperManager.FLAG_LOCK);
    }
}
