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

import com.android.server.deviceidle.IDeviceIdleConstraint;

public interface DeviceIdleInternal {
    void onConstraintStateChanged(IDeviceIdleConstraint constraint, boolean active);

    void registerDeviceIdleConstraint(IDeviceIdleConstraint constraint, String name,
            @IDeviceIdleConstraint.MinimumState int minState);

    void unregisterDeviceIdleConstraint(IDeviceIdleConstraint constraint);

    void exitIdle(String reason);

    // duration in milliseconds
    void addPowerSaveTempWhitelistApp(int callingUid, String packageName,
            long duration, int userId, boolean sync, String reason);

    // duration in milliseconds
    void addPowerSaveTempWhitelistAppDirect(int uid, long duration, boolean sync,
            String reason);

    // duration in milliseconds
    long getNotificationWhitelistDuration();

    void setJobsActive(boolean active);

    // Up-call from alarm manager.
    void setAlarmsActive(boolean active);

    boolean isAppOnWhitelist(int appid);

    int[] getPowerSaveWhitelistUserAppIds();

    int[] getPowerSaveTempWhitelistAppIds();
}
