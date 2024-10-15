/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.server.display.feature;

import android.os.Build;
import android.os.SystemProperties;
import android.text.TextUtils;
import android.util.Slog;

import com.android.server.display.feature.flags.Flags;
import com.android.server.display.utils.DebugUtils;

import java.io.PrintWriter;
import java.util.function.Supplier;

/**
 * Utility class to read the flags used in the display manager server.
 */
public class DisplayManagerFlags {
    private static final String TAG = "DisplayManagerFlags";

    // To enable these logs, run:
    // 'adb shell setprop persist.log.tag.DisplayManagerFlags DEBUG && adb reboot'
    private static final boolean DEBUG = DebugUtils.isDebuggable(TAG);

    private final FlagState mPortInDisplayLayoutFlagState = new FlagState(
            Flags.FLAG_ENABLE_PORT_IN_DISPLAY_LAYOUT,
            Flags::enablePortInDisplayLayout);

    private final FlagState mConnectedDisplayManagementFlagState = new FlagState(
            Flags.FLAG_ENABLE_CONNECTED_DISPLAY_MANAGEMENT,
            Flags::enableConnectedDisplayManagement);

    private final FlagState mNbmControllerFlagState = new FlagState(
            Flags.FLAG_ENABLE_NBM_CONTROLLER,
            Flags::enableNbmController);

    private final FlagState mHdrClamperFlagState = new FlagState(
            Flags.FLAG_ENABLE_HDR_CLAMPER,
            Flags::enableHdrClamper);

    private final FlagState mAdaptiveToneImprovements1 = new FlagState(
            Flags.FLAG_ENABLE_ADAPTIVE_TONE_IMPROVEMENTS_1,
            Flags::enableAdaptiveToneImprovements1);

    private final FlagState mAdaptiveToneImprovements2 = new FlagState(
            Flags.FLAG_ENABLE_ADAPTIVE_TONE_IMPROVEMENTS_2,
            Flags::enableAdaptiveToneImprovements2);

    private final FlagState mDisplayOffloadFlagState = new FlagState(
            Flags.FLAG_ENABLE_DISPLAY_OFFLOAD,
            Flags::enableDisplayOffload);

    private final FlagState mExternalDisplayLimitModeState = new FlagState(
            Flags.FLAG_ENABLE_MODE_LIMIT_FOR_EXTERNAL_DISPLAY,
            Flags::enableModeLimitForExternalDisplay);

    private final FlagState mDisplayTopology = new FlagState(
            Flags.FLAG_DISPLAY_TOPOLOGY,
            Flags::displayTopology);

    private final FlagState mConnectedDisplayErrorHandlingFlagState = new FlagState(
            Flags.FLAG_ENABLE_CONNECTED_DISPLAY_ERROR_HANDLING,
            Flags::enableConnectedDisplayErrorHandling);

    private final FlagState mBackUpSmoothDisplayAndForcePeakRefreshRateFlagState = new FlagState(
            Flags.FLAG_BACK_UP_SMOOTH_DISPLAY_AND_FORCE_PEAK_REFRESH_RATE,
            Flags::backUpSmoothDisplayAndForcePeakRefreshRate);

    private final FlagState mPowerThrottlingClamperFlagState = new FlagState(
            Flags.FLAG_ENABLE_POWER_THROTTLING_CLAMPER,
            Flags::enablePowerThrottlingClamper);

    private final FlagState mEvenDimmerFlagState = new FlagState(
            Flags.FLAG_EVEN_DIMMER,
            Flags::evenDimmer);
    private final FlagState mSmallAreaDetectionFlagState = new FlagState(
            com.android.graphics.surfaceflinger.flags.Flags.FLAG_ENABLE_SMALL_AREA_DETECTION,
            com.android.graphics.surfaceflinger.flags.Flags::enableSmallAreaDetection);

    private final FlagState mBrightnessIntRangeUserPerceptionFlagState = new FlagState(
            Flags.FLAG_BRIGHTNESS_INT_RANGE_USER_PERCEPTION,
            Flags::brightnessIntRangeUserPerception);

    private final FlagState mRestrictDisplayModes = new FlagState(
            Flags.FLAG_ENABLE_RESTRICT_DISPLAY_MODES,
            Flags::enableRestrictDisplayModes);

    private final FlagState mResolutionBackupRestore = new FlagState(
            Flags.FLAG_RESOLUTION_BACKUP_RESTORE,
            Flags::resolutionBackupRestore);

    private final FlagState mVsyncLowPowerVote = new FlagState(
            Flags.FLAG_ENABLE_VSYNC_LOW_POWER_VOTE,
            Flags::enableVsyncLowPowerVote);

    private final FlagState mVsyncLowLightVote = new FlagState(
            Flags.FLAG_ENABLE_VSYNC_LOW_LIGHT_VOTE,
            Flags::enableVsyncLowLightVote);

    private final FlagState mBrightnessWearBedtimeModeClamperFlagState = new FlagState(
            Flags.FLAG_BRIGHTNESS_WEAR_BEDTIME_MODE_CLAMPER,
            Flags::brightnessWearBedtimeModeClamper);

    private final FlagState mAutoBrightnessModesFlagState = new FlagState(
            Flags.FLAG_AUTO_BRIGHTNESS_MODES,
            Flags::autoBrightnessModes);

    private final FlagState mFastHdrTransitions = new FlagState(
            Flags.FLAG_FAST_HDR_TRANSITIONS,
            Flags::fastHdrTransitions);

    private final FlagState mAlwaysRotateDisplayDevice = new FlagState(
            Flags.FLAG_ALWAYS_ROTATE_DISPLAY_DEVICE,
            Flags::alwaysRotateDisplayDevice);

    private final FlagState mRefreshRateVotingTelemetry = new FlagState(
            Flags.FLAG_REFRESH_RATE_VOTING_TELEMETRY,
            Flags::refreshRateVotingTelemetry
    );

    private final FlagState mPixelAnisotropyCorrectionEnabled = new FlagState(
            Flags.FLAG_ENABLE_PIXEL_ANISOTROPY_CORRECTION,
            Flags::enablePixelAnisotropyCorrection
    );

    private final FlagState mSensorBasedBrightnessThrottling = new FlagState(
            Flags.FLAG_SENSOR_BASED_BRIGHTNESS_THROTTLING,
            Flags::sensorBasedBrightnessThrottling
    );

    private final FlagState mIdleScreenRefreshRateTimeout = new FlagState(
            Flags.FLAG_IDLE_SCREEN_REFRESH_RATE_TIMEOUT,
            Flags::idleScreenRefreshRateTimeout
    );

    private final FlagState mRefactorDisplayPowerController = new FlagState(
            Flags.FLAG_REFACTOR_DISPLAY_POWER_CONTROLLER,
            Flags::refactorDisplayPowerController
    );

    private final FlagState mUseFusionProxSensor = new FlagState(
            Flags.FLAG_USE_FUSION_PROX_SENSOR,
            Flags::useFusionProxSensor
    );

    private final FlagState mDozeBrightnessFloat = new FlagState(
            Flags.FLAG_DOZE_BRIGHTNESS_FLOAT,
            Flags::dozeBrightnessFloat);

    private final FlagState mOffloadControlsDozeAutoBrightness = new FlagState(
            Flags.FLAG_OFFLOAD_CONTROLS_DOZE_AUTO_BRIGHTNESS,
            Flags::offloadControlsDozeAutoBrightness
    );

    private final FlagState mPeakRefreshRatePhysicalLimit = new FlagState(
            Flags.FLAG_ENABLE_PEAK_REFRESH_RATE_PHYSICAL_LIMIT,
            Flags::enablePeakRefreshRatePhysicalLimit
    );

    private final FlagState mIgnoreAppPreferredRefreshRate = new FlagState(
            Flags.FLAG_IGNORE_APP_PREFERRED_REFRESH_RATE_REQUEST,
            Flags::ignoreAppPreferredRefreshRateRequest
    );

    private final FlagState mSynthetic60hzModes = new FlagState(
            Flags.FLAG_ENABLE_SYNTHETIC_60HZ_MODES,
            Flags::enableSynthetic60hzModes
    );

    private final FlagState mOffloadDozeOverrideHoldsWakelock = new FlagState(
            Flags.FLAG_OFFLOAD_DOZE_OVERRIDE_HOLDS_WAKELOCK,
            Flags::offloadDozeOverrideHoldsWakelock
    );

    private final FlagState mOffloadSessionCancelBlockScreenOn = new FlagState(
            Flags.FLAG_OFFLOAD_SESSION_CANCEL_BLOCK_SCREEN_ON,
            Flags::offloadSessionCancelBlockScreenOn);

    private final FlagState mNewHdrBrightnessModifier = new FlagState(
            Flags.FLAG_NEW_HDR_BRIGHTNESS_MODIFIER,
            Flags::newHdrBrightnessModifier);

    private final FlagState mIdleScreenConfigInSubscribingLightSensor = new FlagState(
            Flags.FLAG_IDLE_SCREEN_CONFIG_IN_SUBSCRIBING_LIGHT_SENSOR,
            Flags::idleScreenConfigInSubscribingLightSensor);

    private final FlagState mVirtualDisplayLimit =
            new FlagState(
                    Flags.FLAG_VIRTUAL_DISPLAY_LIMIT,
                    Flags::virtualDisplayLimit);

    private final FlagState mNormalBrightnessForDozeParameter = new FlagState(
            Flags.FLAG_NORMAL_BRIGHTNESS_FOR_DOZE_PARAMETER,
            Flags::normalBrightnessForDozeParameter
    );
    private final FlagState mBlockAutobrightnessChangesOnStylusUsage = new FlagState(
            Flags.FLAG_BLOCK_AUTOBRIGHTNESS_CHANGES_ON_STYLUS_USAGE,
            Flags::blockAutobrightnessChangesOnStylusUsage
    );
    private final FlagState mIsUserRefreshRateForExternalDisplayEnabled = new FlagState(
            Flags.FLAG_ENABLE_USER_REFRESH_RATE_FOR_EXTERNAL_DISPLAY,
            Flags::enableUserRefreshRateForExternalDisplay
    );

    private final FlagState mEnableWaitingConfirmationBeforeMirroring = new FlagState(
            Flags.FLAG_ENABLE_WAITING_CONFIRMATION_BEFORE_MIRRORING,
            Flags::enableWaitingConfirmationBeforeMirroring
    );

    private final FlagState mEnableApplyDisplayChangedDuringDisplayAdded = new FlagState(
            Flags.FLAG_ENABLE_APPLY_DISPLAY_CHANGED_DURING_DISPLAY_ADDED,
            Flags::enableApplyDisplayChangedDuringDisplayAdded
    );

    private final FlagState mEnableBatteryStatsForAllDisplays = new FlagState(
            Flags.FLAG_ENABLE_BATTERY_STATS_FOR_ALL_DISPLAYS,
            Flags::enableBatteryStatsForAllDisplays
    );

    private final FlagState mHasArrSupport = new FlagState(
            Flags.FLAG_ENABLE_HAS_ARR_SUPPORT,
            Flags::enableHasArrSupport
    );

    /**
     * @return {@code true} if 'port' is allowed in display layout configuration file.
     */
    public boolean isPortInDisplayLayoutEnabled() {
        return mPortInDisplayLayoutFlagState.isEnabled();
    }

    /** Returns whether connected display management is enabled or not. */
    public boolean isConnectedDisplayManagementEnabled() {
        return mConnectedDisplayManagementFlagState.isEnabled();
    }

    /** Returns whether NBM Controller is enabled or not. */
    public boolean isNbmControllerEnabled() {
        return mNbmControllerFlagState.isEnabled();
    }

    /** Returns whether hdr clamper is enabled on not. */
    public boolean isHdrClamperEnabled() {
        return mHdrClamperFlagState.isEnabled();
    }

    /** Returns whether power throttling clamper is enabled on not. */
    public boolean isPowerThrottlingClamperEnabled() {
        return mPowerThrottlingClamperFlagState.isEnabled();
    }

    /**
     * Returns whether adaptive tone improvements are enabled
     */
    public boolean isAdaptiveTone1Enabled() {
        return mAdaptiveToneImprovements1.isEnabled();
    }

    /**
     * Returns whether adaptive tone improvements are enabled
     */
    public boolean isAdaptiveTone2Enabled() {
        return mAdaptiveToneImprovements2.isEnabled();
    }

    /** Returns whether resolution range voting feature is enabled or not. */
    public boolean isDisplayResolutionRangeVotingEnabled() {
        return isExternalDisplayLimitModeEnabled();
    }

    /**
     * @return Whether user preferred mode is added as a vote in
     *      {@link com.android.server.display.mode.DisplayModeDirector}
     */
    public boolean isUserPreferredModeVoteEnabled() {
        return isExternalDisplayLimitModeEnabled();
    }

    /**
     * @return Whether external display mode limitation is enabled.
     */
    public boolean isExternalDisplayLimitModeEnabled() {
        return mExternalDisplayLimitModeState.isEnabled();
    }

    public boolean isDisplayTopologyEnabled() {
        return mDisplayTopology.isEnabled();
    }

    /**
     * @return Whether displays refresh rate synchronization is enabled.
     */
    public boolean isDisplaysRefreshRatesSynchronizationEnabled() {
        return isExternalDisplayLimitModeEnabled();
    }

    /** Returns whether displayoffload is enabled on not */
    public boolean isDisplayOffloadEnabled() {
        return mDisplayOffloadFlagState.isEnabled();
    }

    /** Returns whether error notifications for connected displays are enabled on not */
    public boolean isConnectedDisplayErrorHandlingEnabled() {
        return mConnectedDisplayErrorHandlingFlagState.isEnabled();
    }

    public boolean isBackUpSmoothDisplayAndForcePeakRefreshRateEnabled() {
        return mBackUpSmoothDisplayAndForcePeakRefreshRateFlagState.isEnabled();
    }

    /** Returns whether brightness range is allowed to extend below traditional range. */
    public boolean isEvenDimmerEnabled() {
        return mEvenDimmerFlagState.isEnabled();
    }

    public boolean isSmallAreaDetectionEnabled() {
        return mSmallAreaDetectionFlagState.isEnabled();
    }

    public boolean isBrightnessIntRangeUserPerceptionEnabled() {
        return mBrightnessIntRangeUserPerceptionFlagState.isEnabled();
    }

    public boolean isRestrictDisplayModesEnabled() {
        return mRestrictDisplayModes.isEnabled();
    }

    public boolean isResolutionBackupRestoreEnabled() {
        return mResolutionBackupRestore.isEnabled();
    }

    public boolean isVsyncLowPowerVoteEnabled() {
        return mVsyncLowPowerVote.isEnabled();
    }

    public boolean isVsyncLowLightVoteEnabled() {
        return mVsyncLowLightVote.isEnabled();
    }

    public boolean isBrightnessWearBedtimeModeClamperEnabled() {
        return mBrightnessWearBedtimeModeClamperFlagState.isEnabled();
    }

    /**
     * @return Whether generic auto-brightness modes are enabled
     */
    public boolean areAutoBrightnessModesEnabled() {
        return mAutoBrightnessModesFlagState.isEnabled();
    }

    public boolean isFastHdrTransitionsEnabled() {
        return mFastHdrTransitions.isEnabled();
    }

    public boolean isAlwaysRotateDisplayDeviceEnabled() {
        return mAlwaysRotateDisplayDevice.isEnabled();
    }

    public boolean isRefreshRateVotingTelemetryEnabled() {
        return mRefreshRateVotingTelemetry.isEnabled();
    }

    public boolean isPixelAnisotropyCorrectionInLogicalDisplayEnabled() {
        return mPixelAnisotropyCorrectionEnabled.isEnabled();
    }

    public boolean isSensorBasedBrightnessThrottlingEnabled() {
        return mSensorBasedBrightnessThrottling.isEnabled();
    }

    public boolean isIdleScreenRefreshRateTimeoutEnabled() {
        return mIdleScreenRefreshRateTimeout.isEnabled();
    }

    public boolean isRefactorDisplayPowerControllerEnabled() {
        return mRefactorDisplayPowerController.isEnabled();
    }

    public boolean isUseFusionProxSensorEnabled() {
        return mUseFusionProxSensor.isEnabled();
    }

    public String getUseFusionProxSensorFlagName() {
        return mUseFusionProxSensor.getName();
    }

    public boolean isDozeBrightnessFloatEnabled() {
        return mDozeBrightnessFloat.isEnabled();
    }

    /**
     * @return Whether DisplayOffload should control auto-brightness in doze
     */
    public boolean offloadControlsDozeAutoBrightness() {
        return mOffloadControlsDozeAutoBrightness.isEnabled();
    }

    public boolean isPeakRefreshRatePhysicalLimitEnabled() {
        return mPeakRefreshRatePhysicalLimit.isEnabled();
    }

    public boolean isOffloadDozeOverrideHoldsWakelockEnabled() {
        return mOffloadDozeOverrideHoldsWakelock.isEnabled();
    }

    public boolean isOffloadSessionCancelBlockScreenOnEnabled() {
        return mOffloadSessionCancelBlockScreenOn.isEnabled();
    }

    /**
     * @return Whether to ignore preferredRefreshRate app request conversion to display mode or not
     */
    public boolean ignoreAppPreferredRefreshRateRequest() {
        return mIgnoreAppPreferredRefreshRate.isEnabled();
    }

    public boolean isSynthetic60HzModesEnabled() {
        return mSynthetic60hzModes.isEnabled();
    }

    /**
     * @return Whether to use new HDR brightness modifier or not
     */
    public boolean useNewHdrBrightnessModifier() {
        return mNewHdrBrightnessModifier.isEnabled();
    }

    public boolean isVirtualDisplayLimitEnabled() {
        return mVirtualDisplayLimit.isEnabled();
    }

    /**
     * @return Whether the useDozeBrightness parameter should be used
     */
    public boolean isNormalBrightnessForDozeParameterEnabled() {
        return mNormalBrightnessForDozeParameter.isEnabled();
    }

     /**
      * @return {@code true} if idle timer refresh rate config is accounted for while subscribing to
      * the light sensor
      */
    public boolean isIdleScreenConfigInSubscribingLightSensorEnabled() {
        return mIdleScreenConfigInSubscribingLightSensor.isEnabled();
    }

    /**
      * @return {@code true} if mirroring won't be enabled until boot completes and the user enables
      * the display.
      */
    public boolean isWaitingConfirmationBeforeMirroringEnabled() {
        return mEnableWaitingConfirmationBeforeMirroring.isEnabled();
    }

    /**
      * @return {@code true} if battery stats is enabled for all displays, not just the primary
      * display.
      */
    public boolean isBatteryStatsEnabledForAllDisplays() {
        return mEnableBatteryStatsForAllDisplays.isEnabled();
    }

    /**
      * @return {@code true} if need to apply display changes during display added event.
      */
    public boolean isApplyDisplayChangedDuringDisplayAddedEnabled() {
        return mEnableApplyDisplayChangedDuringDisplayAdded.isEnabled();
    }

    /**
     * @return {@code true} if autobrightness is to be blocked when stylus is being used
     */
    public boolean isBlockAutobrightnessChangesOnStylusUsage() {
        return mBlockAutobrightnessChangesOnStylusUsage.isEnabled();
    }

    /**
     * @return {@code true} if need to use user refresh rate settings for
     * external displays.
     */
    public boolean isUserRefreshRateForExternalDisplayEnabled() {
        return mIsUserRefreshRateForExternalDisplayEnabled.isEnabled();
    }

    /**
     * @return {@code true} if hasArrSupport API is enabled.
     */
    public boolean hasArrSupportFlag() {
        return mHasArrSupport.isEnabled();
    }
    /**
     * dumps all flagstates
     * @param pw printWriter
     */
    public void dump(PrintWriter pw) {
        pw.println("DisplayManagerFlags:");
        pw.println("--------------------");
        pw.println(" " + mAdaptiveToneImprovements1);
        pw.println(" " + mAdaptiveToneImprovements2);
        pw.println(" " + mBackUpSmoothDisplayAndForcePeakRefreshRateFlagState);
        pw.println(" " + mConnectedDisplayErrorHandlingFlagState);
        pw.println(" " + mConnectedDisplayManagementFlagState);
        pw.println(" " + mDisplayOffloadFlagState);
        pw.println(" " + mExternalDisplayLimitModeState);
        pw.println(" " + mDisplayTopology);
        pw.println(" " + mHdrClamperFlagState);
        pw.println(" " + mNbmControllerFlagState);
        pw.println(" " + mPowerThrottlingClamperFlagState);
        pw.println(" " + mEvenDimmerFlagState);
        pw.println(" " + mSmallAreaDetectionFlagState);
        pw.println(" " + mBrightnessIntRangeUserPerceptionFlagState);
        pw.println(" " + mRestrictDisplayModes);
        pw.println(" " + mBrightnessWearBedtimeModeClamperFlagState);
        pw.println(" " + mAutoBrightnessModesFlagState);
        pw.println(" " + mFastHdrTransitions);
        pw.println(" " + mAlwaysRotateDisplayDevice);
        pw.println(" " + mRefreshRateVotingTelemetry);
        pw.println(" " + mPixelAnisotropyCorrectionEnabled);
        pw.println(" " + mSensorBasedBrightnessThrottling);
        pw.println(" " + mIdleScreenRefreshRateTimeout);
        pw.println(" " + mRefactorDisplayPowerController);
        pw.println(" " + mResolutionBackupRestore);
        pw.println(" " + mUseFusionProxSensor);
        pw.println(" " + mDozeBrightnessFloat);
        pw.println(" " + mOffloadControlsDozeAutoBrightness);
        pw.println(" " + mPeakRefreshRatePhysicalLimit);
        pw.println(" " + mIgnoreAppPreferredRefreshRate);
        pw.println(" " + mSynthetic60hzModes);
        pw.println(" " + mOffloadDozeOverrideHoldsWakelock);
        pw.println(" " + mOffloadSessionCancelBlockScreenOn);
        pw.println(" " + mNewHdrBrightnessModifier);
        pw.println(" " + mVirtualDisplayLimit);
        pw.println(" " + mNormalBrightnessForDozeParameter);
        pw.println(" " + mIdleScreenConfigInSubscribingLightSensor);
        pw.println(" " + mEnableWaitingConfirmationBeforeMirroring);
        pw.println(" " + mEnableBatteryStatsForAllDisplays);
        pw.println(" " + mEnableApplyDisplayChangedDuringDisplayAdded);
        pw.println(" " + mBlockAutobrightnessChangesOnStylusUsage);
        pw.println(" " + mIsUserRefreshRateForExternalDisplayEnabled);
        pw.println(" " + mHasArrSupport);
    }

    private static class FlagState {

        private final String mName;

        private final Supplier<Boolean> mFlagFunction;
        private boolean mEnabledSet;
        private boolean mEnabled;

        private FlagState(String name, Supplier<Boolean> flagFunction) {
            mName = name;
            mFlagFunction = flagFunction;
        }

        private String getName() {
            return mName;
        }

        private boolean isEnabled() {
            if (mEnabledSet) {
                if (DEBUG) {
                    Slog.d(TAG, mName + ": mEnabled. Recall = " + mEnabled);
                }
                return mEnabled;
            }
            mEnabled = flagOrSystemProperty(mFlagFunction, mName);
            if (DEBUG) {
                Slog.d(TAG, mName + ": mEnabled. Flag value = " + mEnabled);
            }
            mEnabledSet = true;
            return mEnabled;
        }

        private boolean flagOrSystemProperty(Supplier<Boolean> flagFunction, String flagName) {
            boolean flagValue = flagFunction.get();
            // TODO(b/299462337) Remove when the infrastructure is ready.
            if (Build.IS_ENG || Build.IS_USERDEBUG) {
                return SystemProperties.getBoolean("persist.sys." + flagName + "-override",
                        flagValue);
            }
            return flagValue;
        }

        @Override
        public String toString() {
            // remove com.android.server.display.feature.flags. from the beginning of the name.
            // align all isEnabled() values.
            // Adjust lengths if we end up with longer names
            final int nameLength = mName.length();
            return TextUtils.substring(mName,  41, nameLength) + ": "
                    + TextUtils.formatSimple("%" + (93 - nameLength) + "s%s", " " , isEnabled())
                    + " (def:" + mFlagFunction.get() + ")";
        }
    }
}
