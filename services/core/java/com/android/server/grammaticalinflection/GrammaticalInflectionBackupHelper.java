/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.server.grammaticalinflection;

import android.app.backup.BackupManager;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.os.UserHandle;
import android.util.Log;
import android.util.SparseArray;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.time.Clock;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

public class GrammaticalInflectionBackupHelper {
    private static final String TAG = GrammaticalInflectionBackupHelper.class.getSimpleName();
    private static final String SYSTEM_BACKUP_PACKAGE_KEY = "android";
    // Stage data would be deleted on reboot since it's stored in memory. So it's retained until
    // retention period OR next reboot, whichever happens earlier.
    private static final Duration STAGE_DATA_RETENTION_PERIOD = Duration.ofDays(3);

    private final SparseArray<StagedData> mCache = new SparseArray<>();
    private final Object mCacheLock = new Object();
    private final PackageManager mPackageManager;
    private final GrammaticalInflectionService mGrammaticalGenderService;
    private final Clock mClock;

    static class StagedData {
        final long mCreationTimeMillis;
        final HashMap<String, Integer> mPackageStates;

        StagedData(long creationTimeMillis) {
            mCreationTimeMillis = creationTimeMillis;
            mPackageStates = new HashMap<>();
        }
    }

    public GrammaticalInflectionBackupHelper(GrammaticalInflectionService grammaticalGenderService,
            PackageManager packageManager) {
        mGrammaticalGenderService = grammaticalGenderService;
        mPackageManager = packageManager;
        mClock = Clock.systemUTC();
    }

    public byte[] getBackupPayload(int userId) {
        synchronized (mCacheLock) {
            cleanStagedDataForOldEntries();
        }

        HashMap<String, Integer> pkgGenderInfo = new HashMap<>();
        for (ApplicationInfo appInfo : mPackageManager.getInstalledApplicationsAsUser(
                PackageManager.ApplicationInfoFlags.of(0), userId)) {
            int gender = mGrammaticalGenderService.getApplicationGrammaticalGender(
                    appInfo.packageName, userId);
            if (gender != Configuration.GRAMMATICAL_GENDER_NOT_SPECIFIED) {
                pkgGenderInfo.put(appInfo.packageName, gender);
            }
        }

        if (!pkgGenderInfo.isEmpty()) {
            return convertToByteArray(pkgGenderInfo);
        } else {
            return null;
        }
    }

    public void stageAndApplyRestoredPayload(byte[] payload, int userId) {
        synchronized (mCacheLock) {
            cleanStagedDataForOldEntries();

            HashMap<String, Integer> pkgInfo = readFromByteArray(payload);
            if (pkgInfo.isEmpty()) {
                return;
            }

            StagedData stagedData = new StagedData(mClock.millis());
            for (Map.Entry<String, Integer> info : pkgInfo.entrySet()) {
                // If app installed, restore immediately, otherwise put it in cache.
                if (isPackageInstalledForUser(info.getKey(), userId)) {
                    if (!hasSetBeforeRestoring(info.getKey(), userId)) {
                        mGrammaticalGenderService.setRequestedApplicationGrammaticalGender(
                                info.getKey(), userId, info.getValue());
                    }
                } else {
                    if (info.getValue() != Configuration.GRAMMATICAL_GENDER_NOT_SPECIFIED) {
                        stagedData.mPackageStates.put(info.getKey(), info.getValue());
                    }
                }
            }

            mCache.append(userId, stagedData);
        }
    }

    private boolean hasSetBeforeRestoring(String pkgName, int userId) {
        return mGrammaticalGenderService.getApplicationGrammaticalGender(pkgName, userId)
                != Configuration.GRAMMATICAL_GENDER_NOT_SPECIFIED;
    }

    public void onPackageAdded(String packageName, int uid) {
        synchronized (mCacheLock) {
            int userId = UserHandle.getUserId(uid);
            StagedData cache = mCache.get(userId);
            if (cache != null && cache.mPackageStates.containsKey(packageName)) {
                int grammaticalGender = cache.mPackageStates.get(packageName);
                if (grammaticalGender != Configuration.GRAMMATICAL_GENDER_NOT_SPECIFIED) {
                    mGrammaticalGenderService.setRequestedApplicationGrammaticalGender(
                            packageName, userId, grammaticalGender);
                }
            }
        }
    }

    public void onPackageDataCleared() {
        notifyBackupManager();
    }

    public void onPackageRemoved() {
        notifyBackupManager();
    }

    public static void notifyBackupManager() {
        BackupManager.dataChanged(SYSTEM_BACKUP_PACKAGE_KEY);
    }

    private byte[] convertToByteArray(HashMap<String, Integer> pkgGenderInfo) {
        try (final ByteArrayOutputStream out = new ByteArrayOutputStream();
             final ObjectOutputStream objStream = new ObjectOutputStream(out)) {
            objStream.writeObject(pkgGenderInfo);
            return out.toByteArray();
        } catch (IOException e) {
            Log.e(TAG, "cannot convert payload to byte array.", e);
            return null;
        }
    }

    private HashMap<String, Integer> readFromByteArray(byte[] payload) {
        HashMap<String, Integer> data = new HashMap<>();

        try (ByteArrayInputStream byteIn = new ByteArrayInputStream(payload);
             ObjectInputStream in = new ObjectInputStream(byteIn)) {
            data = (HashMap<String, Integer>) in.readObject();
        } catch (IOException | ClassNotFoundException e) {
            Log.e(TAG, "cannot convert payload to HashMap.", e);
            e.printStackTrace();
        }
        return data;
    }

    private void cleanStagedDataForOldEntries() {
        for (int i = 0; i < mCache.size(); i++) {
            int userId = mCache.keyAt(i);
            StagedData stagedData = mCache.get(userId);
            if (stagedData.mCreationTimeMillis
                    < mClock.millis() - STAGE_DATA_RETENTION_PERIOD.toMillis()) {
                mCache.remove(userId);
            }
        }
    }

    private boolean isPackageInstalledForUser(String packageName, int userId) {
        PackageInfo pkgInfo = null;
        try {
            pkgInfo = mPackageManager.getPackageInfoAsUser(packageName, /* flags= */ 0, userId);
        } catch (PackageManager.NameNotFoundException e) {
            // The package is not installed
        }
        return pkgInfo != null;
    }
}
