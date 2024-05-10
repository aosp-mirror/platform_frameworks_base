/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.server.appop;

import android.annotation.NonNull;
import android.app.AppOpsManager;
import android.os.UserHandle;
import android.util.ArrayMap;
import android.util.AtomicFile;
import android.util.SparseArray;
import android.util.SparseIntArray;

import com.android.internal.annotations.GuardedBy;
import com.android.server.SystemServiceManager;

import java.io.File;
import java.util.Collections;
import java.util.Map;

/**
 * Provider of legacy app-ops data for new permission subsystem.
 *
 * @hide
 */
public class AppOpMigrationHelperImpl implements AppOpMigrationHelper {
    private SparseArray<Map<Integer, Map<String, Integer>>> mAppIdAppOpModes = null;
    private SparseArray<Map<String, Map<String, Integer>>> mPackageAppOpModes = null;
    private int mVersionAtBoot;

    private final Object mLock = new Object();

    @Override
    @GuardedBy("mLock")
    @NonNull
    public Map<Integer, Map<String, Integer>> getLegacyAppIdAppOpModes(int userId) {
        synchronized (mLock) {
            if (mAppIdAppOpModes == null) {
                readLegacyAppOpState();
            }
        }
        return mAppIdAppOpModes.get(userId, Collections.emptyMap());
    }

    @Override
    @GuardedBy("mLock")
    @NonNull
    public Map<String, Map<String, Integer>> getLegacyPackageAppOpModes(int userId) {
        synchronized (mLock) {
            if (mPackageAppOpModes == null) {
                readLegacyAppOpState();
            }
        }
        return mPackageAppOpModes.get(userId, Collections.emptyMap());
    }

    @GuardedBy("mLock")
    private void readLegacyAppOpState() {
        final File systemDir = SystemServiceManager.ensureSystemDir();
        AtomicFile appOpFile = new AtomicFile(new File(systemDir, "appops.xml"));

        final SparseArray<SparseIntArray> uidAppOpModes = new SparseArray<>();
        final SparseArray<ArrayMap<String, SparseIntArray>> packageAppOpModes =
                new SparseArray<>();

        LegacyAppOpStateParser parser = new LegacyAppOpStateParser();
        final int version = parser.readState(appOpFile, uidAppOpModes, packageAppOpModes);
        // -1 No app ops data available
        // 0 appops.xml exist w/o any version
        switch (version) {
            case -2:
                mVersionAtBoot = -1;
                break;
            case -1:
                mVersionAtBoot = 0;
                break;
            default:
                mVersionAtBoot = version;
        }
        mAppIdAppOpModes = getAppIdAppOpModes(uidAppOpModes);
        mPackageAppOpModes = getPackageAppOpModes(packageAppOpModes);
    }

    private SparseArray<Map<Integer, Map<String, Integer>>> getAppIdAppOpModes(
            SparseArray<SparseIntArray> uidAppOpModes) {
        SparseArray<Map<Integer, Map<String, Integer>>> userAppIdAppOpModes = new SparseArray<>();

        int size = uidAppOpModes.size();
        for (int uidIndex = 0; uidIndex < size; uidIndex++) {
            int uid = uidAppOpModes.keyAt(uidIndex);
            int userId = UserHandle.getUserId(uid);
            Map<Integer, Map<String, Integer>> appIdAppOpModes = userAppIdAppOpModes.get(userId);
            if (appIdAppOpModes == null) {
                appIdAppOpModes = new ArrayMap<>();
                userAppIdAppOpModes.put(userId, appIdAppOpModes);
            }

            SparseIntArray appOpModes = uidAppOpModes.valueAt(uidIndex);
            appIdAppOpModes.put(UserHandle.getAppId(uid), getAppOpModesForOpName(appOpModes));
        }
        return userAppIdAppOpModes;
    }

    private SparseArray<Map<String, Map<String, Integer>>> getPackageAppOpModes(
            SparseArray<ArrayMap<String, SparseIntArray>> legacyPackageAppOpModes) {
        SparseArray<Map<String, Map<String, Integer>>> userPackageAppOpModes = new SparseArray<>();

        int usersSize = legacyPackageAppOpModes.size();
        for (int userIndex = 0; userIndex < usersSize; userIndex++) {
            int userId = legacyPackageAppOpModes.keyAt(userIndex);
            Map<String, Map<String, Integer>> packageAppOpModes = userPackageAppOpModes.get(userId);
            if (packageAppOpModes == null) {
                packageAppOpModes = new ArrayMap<>();
                userPackageAppOpModes.put(userId, packageAppOpModes);
            }

            ArrayMap<String, SparseIntArray> legacyPackagesModes =
                    legacyPackageAppOpModes.valueAt(userIndex);

            int packagesSize = legacyPackagesModes.size();
            for (int packageIndex = 0; packageIndex < packagesSize; packageIndex++) {
                String packageName = legacyPackagesModes.keyAt(packageIndex);
                SparseIntArray modes = legacyPackagesModes.valueAt(packageIndex);
                packageAppOpModes.put(packageName, getAppOpModesForOpName(modes));
            }
        }
        return userPackageAppOpModes;
    }

    /**
     * Converts the map from op code -> mode to op name -> mode.
     */
    private Map<String, Integer> getAppOpModesForOpName(SparseIntArray appOpCodeModes) {
        int modesSize = appOpCodeModes.size();
        Map<String, Integer> appOpNameModes = new ArrayMap<>(modesSize);

        for (int modeIndex = 0; modeIndex < modesSize; modeIndex++) {
            int opCode = appOpCodeModes.keyAt(modeIndex);
            int opMode = appOpCodeModes.valueAt(modeIndex);
            appOpNameModes.put(AppOpsManager.opToPublicName(opCode), opMode);
        }
        return appOpNameModes;
    }

    @Override
    public int getLegacyAppOpVersion() {
        synchronized (mLock) {
            if (mAppIdAppOpModes == null || mPackageAppOpModes == null) {
                readLegacyAppOpState();
            }
        }
        return mVersionAtBoot;
    }

    @Override
    public boolean hasLegacyAppOpState() {
        return getLegacyAppOpVersion() > -1;
    }
}
