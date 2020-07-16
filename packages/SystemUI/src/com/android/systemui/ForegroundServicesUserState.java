/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.systemui;

import android.util.ArrayMap;
import android.util.ArraySet;

import java.util.Arrays;

/**
 * Struct to track relevant packages and notifications for a userid's foreground services.
 */
public class ForegroundServicesUserState {
    // shelf life of foreground services before they go bad
    private static final long FG_SERVICE_GRACE_MILLIS = 5000;

    private String[] mRunning = null;
    private long mServiceStartTime = 0;

    // package -> sufficiently important posted notification keys that signal an app is
    // running a foreground service
    private ArrayMap<String, ArraySet<String>> mImportantNotifications = new ArrayMap<>(1);
    // package -> standard layout posted notification keys that can display appOps
    private ArrayMap<String, ArraySet<String>> mStandardLayoutNotifications = new ArrayMap<>(1);

    // package -> app ops
    private ArrayMap<String, ArraySet<Integer>> mAppOps = new ArrayMap<>(1);

    public void setRunningServices(String[] pkgs, long serviceStartTime) {
        mRunning = pkgs != null ? Arrays.copyOf(pkgs, pkgs.length) : null;
        mServiceStartTime = serviceStartTime;
    }

    public void addOp(String pkg, int op) {
        if (mAppOps.get(pkg) == null) {
            mAppOps.put(pkg, new ArraySet<>(3));
        }
        mAppOps.get(pkg).add(op);
    }

    public boolean removeOp(String pkg, int op) {
        final boolean found;
        final ArraySet<Integer> keys = mAppOps.get(pkg);
        if (keys == null) {
            found = false;
        } else {
            found = keys.remove(op);
            if (keys.size() == 0) {
                mAppOps.remove(pkg);
            }
        }
        return found;
    }

    public void addImportantNotification(String pkg, String key) {
        addNotification(mImportantNotifications, pkg, key);
    }

    public boolean removeImportantNotification(String pkg, String key) {
        return removeNotification(mImportantNotifications, pkg, key);
    }

    public void addStandardLayoutNotification(String pkg, String key) {
        addNotification(mStandardLayoutNotifications, pkg, key);
    }

    public boolean removeStandardLayoutNotification(String pkg, String key) {
        return removeNotification(mStandardLayoutNotifications, pkg, key);
    }

    public boolean removeNotification(String pkg, String key) {
        boolean removed = false;
        removed |= removeImportantNotification(pkg, key);
        removed |= removeStandardLayoutNotification(pkg, key);
        return removed;
    }

    public void addNotification(ArrayMap<String, ArraySet<String>> map, String pkg,
            String key) {
        if (map.get(pkg) == null) {
            map.put(pkg, new ArraySet<>());
        }
        map.get(pkg).add(key);
    }

    public boolean removeNotification(ArrayMap<String, ArraySet<String>> map,
            String pkg, String key) {
        final boolean found;
        final ArraySet<String> keys = map.get(pkg);
        if (keys == null) {
            found = false;
        } else {
            found = keys.remove(key);
            if (keys.size() == 0) {
                map.remove(pkg);
            }
        }
        return found;
    }

    /**
     * System disclosures for foreground services are required if an app has a foreground service
     * running AND the app hasn't posted its own notification signalling it is running a
     * foreground service
     */
    public boolean isDisclosureNeeded() {
        if (mRunning != null
                && System.currentTimeMillis() - mServiceStartTime
                >= FG_SERVICE_GRACE_MILLIS) {

            for (String pkg : mRunning) {
                final ArraySet<String> set = mImportantNotifications.get(pkg);
                if (set == null || set.size() == 0) {
                    return true;
                }
            }
        }
        return false;
    }

    public ArraySet<Integer> getFeatures(String pkg) {
        return mAppOps.get(pkg);
    }

    /**
     * Gets the notifications with standard layouts associated with this package
     */
    public ArraySet<String> getStandardLayoutKeys(String pkg) {
        final ArraySet<String> set = mStandardLayoutNotifications.get(pkg);
        if (set == null || set.size() == 0) {
            return null;
        }
        return set;
    }

    @Override
    public String toString() {
        return "UserServices{"
                + "mRunning=" + Arrays.toString(mRunning)
                + ", mServiceStartTime=" + mServiceStartTime
                + ", mImportantNotifications=" + mImportantNotifications
                + ", mStandardLayoutNotifications=" + mStandardLayoutNotifications
                + '}';
    }
}
