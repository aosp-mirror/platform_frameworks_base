/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.server.location.injector;

import com.android.internal.annotations.VisibleForTesting;
import com.android.server.location.settings.LocationSettings;

/**
 * Injects various location dependencies so that they may be controlled by tests.
 */
@VisibleForTesting
public interface Injector {

    /** Returns a UserInfoHelper. */
    UserInfoHelper getUserInfoHelper();

    /** Returns a LocationSettings. */
    LocationSettings getLocationSettings();

    /** Returns an AlarmHelper. */
    AlarmHelper getAlarmHelper();

    /** Returns an AppOpsHelper. */
    AppOpsHelper getAppOpsHelper();

    /** Returns a LocationPermissionsHelper. */
    LocationPermissionsHelper getLocationPermissionsHelper();

    /** Returns a SettingsHelper. */
    SettingsHelper getSettingsHelper();

    /** Returns an AppForegroundHelper. */
    AppForegroundHelper getAppForegroundHelper();

    /** Returns a LocationPowerSaveModeHelper. */
    LocationPowerSaveModeHelper getLocationPowerSaveModeHelper();

    /** Returns a ScreenInteractiveHelper. */
    ScreenInteractiveHelper getScreenInteractiveHelper();

    /** Returns a DeviceStationaryHelper. */
    DeviceStationaryHelper getDeviceStationaryHelper();

    /** Returns a DeviceIdleHelper. */
    DeviceIdleHelper getDeviceIdleHelper();

    /** Returns an EmergencyHelper. */
    EmergencyHelper getEmergencyHelper();

    /** Returns a LocationUsageLogger. */
    LocationUsageLogger getLocationUsageLogger();
}
