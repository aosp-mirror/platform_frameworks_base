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

import static android.app.AppOpsManager.OP_MONITOR_HIGH_POWER_LOCATION;
import static android.app.AppOpsManager.OP_MONITOR_LOCATION;

import static com.android.server.location.LocationManagerService.D;
import static com.android.server.location.LocationManagerService.TAG;

import android.location.util.identity.CallerIdentity;
import android.util.ArrayMap;
import android.util.Log;

import com.android.internal.annotations.GuardedBy;

import java.util.Map;

/**
 * Helps manage appop monitoring for multiple location clients.
 */
public class LocationAttributionHelper {

    private final AppOpsHelper mAppOpsHelper;

    @GuardedBy("this")
    private final Map<CallerIdentity, Integer> mAttributions;
    @GuardedBy("this")
    private final Map<CallerIdentity, Integer> mHighPowerAttributions;

    public LocationAttributionHelper(AppOpsHelper appOpsHelper) {
        mAppOpsHelper = appOpsHelper;

        mAttributions = new ArrayMap<>();
        mHighPowerAttributions = new ArrayMap<>();
    }

    /**
     * Report normal location usage for the given caller in the given bucket, with a unique key.
     */
    public synchronized void reportLocationStart(CallerIdentity identity) {
        identity = CallerIdentity.forAggregation(identity);

        int count = mAttributions.getOrDefault(identity, 0);
        if (count == 0) {
            if (mAppOpsHelper.startOpNoThrow(OP_MONITOR_LOCATION, identity)) {
                mAttributions.put(identity, 1);
            }
        } else {
            mAttributions.put(identity, count + 1);
        }
    }

    /**
     * Report normal location usage has stopped for the given caller in the given bucket, with a
     * unique key.
     */
    public synchronized void reportLocationStop(CallerIdentity identity) {
        identity = CallerIdentity.forAggregation(identity);

        int count = mAttributions.getOrDefault(identity, 0);
        if (count == 1) {
            mAttributions.remove(identity);
            mAppOpsHelper.finishOp(OP_MONITOR_LOCATION, identity);
        } else if (count > 1) {
            mAttributions.put(identity, count - 1);
        }
    }

    /**
     * Report high power location usage for the given caller in the given bucket, with a unique
     * key.
     */
    public synchronized void reportHighPowerLocationStart(CallerIdentity identity) {
        identity = CallerIdentity.forAggregation(identity);

        int count = mHighPowerAttributions.getOrDefault(identity, 0);
        if (count == 0) {
            if (D) {
                Log.v(TAG, "starting high power location attribution for " + identity);
            }
            if (mAppOpsHelper.startOpNoThrow(OP_MONITOR_HIGH_POWER_LOCATION, identity)) {
                mHighPowerAttributions.put(identity, 1);
            }
        } else {
            mHighPowerAttributions.put(identity, count + 1);
        }
    }

    /**
     * Report high power location usage has stopped for the given caller in the given bucket,
     * with a unique key.
     */
    public synchronized void reportHighPowerLocationStop(CallerIdentity identity) {
        identity = CallerIdentity.forAggregation(identity);

        int count = mHighPowerAttributions.getOrDefault(identity, 0);
        if (count == 1) {
            if (D) {
                Log.v(TAG, "stopping high power location attribution for " + identity);
            }
            mHighPowerAttributions.remove(identity);
            mAppOpsHelper.finishOp(OP_MONITOR_HIGH_POWER_LOCATION, identity);
        } else if (count > 1) {
            mHighPowerAttributions.put(identity, count - 1);
        }
    }
}
