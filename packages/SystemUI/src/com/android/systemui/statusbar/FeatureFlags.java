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

package com.android.systemui.statusbar;

import com.android.systemui.R;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.flags.FeatureFlagReader;

import javax.inject.Inject;

/**
 * Class to manage simple DeviceConfig-based feature flags.
 *
 * See {@link FeatureFlagReader} for instructions on defining and flipping flags.
 */
@SysUISingleton
public class FeatureFlags {
    private final FeatureFlagReader mFlagReader;

    @Inject
    public FeatureFlags(FeatureFlagReader flagReader) {
        mFlagReader = flagReader;
    }

    public boolean isNewNotifPipelineEnabled() {
        return mFlagReader.isEnabled(R.bool.flag_notification_pipeline2);
    }

    public boolean isNewNotifPipelineRenderingEnabled() {
        return mFlagReader.isEnabled(R.bool.flag_notification_pipeline2_rendering);
    }

    public boolean isShadeOpaque() {
        return mFlagReader.isEnabled(R.bool.flag_shade_is_opaque);
    }

    /** b/171917882 */
    public boolean isTwoColumnNotificationShadeEnabled() {
        return mFlagReader.isEnabled(R.bool.flag_notification_twocolumn);
    }

    // Does not support runtime changes
    public boolean isQSLabelsEnabled() {
        return mFlagReader.isEnabled(R.bool.flag_qs_labels);
    }

    public boolean isKeyguardLayoutEnabled() {
        return mFlagReader.isEnabled(R.bool.flag_keyguard_layout);
    }

    /** b/178485354 */
    public boolean useNewBrightnessSlider() {
        return mFlagReader.isEnabled(R.bool.flag_brightness_slider);
    }

    public boolean useNewLockscreenAnimations() {
        return mFlagReader.isEnabled(R.bool.flag_lockscreen_animations);
    }

    public boolean isPeopleTileEnabled() {
        return mFlagReader.isEnabled(R.bool.flag_conversations);
    }

    public boolean isToastStyleEnabled() {
        return mFlagReader.isEnabled(R.bool.flag_toast_style);
    }

    public boolean isMonetEnabled() {
        return mFlagReader.isEnabled(R.bool.flag_monet);
    }

    public boolean isQuickAccessWalletEnabled() {
        return mFlagReader.isEnabled(R.bool.flag_wallet);
    }

    public boolean isPMLiteEnabled() {
        return mFlagReader.isEnabled(R.bool.flag_pm_lite);
    }

    public boolean isAlarmTileAvailable() {
        return mFlagReader.isEnabled(R.bool.flag_alarm_tile);
    }

    public boolean isChargingRippleEnabled() {
        return mFlagReader.isEnabled(R.bool.flag_charging_ripple);
    }
}
