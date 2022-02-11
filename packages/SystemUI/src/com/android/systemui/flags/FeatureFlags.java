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

package com.android.systemui.flags;

import android.content.Context;
import android.util.FeatureFlagUtils;
import android.util.Log;
import android.widget.Toast;

import com.android.systemui.dagger.SysUISingleton;

import javax.inject.Inject;

/**
 * Class to manage simple DeviceConfig-based feature flags.
 *
 * See {@link Flags} for instructions on defining new flags.
 */
@SysUISingleton
public class FeatureFlags {
    private final FlagReader mFlagReader;
    private final Context mContext;

    @Inject
    public FeatureFlags(FlagReader flagReader, Context context) {
        mFlagReader = flagReader;
        mContext = context;
    }

    /**
     * @param flag The {@link BooleanFlag} of interest.
     * @return The value of the flag.
     */
    public boolean isEnabled(BooleanFlag flag) {
        return mFlagReader.isEnabled(flag);
    }

    public void assertLegacyPipelineEnabled() {
        if (isNewNotifPipelineRenderingEnabled()) {
            throw new IllegalStateException("Old pipeline code running w/ new pipeline enabled");
        }
    }

    public boolean checkLegacyPipelineEnabled() {
        if (!isNewNotifPipelineRenderingEnabled()) {
            return true;
        }
        Log.d("NotifPipeline", "Old pipeline code running w/ new pipeline enabled",
                new Exception());
        Toast.makeText(mContext, "Old pipeline code running!", Toast.LENGTH_SHORT).show();
        return false;
    }

    public boolean isNewNotifPipelineEnabled() {
        return isEnabled(Flags.NEW_NOTIFICATION_PIPELINE);
    }

    public boolean isNewNotifPipelineRenderingEnabled() {
        return isEnabled(Flags.NEW_NOTIFICATION_PIPELINE_RENDERING);
    }

    /** */
    public boolean useNewLockscreenAnimations() {
        return isEnabled(Flags.LOCKSCREEN_ANIMATIONS);
    }

    public boolean isPeopleTileEnabled() {
        return isEnabled(Flags.PEOPLE_TILE);
    }

    public boolean isMonetEnabled() {
        return isEnabled(Flags.MONET);
    }

    public boolean isPMLiteEnabled() {
        return isEnabled(Flags.POWER_MENU_LITE);
    }

    public boolean isChargingRippleEnabled() {
        return isEnabled(Flags.CHARGING_RIPPLE);
    }

    public boolean isOngoingCallStatusBarChipEnabled() {
        return isEnabled(Flags.ONGOING_CALL_STATUS_BAR_CHIP);
    }

    public boolean isOngoingCallInImmersiveEnabled() {
        return isOngoingCallStatusBarChipEnabled() && isEnabled(Flags.ONGOING_CALL_IN_IMMERSIVE);
    }

    public boolean isOngoingCallInImmersiveChipTapEnabled() {
        return isOngoingCallInImmersiveEnabled()
                && isEnabled(Flags.ONGOING_CALL_IN_IMMERSIVE_CHIP_TAP);
    }

    public boolean isSmartspaceEnabled() {
        return isEnabled(Flags.SMARTSPACE);
    }

    public boolean isSmartspaceDedupingEnabled() {
        return isSmartspaceEnabled() && isEnabled(Flags.SMARTSPACE_DEDUPING);
    }

    public boolean isNewKeyguardSwipeAnimationEnabled() {
        return isEnabled(Flags.NEW_UNLOCK_SWIPE_ANIMATION);
    }

    public boolean isSmartSpaceSharedElementTransitionEnabled() {
        return isEnabled(Flags.SMARTSPACE_SHARED_ELEMENT_TRANSITION_ENABLED);
    }

    /** Whether or not to use the provider model behavior for the status bar icons */
    public boolean isCombinedStatusBarSignalIconsEnabled() {
        return isEnabled(Flags.COMBINED_STATUS_BAR_SIGNAL_ICONS);
    }

    /** System setting for provider model behavior */
    public boolean isProviderModelSettingEnabled() {
        return FeatureFlagUtils.isEnabled(mContext, FeatureFlagUtils.SETTINGS_PROVIDER_MODEL);
    }

    /**
     * Use the new version of the user switcher
     */
    public boolean useNewUserSwitcher() {
        return isEnabled(Flags.NEW_USER_SWITCHER);
    }

    /**
     * Use the new single view QS headers
     */
    public boolean useCombinedQSHeaders() {
        return isEnabled(Flags.COMBINED_QS_HEADERS);
    }

    /** static method for the system setting */
    public static boolean isProviderModelSettingEnabled(Context context) {
        return FeatureFlagUtils.isEnabled(context, FeatureFlagUtils.SETTINGS_PROVIDER_MODEL);
    }
}
