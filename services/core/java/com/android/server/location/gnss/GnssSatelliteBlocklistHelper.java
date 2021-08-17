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

package com.android.server.location.gnss;

import android.content.ContentResolver;
import android.content.Context;
import android.database.ContentObserver;
import android.os.Handler;
import android.os.Looper;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;

import java.util.ArrayList;
import java.util.List;

/**
 * Detects denylist change and updates the denylist.
 */
class GnssSatelliteBlocklistHelper {

    private static final String TAG = "GnssBlocklistHelper";
    private static final String BLOCKLIST_DELIMITER = ",";

    private final Context mContext;
    private final GnssSatelliteBlocklistCallback mCallback;

    interface GnssSatelliteBlocklistCallback {
        void onUpdateSatelliteBlocklist(int[] constellations, int[] svids);
    }

    GnssSatelliteBlocklistHelper(Context context, Looper looper,
            GnssSatelliteBlocklistCallback callback) {
        mContext = context;
        mCallback = callback;
        ContentObserver contentObserver = new ContentObserver(new Handler(looper)) {
            @Override
            public void onChange(boolean selfChange) {
                updateSatelliteBlocklist();
            }
        };
        mContext.getContentResolver().registerContentObserver(
                Settings.Global.getUriFor(
                        Settings.Global.GNSS_SATELLITE_BLOCKLIST),
                true,
                contentObserver, UserHandle.USER_ALL);
    }

    void updateSatelliteBlocklist() {
        ContentResolver resolver = mContext.getContentResolver();
        String blocklist = Settings.Global.getString(
                resolver,
                Settings.Global.GNSS_SATELLITE_BLOCKLIST);
        if (blocklist == null) {
            blocklist = "";
        }
        Log.i(TAG, String.format("Update GNSS satellite blocklist: %s", blocklist));

        List<Integer> blocklistValues;
        try {
            blocklistValues = parseSatelliteBlocklist(blocklist);
        } catch (NumberFormatException e) {
            Log.e(TAG, "Exception thrown when parsing blocklist string.", e);
            return;
        }

        if (blocklistValues.size() % 2 != 0) {
            Log.e(TAG, "blocklist string has odd number of values."
                    + "Aborting updateSatelliteBlocklist");
            return;
        }

        int length = blocklistValues.size() / 2;
        int[] constellations = new int[length];
        int[] svids = new int[length];
        for (int i = 0; i < length; i++) {
            constellations[i] = blocklistValues.get(i * 2);
            svids[i] = blocklistValues.get(i * 2 + 1);
        }
        mCallback.onUpdateSatelliteBlocklist(constellations, svids);
    }

    @VisibleForTesting
    static List<Integer> parseSatelliteBlocklist(String blocklist) throws NumberFormatException {
        String[] strings = blocklist.split(BLOCKLIST_DELIMITER);
        List<Integer> parsed = new ArrayList<>(strings.length);
        for (String string : strings) {
            string = string.trim();
            if (!"".equals(string)) {
                int value = Integer.parseInt(string);
                if (value < 0) {
                    throw new NumberFormatException("Negative value is invalid.");
                }
                parsed.add(value);
            }
        }
        return parsed;
    }
}
