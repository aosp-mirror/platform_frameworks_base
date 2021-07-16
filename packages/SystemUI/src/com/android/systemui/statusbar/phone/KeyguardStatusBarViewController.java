/*
 * Copyright (C) 2021 The Android Open Source Project
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

import com.android.keyguard.CarrierTextController;
import com.android.systemui.statusbar.policy.ConfigurationController;
import com.android.systemui.util.ViewController;

import javax.inject.Inject;

/** View Controller for {@link com.android.systemui.statusbar.phone.KeyguardStatusBarView}. */
public class KeyguardStatusBarViewController extends ViewController<KeyguardStatusBarView> {
    private final CarrierTextController mCarrierTextController;
    private final ConfigurationController mConfigurationController;

    private final ConfigurationController.ConfigurationListener mConfigurationListener =
            new ConfigurationController.ConfigurationListener() {
                @Override
                public void onDensityOrFontScaleChanged() {
                    mView.loadDimens();
                }

                @Override
                public void onOverlayChanged() {
                    KeyguardStatusBarViewController.this.onThemeChanged();
                    mView.onOverlayChanged();
                }

                @Override
                public void onThemeChanged() {
                    KeyguardStatusBarViewController.this.onThemeChanged();
                }
            };

    @Inject
    public KeyguardStatusBarViewController(
            KeyguardStatusBarView view,
            CarrierTextController carrierTextController,
            ConfigurationController configurationController) {
        super(view);
        mCarrierTextController = carrierTextController;
        mConfigurationController = configurationController;
    }

    @Override
    protected void onInit() {
        super.onInit();
        mCarrierTextController.init();
    }

    @Override
    protected void onViewAttached() {
        mConfigurationController.addCallback(mConfigurationListener);
        onThemeChanged();
    }

    @Override
    protected void onViewDetached() {
        mConfigurationController.removeCallback(mConfigurationListener);
    }

    /** Should be called when the theme changes. */
    public void onThemeChanged() {
        mView.onThemeChanged();
    }
}
