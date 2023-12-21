/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.settingslib.wifi;

import static android.os.UserManager.DISALLOW_CONFIG_WIFI;

import android.content.Context;
import android.os.Bundle;
import android.os.UserManager;
import android.util.SparseArray;

import androidx.annotation.NonNull;
import androidx.annotation.VisibleForTesting;

import java.util.HashMap;
import java.util.Map;

/**
 * This is a singleton class for Wi-Fi restrictions caching.
 */
public class WifiRestrictionsCache {
    private static final String TAG = "WifiResCache";

    /**
     * Manages mapping between user ID and corresponding singleton {@link WifiRestrictionsCache}
     * object.
     */
    @VisibleForTesting
    protected static final SparseArray<WifiRestrictionsCache> sInstances = new SparseArray<>();

    @VisibleForTesting
    protected UserManager mUserManager;
    @VisibleForTesting
    protected Bundle mUserRestrictions;
    @VisibleForTesting
    protected final Map<String, Boolean> mRestrictions = new HashMap<>();

    /**
     * @return an instance of {@link WifiRestrictionsCache} object.
     */
    @NonNull
    public static WifiRestrictionsCache getInstance(@NonNull Context context) {
        final int requestUserId = context.getUserId();
        WifiRestrictionsCache cache;
        synchronized (sInstances) {
            // We have same user context as request.
            if (sInstances.indexOfKey(requestUserId) >= 0) {
                return sInstances.get(requestUserId);
            }
            // Request by a new user context.
            cache = new WifiRestrictionsCache(context);
            sInstances.put(context.getUserId(), cache);
        }
        return cache;
    }

    /**
     * Removes all the instances.
     */
    public static void clearInstance() {
        synchronized (sInstances) {
            for (int i = 0; i < sInstances.size(); i++) {
                int key = sInstances.keyAt(i);
                WifiRestrictionsCache cache = sInstances.get(key);
                cache.clearRestrictions();
                sInstances.remove(key);
            }
            sInstances.clear();
        }
    }

    /**
     * Constructor to create a singleton class for Wi-Fi restrictions cache.
     *
     * @param context The Context this is associated with.
     */
    protected WifiRestrictionsCache(@NonNull Context context) {
        mUserManager = context.getSystemService(UserManager.class);
        if (mUserManager != null) {
            mUserRestrictions = mUserManager.getUserRestrictions();
        }
    }

    /**
     * @return the boolean value of the restrictions
     */
    public Boolean getRestriction(String key) {
        if (mUserRestrictions == null) {
            return false;
        }
        Boolean restriction;
        synchronized (mRestrictions) {
            if (mRestrictions.containsKey(key)) {
                return mRestrictions.get(key);
            }
            restriction = mUserRestrictions.getBoolean(key);
            mRestrictions.put(key, restriction);
        }
        return restriction;
    }

    /**
     * Removes all the restrictions.
     */
    public void clearRestrictions() {
        synchronized (mRestrictions) {
            mRestrictions.clear();
        }
    }

    /**
     * @return Whether the user is allowed to config Wi-Fi.
     */
    public Boolean isConfigWifiAllowed() {
        return !getRestriction(DISALLOW_CONFIG_WIFI);
    }
}
