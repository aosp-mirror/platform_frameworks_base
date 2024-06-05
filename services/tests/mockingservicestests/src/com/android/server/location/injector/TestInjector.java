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

import android.content.Context;

import com.android.server.location.settings.FakeLocationSettings;

public class TestInjector implements Injector {

    private final FakeUserInfoHelper mUserInfoHelper;
    private final FakeLocationSettings mLocationSettings;
    private final FakeAlarmHelper mAlarmHelper;
    private final FakeAppOpsHelper mAppOpsHelper;
    private final FakeLocationPermissionsHelper mLocationPermissionsHelper;
    private final FakeSettingsHelper mSettingsHelper;
    private final FakeAppForegroundHelper mAppForegroundHelper;
    private final FakeLocationPowerSaveModeHelper mLocationPowerSaveModeHelper;
    private final FakeScreenInteractiveHelper mScreenInteractiveHelper;
    private final FakeDeviceStationaryHelper mDeviceStationaryHelper;
    private final FakeDeviceIdleHelper mDeviceIdleHelper;
    private final FakeEmergencyHelper mEmergencyHelper;
    private final LocationUsageLogger mLocationUsageLogger;
    private final FakePackageResetHelper mPackageResetHelper;

    public TestInjector(Context context) {
        mUserInfoHelper = new FakeUserInfoHelper();
        mLocationSettings = new FakeLocationSettings(context);
        mAlarmHelper = new FakeAlarmHelper();
        mAppOpsHelper = new FakeAppOpsHelper();
        mLocationPermissionsHelper = new FakeLocationPermissionsHelper(mAppOpsHelper);
        mSettingsHelper = new FakeSettingsHelper();
        mAppForegroundHelper = new FakeAppForegroundHelper();
        mLocationPowerSaveModeHelper = new FakeLocationPowerSaveModeHelper();
        mScreenInteractiveHelper = new FakeScreenInteractiveHelper();
        mDeviceStationaryHelper = new FakeDeviceStationaryHelper();
        mDeviceIdleHelper = new FakeDeviceIdleHelper();
        mEmergencyHelper = new FakeEmergencyHelper();
        mLocationUsageLogger = new LocationUsageLogger();
        mPackageResetHelper = new FakePackageResetHelper();
    }

    @Override
    public FakeUserInfoHelper getUserInfoHelper() {
        return mUserInfoHelper;
    }

    @Override
    public FakeLocationSettings getLocationSettings() {
        return mLocationSettings;
    }

    @Override
    public FakeAlarmHelper getAlarmHelper() {
        return mAlarmHelper;
    }

    @Override
    public FakeAppOpsHelper getAppOpsHelper() {
        return mAppOpsHelper;
    }

    @Override
    public FakeLocationPermissionsHelper getLocationPermissionsHelper() {
        return mLocationPermissionsHelper;
    }

    @Override
    public FakeSettingsHelper getSettingsHelper() {
        return mSettingsHelper;
    }

    @Override
    public FakeAppForegroundHelper getAppForegroundHelper() {
        return mAppForegroundHelper;
    }

    @Override
    public FakeLocationPowerSaveModeHelper getLocationPowerSaveModeHelper() {
        return mLocationPowerSaveModeHelper;
    }

    @Override
    public FakeScreenInteractiveHelper getScreenInteractiveHelper() {
        return mScreenInteractiveHelper;
    }

    @Override
    public FakeDeviceStationaryHelper getDeviceStationaryHelper() {
        return mDeviceStationaryHelper;
    }

    @Override
    public FakeDeviceIdleHelper getDeviceIdleHelper() {
        return mDeviceIdleHelper;
    }

    @Override
    public FakeEmergencyHelper getEmergencyHelper() {
        return mEmergencyHelper;
    }

    @Override
    public LocationUsageLogger getLocationUsageLogger() {
        return mLocationUsageLogger;
    }

    @Override
    public FakePackageResetHelper getPackageResetHelper() {
        return mPackageResetHelper;
    }
}
