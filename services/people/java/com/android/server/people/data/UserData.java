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

package com.android.server.people.data;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.os.Environment;
import android.text.TextUtils;
import android.util.ArrayMap;

import java.io.File;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.Consumer;

/** The data associated with a user profile. */
class UserData {

    private final @UserIdInt int mUserId;

    private final File mPerUserPeopleDataDir;

    private final ScheduledExecutorService mScheduledExecutorService;

    private final ContactsQueryHelper mHelper;

    private boolean mIsUnlocked;

    private Map<String, PackageData> mPackageDataMap = new ArrayMap<>();

    @Nullable
    private String mDefaultDialer;

    @Nullable
    private String mDefaultSmsApp;

    UserData(@UserIdInt int userId, @NonNull ScheduledExecutorService scheduledExecutorService,
            ContactsQueryHelper helper) {
        mUserId = userId;
        mPerUserPeopleDataDir = new File(Environment.getDataSystemCeDirectory(mUserId), "people");
        mScheduledExecutorService = scheduledExecutorService;
        mHelper = helper;
    }

    @UserIdInt int getUserId() {
        return mUserId;
    }

    void forAllPackages(@NonNull Consumer<PackageData> consumer) {
        for (PackageData packageData : mPackageDataMap.values()) {
            consumer.accept(packageData);
        }
    }

    void setUserUnlocked() {
        mIsUnlocked = true;

        // Ensures per user root directory for people data is present, and attempt to load
        // data from disk.
        mPerUserPeopleDataDir.mkdirs();
        for (PackageData packageData : mPackageDataMap.values()) {
            packageData.loadFromDisk();
        }
    }

    void setUserStopped() {
        mIsUnlocked = false;
    }

    boolean isUnlocked() {
        return mIsUnlocked;
    }

    /**
     * Gets the {@link PackageData} for the specified {@code packageName} if exists; otherwise
     * creates a new instance and returns it.
     */
    @NonNull
    PackageData getOrCreatePackageData(String packageName) {
        return mPackageDataMap.computeIfAbsent(packageName, key -> createPackageData(packageName));
    }

    /**
     * Gets the {@link PackageData} for the specified {@code packageName} if exists; otherwise
     * returns {@code null}.
     */
    @Nullable
    PackageData getPackageData(@NonNull String packageName) {
        return mPackageDataMap.get(packageName);
    }

    void setDefaultDialer(@Nullable String packageName) {
        mDefaultDialer = packageName;
    }

    @Nullable
    PackageData getDefaultDialer() {
        return mDefaultDialer != null ? getPackageData(mDefaultDialer) : null;
    }

    void setDefaultSmsApp(@Nullable String packageName) {
        mDefaultSmsApp = packageName;
    }

    @Nullable
    PackageData getDefaultSmsApp() {
        return mDefaultSmsApp != null ? getPackageData(mDefaultSmsApp) : null;
    }

    private PackageData createPackageData(String packageName) {
        return new PackageData(packageName, mUserId, this::isDefaultDialer, this::isDefaultSmsApp,
                mScheduledExecutorService, mPerUserPeopleDataDir, mHelper);
    }

    private boolean isDefaultDialer(String packageName) {
        return TextUtils.equals(mDefaultDialer, packageName);
    }

    private boolean isDefaultSmsApp(String packageName) {
        return TextUtils.equals(mDefaultSmsApp, packageName);
    }
}
