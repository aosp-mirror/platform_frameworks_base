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

package com.android.server.power.feature;

import android.os.Build;
import android.os.SystemProperties;
import android.text.TextUtils;
import android.util.Slog;

import com.android.server.power.feature.flags.Flags;

import java.io.PrintWriter;
import java.util.function.Supplier;

/**
 * Utility class to read the flags used in the power manager server.
 */
public class PowerManagerFlags {
    private static final boolean DEBUG = false;
    private static final String TAG = "PowerManagerFlags";

    private final FlagState mEarlyScreenTimeoutDetectorFlagState = new FlagState(
            Flags.FLAG_ENABLE_EARLY_SCREEN_TIMEOUT_DETECTOR,
            Flags::enableEarlyScreenTimeoutDetector);

    private final FlagState mImproveWakelockLatency = new FlagState(
            Flags.FLAG_IMPROVE_WAKELOCK_LATENCY,
            Flags::improveWakelockLatency
    );

    private final FlagState mPerDisplayWakeByTouch = new FlagState(
            Flags.FLAG_PER_DISPLAY_WAKE_BY_TOUCH,
            Flags::perDisplayWakeByTouch
    );

    private final FlagState mFrameworkWakelockInfo =
            new FlagState(Flags.FLAG_FRAMEWORK_WAKELOCK_INFO, Flags::frameworkWakelockInfo);

    private final FlagState mPolicyReasonInDisplayPowerRequest = new FlagState(
            Flags.FLAG_POLICY_REASON_IN_DISPLAY_POWER_REQUEST,
            Flags::policyReasonInDisplayPowerRequest
    );

    private final FlagState mMoveWscLoggingToNotifier =
            new FlagState(Flags.FLAG_MOVE_WSC_LOGGING_TO_NOTIFIER, Flags::moveWscLoggingToNotifier);

    /** Returns whether early-screen-timeout-detector is enabled on not. */
    public boolean isEarlyScreenTimeoutDetectorEnabled() {
        return mEarlyScreenTimeoutDetectorFlagState.isEnabled();
    }

    /**
     * @return Whether to improve the wakelock acquire/release latency or not
     */
    public boolean improveWakelockLatency() {
        return mImproveWakelockLatency.isEnabled();
    }

    /**
     * @return Whether per-display wake by touch is enabled or not.
     */
    public boolean isPerDisplayWakeByTouchEnabled() {
        return mPerDisplayWakeByTouch.isEnabled();
    }

    /**
     * @return Whether FrameworkWakelockInfo atom logging is enabled or not.
     */
    public boolean isFrameworkWakelockInfoEnabled() {
        return mFrameworkWakelockInfo.isEnabled();
    }

    /**
     * @return Whether the wakefulness reason is populated in DisplayPowerRequest.
     */
    public boolean isPolicyReasonInDisplayPowerRequestEnabled() {
        return mPolicyReasonInDisplayPowerRequest.isEnabled();
    }

    /**
     * @return Whether we move WakelockStateChanged atom logging to Notifier (enabled) or leave it
     *     in BatteryStatsImpl (disabled).
     */
    public boolean isMoveWscLoggingToNotifierEnabled() {
        return mMoveWscLoggingToNotifier.isEnabled();
    }

    /**
     * dumps all flagstates
     * @param pw printWriter
     */
    public void dump(PrintWriter pw) {
        pw.println("PowerManagerFlags:");
        pw.println(" " + mEarlyScreenTimeoutDetectorFlagState);
        pw.println(" " + mImproveWakelockLatency);
        pw.println(" " + mPerDisplayWakeByTouch);
        pw.println(" " + mFrameworkWakelockInfo);
        pw.println(" " + mMoveWscLoggingToNotifier);
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
            if (Build.IS_ENG || Build.IS_USERDEBUG) {
                return SystemProperties.getBoolean("persist.sys." + flagName + "-override",
                        flagValue);
            }
            return flagValue;
        }

        @Override
        public String toString() {
            // remove com.android.server.power.feature.flags. from the beginning of the name.
            // align all isEnabled() values.
            // Adjust lengths if we end up with longer names
            final int nameLength = mName.length();
            return TextUtils.substring(mName,  39, nameLength) + ": "
                    + TextUtils.formatSimple("%" + (91 - nameLength) + "s%s", " " , isEnabled())
                    + " (def:" + mFlagFunction.get() + ")";
        }
    }
}
