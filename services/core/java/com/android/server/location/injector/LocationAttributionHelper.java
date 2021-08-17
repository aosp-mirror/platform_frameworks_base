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
import android.util.ArraySet;
import android.util.Log;

import com.android.internal.annotations.GuardedBy;

import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Helps manage appop monitoring for multiple location clients.
 */
public class LocationAttributionHelper {

    private static class BucketKey {
        private final String mBucket;
        private final Object mKey;

        private BucketKey(String bucket, Object key) {
            mBucket = Objects.requireNonNull(bucket);
            mKey = Objects.requireNonNull(key);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (o == null || getClass() != o.getClass()) {
                return false;
            }

            BucketKey that = (BucketKey) o;
            return mBucket.equals(that.mBucket)
                    && mKey.equals(that.mKey);
        }

        @Override
        public int hashCode() {
            return Objects.hash(mBucket, mKey);
        }
    }

    private final AppOpsHelper mAppOpsHelper;

    @GuardedBy("this")
    private final Map<CallerIdentity, Set<BucketKey>> mAttributions;
    @GuardedBy("this")
    private final Map<CallerIdentity, Set<BucketKey>> mHighPowerAttributions;

    public LocationAttributionHelper(AppOpsHelper appOpsHelper) {
        mAppOpsHelper = appOpsHelper;

        mAttributions = new ArrayMap<>();
        mHighPowerAttributions = new ArrayMap<>();
    }

    /**
     * Report normal location usage for the given caller in the given bucket, with a unique key.
     */
    public synchronized void reportLocationStart(CallerIdentity identity, String bucket,
            Object key) {
        Set<BucketKey> keySet = mAttributions.computeIfAbsent(identity,
                i -> new ArraySet<>());
        boolean empty = keySet.isEmpty();
        if (keySet.add(new BucketKey(bucket, key)) && empty) {
            if (!mAppOpsHelper.startOpNoThrow(OP_MONITOR_LOCATION, identity)) {
                mAttributions.remove(identity);
            }
        }
    }

    /**
     * Report normal location usage has stopped for the given caller in the given bucket, with a
     * unique key.
     */
    public synchronized void reportLocationStop(CallerIdentity identity, String bucket,
            Object key) {
        Set<BucketKey> keySet = mAttributions.get(identity);
        if (keySet != null && keySet.remove(new BucketKey(bucket, key))
                && keySet.isEmpty()) {
            mAttributions.remove(identity);
            mAppOpsHelper.finishOp(OP_MONITOR_LOCATION, identity);
        }
    }

    /**
     * Report high power location usage for the given caller in the given bucket, with a unique
     * key.
     */
    public synchronized void reportHighPowerLocationStart(CallerIdentity identity, String bucket,
            Object key) {
        Set<BucketKey> keySet = mHighPowerAttributions.computeIfAbsent(identity,
                i -> new ArraySet<>());
        boolean empty = keySet.isEmpty();
        if (keySet.add(new BucketKey(bucket, key)) && empty) {
            if (mAppOpsHelper.startOpNoThrow(OP_MONITOR_HIGH_POWER_LOCATION, identity)) {
                if (D) {
                    Log.v(TAG, "starting high power location attribution for " + identity);
                }
            } else {
                mHighPowerAttributions.remove(identity);
            }
        }
    }

    /**
     * Report high power location usage has stopped for the given caller in the given bucket,
     * with a unique key.
     */
    public synchronized void reportHighPowerLocationStop(CallerIdentity identity, String bucket,
            Object key) {
        Set<BucketKey> keySet = mHighPowerAttributions.get(identity);
        if (keySet != null && keySet.remove(new BucketKey(bucket, key))
                && keySet.isEmpty()) {
            if (D) {
                Log.v(TAG, "stopping high power location attribution for " + identity);
            }
            mHighPowerAttributions.remove(identity);
            mAppOpsHelper.finishOp(OP_MONITOR_HIGH_POWER_LOCATION, identity);
        }
    }
}
