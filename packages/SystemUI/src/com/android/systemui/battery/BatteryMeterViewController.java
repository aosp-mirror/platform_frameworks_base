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

import android.util.ArraySet;
import android.view.View;

import com.android.systemui.statusbar.phone.StatusBarIconController;
import com.android.systemui.statusbar.policy.ConfigurationController;
import com.android.systemui.tuner.TunerService;
import com.android.systemui.util.ViewController;

import javax.inject.Inject;

/** Controller for {@link BatteryMeterView}. **/
public class BatteryMeterViewController extends ViewController<BatteryMeterView> {
    private final ConfigurationController mConfigurationController;
    private final TunerService mTunerService;

    private final String mSlotBattery;

    private final ConfigurationController.ConfigurationListener mConfigurationListener =
            new ConfigurationController.ConfigurationListener() {
                @Override
                public void onDensityOrFontScaleChanged() {
                    mView.scaleBatteryMeterViews();
                }
            };

    private final TunerService.Tunable mTunable = new TunerService.Tunable() {
        @Override
        public void onTuningChanged(String key, String newValue) {
            if (StatusBarIconController.ICON_HIDE_LIST.equals(key)) {
                ArraySet<String> icons = StatusBarIconController.getIconHideList(
                        getContext(), newValue);
                mView.setVisibility(icons.contains(mSlotBattery) ? View.GONE : View.VISIBLE);
            }
        }
    };

    // Some places may need to show the battery conditionally, and not obey the tuner
    private boolean mIgnoreTunerUpdates;
    private boolean mIsSubscribedForTunerUpdates;

    @Inject
    public BatteryMeterViewController(
            BatteryMeterView view,
            ConfigurationController configurationController,
            TunerService tunerService) {
        super(view);
        mConfigurationController = configurationController;
        mTunerService = tunerService;

        mSlotBattery = getResources().getString(com.android.internal.R.string.status_bar_battery);
    }

    @Override
    protected void onViewAttached() {
        mConfigurationController.addCallback(mConfigurationListener);
        subscribeForTunerUpdates();
    }

    @Override
    protected void onViewDetached() {
        destroy();
    }

    @Override
    public void destroy() {
        super.destroy();
        mConfigurationController.removeCallback(mConfigurationListener);
        unsubscribeFromTunerUpdates();
    }

    /**
     * Turn off {@link BatteryMeterView}'s subscribing to the tuner for updates, and thus avoid it
     * controlling its own visibility.
     */
    public void ignoreTunerUpdates() {
        mIgnoreTunerUpdates = true;
        unsubscribeFromTunerUpdates();
    }

    private void subscribeForTunerUpdates() {
        if (mIsSubscribedForTunerUpdates || mIgnoreTunerUpdates) {
            return;
        }

        mTunerService.addTunable(mTunable, StatusBarIconController.ICON_HIDE_LIST);
        mIsSubscribedForTunerUpdates = true;
    }

    private void unsubscribeFromTunerUpdates() {
        if (!mIsSubscribedForTunerUpdates) {
            return;
        }

        mTunerService.removeTunable(mTunable);
        mIsSubscribedForTunerUpdates = false;
    }
}