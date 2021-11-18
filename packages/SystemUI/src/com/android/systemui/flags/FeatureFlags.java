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

/**
 * Class to manage simple DeviceConfig-based feature flags.
 *
 * See {@link Flags} for instructions on defining new flags.
 */
public interface FeatureFlags extends FlagReader {
    default boolean isNewNotifPipelineRenderingEnabled() {
        return isEnabled(Flags.NEW_NOTIFICATION_PIPELINE_RENDERING);
    }

    /** */
    default boolean useNewLockscreenAnimations() {
        return isEnabled(Flags.LOCKSCREEN_ANIMATIONS);
    }

    default boolean isPeopleTileEnabled() {
        return isEnabled(Flags.PEOPLE_TILE);
    }

    default boolean isMonetEnabled() {
        return isEnabled(Flags.MONET);
    }

    default boolean isPMLiteEnabled() {
        return isEnabled(Flags.POWER_MENU_LITE);
    }

    default boolean isChargingRippleEnabled() {
        return isEnabled(Flags.CHARGING_RIPPLE);
    }

    default boolean isOngoingCallStatusBarChipEnabled() {
        return isEnabled(Flags.ONGOING_CALL_STATUS_BAR_CHIP);
    }

    default boolean isOngoingCallInImmersiveEnabled() {
        return isOngoingCallStatusBarChipEnabled() && isEnabled(Flags.ONGOING_CALL_IN_IMMERSIVE);
    }

    default boolean isOngoingCallInImmersiveChipTapEnabled() {
        return isOngoingCallInImmersiveEnabled()
                && isEnabled(Flags.ONGOING_CALL_IN_IMMERSIVE_CHIP_TAP);
    }

    default boolean isSmartspaceEnabled() {
        return isEnabled(Flags.SMARTSPACE);
    }

    default boolean isSmartspaceDedupingEnabled() {
        return isSmartspaceEnabled() && isEnabled(Flags.SMARTSPACE_DEDUPING);
    }

    default boolean isNewKeyguardSwipeAnimationEnabled() {
        return isEnabled(Flags.NEW_UNLOCK_SWIPE_ANIMATION);
    }

    default boolean isKeyguardQsUserDetailsShortcutEnabled() {
        return isEnabled(Flags.QS_USER_DETAIL_SHORTCUT);
    }

    default boolean isSmartSpaceSharedElementTransitionEnabled() {
        return isEnabled(Flags.SMARTSPACE_SHARED_ELEMENT_TRANSITION_ENABLED);
    }

    /** Whether or not to use the provider model behavior for the status bar icons */
    default boolean isCombinedStatusBarSignalIconsEnabled() {
        return isEnabled(Flags.COMBINED_STATUS_BAR_SIGNAL_ICONS);
    }

    /**
     * Use the new version of the user switcher
     */
    default boolean useNewUserSwitcher() {
        return isEnabled(Flags.NEW_USER_SWITCHER);
    }

    /**
     * Use the new single view QS headers
     */
    default boolean useCombinedQSHeaders() {
        return isEnabled(Flags.COMBINED_QS_HEADERS);
    }
}
