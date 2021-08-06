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
package com.android.systemui.battery;

import com.android.systemui.statusbar.policy.ConfigurationController;
import com.android.systemui.util.ViewController;

import javax.inject.Inject;

/** Controller for {@link BatteryMeterView}. **/
public class BatteryMeterViewController extends ViewController<BatteryMeterView> {
    private final ConfigurationController mConfigurationController;

    private final ConfigurationController.ConfigurationListener mConfigurationListener =
            new ConfigurationController.ConfigurationListener() {
                @Override
                public void onDensityOrFontScaleChanged() {
                    mView.scaleBatteryMeterViews();
                }
            };

    @Inject
    public BatteryMeterViewController(
            BatteryMeterView view,
            ConfigurationController configurationController) {
        super(view);
        mConfigurationController = configurationController;
    }

    @Override
    protected void onViewAttached() {
        mConfigurationController.addCallback(mConfigurationListener);
    }

    @Override
    protected void onViewDetached() {
        destroy();
    }

    @Override
    public void destroy() {
        super.destroy();
        mConfigurationController.removeCallback(mConfigurationListener);
    }
}