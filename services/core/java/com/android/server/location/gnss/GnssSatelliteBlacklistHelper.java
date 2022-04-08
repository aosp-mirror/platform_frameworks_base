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
 * Detects blacklist change and updates the blacklist.
 */
class GnssSatelliteBlacklistHelper {

    private static final String TAG = "GnssBlacklistHelper";
    private static final String BLACKLIST_DELIMITER = ",";

    private final Context mContext;
    private final GnssSatelliteBlacklistCallback mCallback;

    interface GnssSatelliteBlacklistCallback {
        void onUpdateSatelliteBlacklist(int[] constellations, int[] svids);
    }

    GnssSatelliteBlacklistHelper(Context context, Looper looper,
            GnssSatelliteBlacklistCallback callback) {
        mContext = context;
        mCallback = callback;
        ContentObserver contentObserver = new ContentObserver(new Handler(looper)) {
            @Override
            public void onChange(boolean selfChange) {
                updateSatelliteBlacklist();
            }
        };
        mContext.getContentResolver().registerContentObserver(
                Settings.Global.getUriFor(
                        Settings.Global.GNSS_SATELLITE_BLACKLIST),
                true,
                contentObserver, UserHandle.USER_ALL);
    }

    void updateSatelliteBlacklist() {
        ContentResolver resolver = mContext.getContentResolver();
        String blacklist = Settings.Global.getString(
                resolver,
                Settings.Global.GNSS_SATELLITE_BLACKLIST);
        if (blacklist == null) {
            blacklist = "";
        }
        Log.i(TAG, String.format("Update GNSS satellite blacklist: %s", blacklist));

        List<Integer> blacklistValues;
        try {
            blacklistValues = parseSatelliteBlacklist(blacklist);
        } catch (NumberFormatException e) {
            Log.e(TAG, "Exception thrown when parsing blacklist string.", e);
            return;
        }

        if (blacklistValues.size() % 2 != 0) {
            Log.e(TAG, "blacklist string has odd number of values."
                    + "Aborting updateSatelliteBlacklist");
            return;
        }

        int length = blacklistValues.size() / 2;
        int[] constellations = new int[length];
        int[] svids = new int[length];
        for (int i = 0; i < length; i++) {
            constellations[i] = blacklistValues.get(i * 2);
            svids[i] = blacklistValues.get(i * 2 + 1);
        }
        mCallback.onUpdateSatelliteBlacklist(constellations, svids);
    }

    @VisibleForTesting
    static List<Integer> parseSatelliteBlacklist(String blacklist) throws NumberFormatException {
        String[] strings = blacklist.split(BLACKLIST_DELIMITER);
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
