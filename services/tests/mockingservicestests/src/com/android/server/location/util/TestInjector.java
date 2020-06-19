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

package com.android.server.location.util;

public class TestInjector implements Injector {

    private final FakeUserInfoHelper mUserInfoHelper;
    private final FakeAppOpsHelper mAppOpsHelper;
    private final FakeSettingsHelper mSettingsHelper;
    private final FakeAppForegroundHelper mAppForegroundHelper;
    private final LocationUsageLogger mLocationUsageLogger;
    private final LocationAttributionHelper mLocationAttributionHelper;

    public TestInjector() {
        mUserInfoHelper = new FakeUserInfoHelper();
        mAppOpsHelper = new FakeAppOpsHelper();
        mSettingsHelper = new FakeSettingsHelper();
        mAppForegroundHelper = new FakeAppForegroundHelper();
        mLocationUsageLogger = new LocationUsageLogger();
        mLocationAttributionHelper = new LocationAttributionHelper(mAppOpsHelper);
    }

    @Override
    public FakeUserInfoHelper getUserInfoHelper() {
        return mUserInfoHelper;
    }

    @Override
    public FakeAppOpsHelper getAppOpsHelper() {
        return mAppOpsHelper;
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
    public LocationUsageLogger getLocationUsageLogger() {
        return mLocationUsageLogger;
    }

    @Override
    public LocationAttributionHelper getLocationAttributionHelper() {
        return mLocationAttributionHelper;
    }
}
