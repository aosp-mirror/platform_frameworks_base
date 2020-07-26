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

    private static class ProviderListener {
        private final String mProvider;
        private final Object mKey;

        private ProviderListener(String provider, Object key) {
            mProvider = Objects.requireNonNull(provider);
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

            ProviderListener that = (ProviderListener) o;
            return mProvider.equals(that.mProvider)
                    && mKey.equals(that.mKey);
        }

        @Override
        public int hashCode() {
            return Objects.hash(mProvider, mKey);
        }
    }

    private final AppOpsHelper mAppOpsHelper;

    @GuardedBy("this")
    private final Map<CallerIdentity, Set<ProviderListener>> mAttributions;
    @GuardedBy("this")
    private final Map<CallerIdentity, Set<ProviderListener>> mHighPowerAttributions;

    public LocationAttributionHelper(AppOpsHelper appOpsHelper) {
        mAppOpsHelper = appOpsHelper;

        mAttributions = new ArrayMap<>();
        mHighPowerAttributions = new ArrayMap<>();
    }

    /**
     * Report normal location usage for the given caller on the given provider, with a unique key.
     */
    public synchronized void reportLocationStart(CallerIdentity identity, String provider,
            Object key) {
        Set<ProviderListener> keySet = mAttributions.computeIfAbsent(identity,
                i -> new ArraySet<>());
        boolean empty = keySet.isEmpty();
        if (keySet.add(new ProviderListener(provider, key)) && empty) {
            if (!mAppOpsHelper.startOpNoThrow(OP_MONITOR_LOCATION, identity)) {
                mAttributions.remove(identity);
            }
        }
    }

    /**
     * Report normal location usage has stopped for the given caller on the given provider, with a
     * unique key.
     */
    public synchronized void reportLocationStop(CallerIdentity identity, String provider,
            Object key) {
        Set<ProviderListener> keySet = mAttributions.get(identity);
        if (keySet != null && keySet.remove(new ProviderListener(provider, key))
                && keySet.isEmpty()) {
            mAttributions.remove(identity);
            mAppOpsHelper.finishOp(OP_MONITOR_LOCATION, identity);
        }
    }

    /**
     * Report high power location usage for the given caller on the given provider, with a unique
     * key.
     */
    public synchronized void reportHighPowerLocationStart(CallerIdentity identity, String provider,
            Object key) {
        Set<ProviderListener> keySet = mHighPowerAttributions.computeIfAbsent(identity,
                i -> new ArraySet<>());
        boolean empty = keySet.isEmpty();
        if (keySet.add(new ProviderListener(provider, key)) && empty) {
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
     * Report high power location usage has stopped for the given caller on the given provider,
     * with a unique key.
     */
    public synchronized void reportHighPowerLocationStop(CallerIdentity identity, String provider,
            Object key) {
        Set<ProviderListener> keySet = mHighPowerAttributions.get(identity);
        if (keySet != null && keySet.remove(new ProviderListener(provider, key))
                && keySet.isEmpty()) {
            if (D) {
                Log.v(TAG, "stopping high power location attribution for " + identity);
            }
            mHighPowerAttributions.remove(identity);
            mAppOpsHelper.finishOp(OP_MONITOR_HIGH_POWER_LOCATION, identity);
        }
    }
}
