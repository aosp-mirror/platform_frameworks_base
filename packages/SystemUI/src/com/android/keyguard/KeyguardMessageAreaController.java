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

import android.content.res.ColorStateList;
import android.content.res.Configuration;

import com.android.systemui.statusbar.policy.ConfigurationController;
import com.android.systemui.statusbar.policy.ConfigurationController.ConfigurationListener;
import com.android.systemui.util.ViewController;

import javax.inject.Inject;

/** Controller for a {@link KeyguardMessageAreaController}. */
public class KeyguardMessageAreaController extends ViewController<KeyguardMessageArea> {
    private final KeyguardUpdateMonitor mKeyguardUpdateMonitor;
    private final ConfigurationController mConfigurationController;
    private boolean mAltBouncerShowing;

    private KeyguardUpdateMonitorCallback mInfoCallback = new KeyguardUpdateMonitorCallback() {
        public void onFinishedGoingToSleep(int why) {
            mView.setSelected(false);
        }

        public void onStartedWakingUp() {
            mView.setSelected(true);
        }
    };

    private ConfigurationListener mConfigurationListener = new ConfigurationListener() {
        @Override
        public void onConfigChanged(Configuration newConfig) {
            mView.onConfigChanged();
        }

        @Override
        public void onThemeChanged() {
            mView.onThemeChanged();
        }

        @Override
        public void onDensityOrFontScaleChanged() {
            mView.onDensityOrFontScaleChanged();
        }
    };

    private KeyguardMessageAreaController(KeyguardMessageArea view,
            KeyguardUpdateMonitor keyguardUpdateMonitor,
            ConfigurationController configurationController) {
        super(view);

        mKeyguardUpdateMonitor = keyguardUpdateMonitor;
        mConfigurationController = configurationController;
    }

    @Override
    protected void onViewAttached() {
        mConfigurationController.addCallback(mConfigurationListener);
        mKeyguardUpdateMonitor.registerCallback(mInfoCallback);
        mView.setSelected(mKeyguardUpdateMonitor.isDeviceInteractive());
        mView.onThemeChanged();
    }

    @Override
    protected void onViewDetached() {
        mConfigurationController.removeCallback(mConfigurationListener);
        mKeyguardUpdateMonitor.removeCallback(mInfoCallback);
    }

    /**
     * Set whether alt bouncer is showing
     */
    public void setAltBouncerShowing(boolean showing) {
        mView.setAltBouncerShowing(showing);
    }

    /**
     * Set bouncer is fully showing
     */
    public void setBouncerShowing(boolean showing) {
        mView.setBouncerShowing(showing);
    }

    public void setMessage(CharSequence s) {
        mView.setMessage(s);
    }

    public void setMessage(int resId) {
        mView.setMessage(resId);
    }

    public void setNextMessageColor(ColorStateList colorState) {
        mView.setNextMessageColor(colorState);
    }

    /**
     * Reload colors from resources.
     **/
    public void reloadColors() {
        mView.reloadColor();
    }

    /** Factory for creating {@link com.android.keyguard.KeyguardMessageAreaController}. */
    public static class Factory {
        private final KeyguardUpdateMonitor mKeyguardUpdateMonitor;
        private final ConfigurationController mConfigurationController;

        @Inject
        public Factory(KeyguardUpdateMonitor keyguardUpdateMonitor,
                ConfigurationController configurationController) {
            mKeyguardUpdateMonitor = keyguardUpdateMonitor;
            mConfigurationController = configurationController;
        }

        /** Build a new {@link KeyguardMessageAreaController}. */
        public KeyguardMessageAreaController create(KeyguardMessageArea view) {
            return new KeyguardMessageAreaController(
                    view, mKeyguardUpdateMonitor, mConfigurationController);
        }
    }
}
