/*
 * Copyright (C) 2025 The Android Open Source Project
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

package com.android.server.wm;

import static android.util.MathUtils.abs;

import android.annotation.ElapsedRealtimeLong;
import android.annotation.NonNull;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.FrameworkStatsLog;

import java.util.function.LongSupplier;

/**
 * Logs potential race conditions that lead to incorrect auto-rotate setting.
 *
 * Before go/auto-rotate-refactor, there is a race condition that happen during device state
 * changes, as a result, incorrect auto-rotate setting are written for a device state in
 * DEVICE_STATE_ROTATION_LOCK. Realistically, users shouldn’t be able to change
 * DEVICE_STATE_ROTATION_LOCK while the device folds/unfolds.
 *
 * This class monitors the time between a device state change and a subsequent change to the device
 * state based auto-rotate setting.  If the duration is less than a threshold
 * (DEVICE_STATE_AUTO_ROTATE_SETTING_ISSUE_THRESHOLD), a potential issue is logged. The logging of
 * the atom is not expected to occur often, realistically estimated once a month on few devices.
 * But the number could be bigger, as that's what this metric is set to reveal.
 *
 * @see #DEVICE_STATE_AUTO_ROTATE_SETTING_ISSUE_THRESHOLD_MILLIS
 */
public class DeviceStateAutoRotateSettingIssueLogger {
    @VisibleForTesting
    static final long DEVICE_STATE_AUTO_ROTATE_SETTING_ISSUE_THRESHOLD_MILLIS = 1500;
    private static final long TIME_NOT_SET = -1;

    private final LongSupplier mElapsedTimeMillisSupplier;

    @ElapsedRealtimeLong
    private long mLastDeviceStateChangeTime = TIME_NOT_SET;
    @ElapsedRealtimeLong
    private long mLastDeviceStateAutoRotateSettingChangeTime = TIME_NOT_SET;

    public DeviceStateAutoRotateSettingIssueLogger(
            @NonNull LongSupplier elapsedTimeMillisSupplier) {
        mElapsedTimeMillisSupplier = elapsedTimeMillisSupplier;
    }

    /** Notify logger that device state has changed. */
    public void onDeviceStateChange() {
        mLastDeviceStateChangeTime = mElapsedTimeMillisSupplier.getAsLong();
        onStateChange();
    }

    /** Notify logger that device state based auto rotate setting has changed. */
    public void onDeviceStateAutoRotateSettingChange() {
        mLastDeviceStateAutoRotateSettingChangeTime = mElapsedTimeMillisSupplier.getAsLong();
        onStateChange();
    }

    private void onStateChange() {
        // Only move forward if both of the events have occurred already
        if (mLastDeviceStateChangeTime != TIME_NOT_SET
                && mLastDeviceStateAutoRotateSettingChangeTime != TIME_NOT_SET) {
            final long duration =
                    mLastDeviceStateAutoRotateSettingChangeTime - mLastDeviceStateChangeTime;
            boolean isDeviceStateChangeFirst = duration > 0;

            if (abs(duration)
                    < DEVICE_STATE_AUTO_ROTATE_SETTING_ISSUE_THRESHOLD_MILLIS) {
                FrameworkStatsLog.write(
                        FrameworkStatsLog.DEVICE_STATE_AUTO_ROTATE_SETTING_ISSUE_REPORTED,
                        (int) abs(duration),
                        isDeviceStateChangeFirst);
            }

            mLastDeviceStateAutoRotateSettingChangeTime = TIME_NOT_SET;
            mLastDeviceStateChangeTime = TIME_NOT_SET;
        }
    }
}
