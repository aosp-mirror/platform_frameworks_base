/**
 * Copyright (C) 2015 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy
 * of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.android.server.usage;

import android.util.ArrayMap;
import android.util.SparseArray;

import com.android.internal.util.IndentingPrintWriter;

/**
 * Keeps track of recent active state changes in apps.
 * Access should be guarded by a lock by the caller.
 */
public class AppIdleHistory {

    private SparseArray<ArrayMap<String,byte[]>> mIdleHistory = new SparseArray<>();
    private long lastPeriod = 0;
    private static final long ONE_MINUTE = 60 * 1000;
    private static final int HISTORY_SIZE = 100;
    private static final int FLAG_LAST_STATE = 2;
    private static final int FLAG_PARTIAL_ACTIVE = 1;
    private static final long PERIOD_DURATION = UsageStatsService.COMPRESS_TIME ? ONE_MINUTE
            : 60 * ONE_MINUTE;

    public void addEntry(String packageName, int userId, boolean idle, long timeNow) {
        ArrayMap<String, byte[]> userHistory = getUserHistory(userId);
        byte[] packageHistory = getPackageHistory(userHistory, packageName);

        long thisPeriod = timeNow / PERIOD_DURATION;
        // Has the period switched over? Slide all users' package histories
        if (lastPeriod != 0 && lastPeriod < thisPeriod
                && (thisPeriod - lastPeriod) < HISTORY_SIZE - 1) {
            int diff = (int) (thisPeriod - lastPeriod);
            final int NUSERS = mIdleHistory.size();
            for (int u = 0; u < NUSERS; u++) {
                userHistory = mIdleHistory.valueAt(u);
                for (byte[] history : userHistory.values()) {
                    // Shift left
                    System.arraycopy(history, diff, history, 0, HISTORY_SIZE - diff);
                    // Replicate last state across the diff
                    for (int i = 0; i < diff; i++) {
                        history[HISTORY_SIZE - i - 1] =
                                (byte) (history[HISTORY_SIZE - diff - 1] & FLAG_LAST_STATE);
                    }
                }
            }
        }
        lastPeriod = thisPeriod;
        if (!idle) {
            packageHistory[HISTORY_SIZE - 1] = FLAG_LAST_STATE | FLAG_PARTIAL_ACTIVE;
        } else {
            packageHistory[HISTORY_SIZE - 1] &= ~FLAG_LAST_STATE;
        }
    }

    private ArrayMap<String, byte[]> getUserHistory(int userId) {
        ArrayMap<String, byte[]> userHistory = mIdleHistory.get(userId);
        if (userHistory == null) {
            userHistory = new ArrayMap<>();
            mIdleHistory.put(userId, userHistory);
        }
        return userHistory;
    }

    private byte[] getPackageHistory(ArrayMap<String, byte[]> userHistory, String packageName) {
        byte[] packageHistory = userHistory.get(packageName);
        if (packageHistory == null) {
            packageHistory = new byte[HISTORY_SIZE];
            userHistory.put(packageName, packageHistory);
        }
        return packageHistory;
    }

    public void removeUser(int userId) {
        mIdleHistory.remove(userId);
    }

    public boolean isIdle(int userId, String packageName) {
        ArrayMap<String, byte[]> userHistory = getUserHistory(userId);
        byte[] packageHistory = getPackageHistory(userHistory, packageName);
        return (packageHistory[HISTORY_SIZE - 1] & FLAG_LAST_STATE) == 0;
    }

    public void dump(IndentingPrintWriter idpw, int userId) {
        ArrayMap<String, byte[]> userHistory = mIdleHistory.get(userId);
        if (userHistory == null) return;
        final int P = userHistory.size();
        for (int p = 0; p < P; p++) {
            final String packageName = userHistory.keyAt(p);
            final byte[] history = userHistory.valueAt(p);
            for (int i = 0; i < HISTORY_SIZE; i++) {
                idpw.print(history[i] == 0 ? '.' : 'A');
            }
            idpw.print("  " + packageName);
            idpw.println();
        }
    }
}