/*
 * Copyright (C) 2017 The Android Open Source Project
 * Copyright (C) 2014-2017 The Dirty Unicorns Project
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

package com.android.settings.fuelgauge;

import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import com.android.settings.fuelgauge.PowerUsageFeatureProvider;
import java.util.List;

public final class PowerUsageFeatureProviderImpl
implements PowerUsageFeatureProvider {
    static final String ADDITIONAL_BATTERY_INFO_ACTION = "com.google.android.apps.turbo.SHOW_ADDITIONAL_BATTERY_INFO";
    static final String ADDITIONAL_BATTERY_INFO_PACKAGE = "com.google.android.apps.turbo";
    static final String ADDITIONAL_BATTERY_INFO_ENABLED = "settings:additional_battery_info_enabled";
    private Context mContext;

    public PowerUsageFeatureProviderImpl(Context context) {
        mContext = context;
    }

    public Intent getAdditionalBatteryInfoIntent() {
        Intent intent = new Intent(ADDITIONAL_BATTERY_INFO_ACTION);
        return intent.setPackage(ADDITIONAL_BATTERY_INFO_PACKAGE);
    }

    /*
     * Enabled force condition propagation
     * Lifted jumps to return sites
     */
    public boolean isAdditionalBatteryInfoEnabled() {
        Intent intent = getAdditionalBatteryInfoIntent();
        boolean bl2 = mContext.getPackageManager().queryIntentActivities(intent, 0).isEmpty();
        if (!bl2) return true;
        return false;
    }
}