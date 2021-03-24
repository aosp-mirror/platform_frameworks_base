/*
 * Copyright 2019 The Android Open Source Project
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

package com.android.server.timezonedetector;

import android.annotation.Nullable;
import android.app.AlarmManager;
import android.content.ContentResolver;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.SystemProperties;
import android.provider.Settings;

/**
 * The real implementation of {@link TimeZoneDetectorStrategyImpl.Callback}.
 */
public final class TimeZoneDetectorCallbackImpl implements TimeZoneDetectorStrategyImpl.Callback {

    private static final String TIMEZONE_PROPERTY = "persist.sys.timezone";

    private final Context mContext;
    private final ContentResolver mCr;

    TimeZoneDetectorCallbackImpl(Context context) {
        mContext = context;
        mCr = context.getContentResolver();
    }

    @Override
    public boolean isAutoTimeZoneDetectionEnabled() {
        if (isAutoTimeZoneDetectionSupported()) {
            return Settings.Global.getInt(mCr, Settings.Global.AUTO_TIME_ZONE, 1 /* default */) > 0;
        }
        return false;
    }

    private boolean isAutoTimeZoneDetectionSupported() {
        return deviceHasTelephonyNetwork();
    }

    private boolean deviceHasTelephonyNetwork() {
        return mContext.getPackageManager().hasSystemFeature(PackageManager.FEATURE_TELEPHONY);
    }

    @Override
    public boolean isDeviceTimeZoneInitialized() {
        // timezone.equals("GMT") will be true and only true if the time zone was
        // set to a default value by the system server (when starting, system server
        // sets the persist.sys.timezone to "GMT" if it's not set). "GMT" is not used by
        // any code that sets it explicitly (in case where something sets GMT explicitly,
        // "Etc/GMT" Olson ID would be used).

        String timeZoneId = getDeviceTimeZone();
        return timeZoneId != null && timeZoneId.length() > 0 && !timeZoneId.equals("GMT");
    }

    @Override
    @Nullable
    public String getDeviceTimeZone() {
        return SystemProperties.get(TIMEZONE_PROPERTY);
    }

    @Override
    public void setDeviceTimeZone(String zoneId) {
        AlarmManager alarmManager = mContext.getSystemService(AlarmManager.class);
        alarmManager.setTimeZone(zoneId);
    }
}
