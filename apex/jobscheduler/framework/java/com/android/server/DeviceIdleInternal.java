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

package com.android.server;

import android.annotation.Nullable;
import android.os.PowerExemptionManager;
import android.os.PowerExemptionManager.ReasonCode;
import android.os.PowerExemptionManager.TempAllowListType;

import com.android.server.deviceidle.IDeviceIdleConstraint;

public interface DeviceIdleInternal {
    void onConstraintStateChanged(IDeviceIdleConstraint constraint, boolean active);

    void registerDeviceIdleConstraint(IDeviceIdleConstraint constraint, String name,
            @IDeviceIdleConstraint.MinimumState int minState);

    void unregisterDeviceIdleConstraint(IDeviceIdleConstraint constraint);

    void exitIdle(String reason);

    /**
     * Same as {@link #addPowerSaveTempWhitelistApp(int, String, long, int, boolean, int, String)}
     * with {@link PowerExemptionManager#TEMPORARY_ALLOW_LIST_TYPE_FOREGROUND_SERVICE_ALLOWED}.
     */
    void addPowerSaveTempWhitelistApp(int callingUid, String packageName,
            long durationMs, int userId, boolean sync, @ReasonCode int reasonCode,
            @Nullable String reason);

    /**
     * Put a package in the temp-allowlist.
     */
    void addPowerSaveTempWhitelistApp(int callingUid, String packageName,
            long durationMs, @TempAllowListType int tempAllowListType, int userId, boolean sync,
            @ReasonCode int reasonCode, @Nullable String reason);

    /**
     * Called by ActivityManagerService to directly add UID to DeviceIdleController's temp
     * allowlist.
     * @param uid
     * @param duration duration in milliseconds
     * @param type temp allowlist type defined at {@link TempAllowListType}
     * @param sync
     * @param reasonCode one of {@link ReasonCode}
     * @param reason
     * @param callingUid UID of app who added this temp-allowlist.
     */
    void addPowerSaveTempWhitelistAppDirect(int uid, long duration,
            @TempAllowListType int type, boolean sync, @ReasonCode int reasonCode,
            @Nullable String reason, int callingUid);

    // duration in milliseconds
    long getNotificationAllowlistDuration();

    void setJobsActive(boolean active);

    // Up-call from alarm manager.
    void setAlarmsActive(boolean active);

    boolean isAppOnWhitelist(int appid);

    int[] getPowerSaveWhitelistUserAppIds();

    int[] getPowerSaveTempWhitelistAppIds();

    /**
     * Listener to be notified when DeviceIdleController determines that the device has moved or is
     * stationary.
     */
    interface StationaryListener {
        void onDeviceStationaryChanged(boolean isStationary);
    }

    /**
     * Registers a listener that will be notified when the system has detected that the device is
     * stationary or in motion.
     */
    void registerStationaryListener(StationaryListener listener);

    /**
     * Unregisters a registered stationary listener from being notified when the system has detected
     * that the device is stationary or in motion.
     */
    void unregisterStationaryListener(StationaryListener listener);

    /**
     * Apply some restrictions on temp allowlist type based on the reasonCode.
     * @param reasonCode temp allowlist reason code.
     * @param defaultType default temp allowlist type if reasonCode can not decide a type.
     * @return temp allowlist type based on the reasonCode.
     */
    @TempAllowListType int getTempAllowListType(@ReasonCode int reasonCode,
            @TempAllowListType int defaultType);
}
