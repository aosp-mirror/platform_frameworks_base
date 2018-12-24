/*
 * Copyright 2018 The Android Open Source Project
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
package com.android.settingslib.media;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * ConnectionRecordManager represents the sharedPreferences operation on device usage record
 */
public class ConnectionRecordManager {
    private static final Object sInstanceSync = new Object();
    private static final String KEY_LAST_SELECTED_DEVICE = "last_selected_device";
    private static final String SHARED_PREFERENCES_NAME = "seamless_transfer_record";
    private static final String TAG = "ConnectionRecordManager";
    private static ConnectionRecordManager sInstance;

    private String mLastSelectedDevice;

    /**
     * Get an {@code ConnectionRecordManager} instance (create one if necessary).
     */
    public static ConnectionRecordManager getInstance() {
        synchronized (sInstanceSync) {
            if (sInstance == null) {
                sInstance = new ConnectionRecordManager();
            }
        }
        return sInstance;
    }

    private SharedPreferences getSharedPreferences(Context context) {
        return context.getSharedPreferences(SHARED_PREFERENCES_NAME, Context.MODE_PRIVATE);
    }

    /**
     * Get connection record from sharedPreferences
     *
     * @param id a unique device Id
     * @return the the usage result
     */
    public synchronized int fetchConnectionRecord(Context context, String id) {
        return getSharedPreferences(context).getInt(id, 0);
    }

    /**
     * Get the last selected device from sharedPreferences
     */
    public synchronized void fetchLastSelectedDevice(Context context) {
        mLastSelectedDevice = getSharedPreferences(context).getString(KEY_LAST_SELECTED_DEVICE,
                null);
    }

    /**
     * Set device usage time and last selected device in sharedPreference
     *
     * @param id a unique device Id
     * @param record usage times
     */
    public synchronized void setConnectionRecord(Context context, String id, int record) {
        final SharedPreferences.Editor editor = getSharedPreferences(context).edit();
        // Update used times
        mLastSelectedDevice = id;
        editor.putInt(mLastSelectedDevice, record);
        // Update last used device
        editor.putString(KEY_LAST_SELECTED_DEVICE, mLastSelectedDevice);
        editor.apply();
    }

    /**
     * @return the last selected device
     */
    public synchronized String getLastSelectedDevice() {
        return mLastSelectedDevice;
    }
}
