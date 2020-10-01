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
import android.annotation.WorkerThread;
import android.os.Environment;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.Slog;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.Consumer;

/** The data associated with a user profile. */
class UserData {

    private static final String TAG = UserData.class.getSimpleName();

    private static final int CONVERSATIONS_END_TOKEN = -1;

    private final @UserIdInt int mUserId;

    private final File mPerUserPeopleDataDir;

    private final ScheduledExecutorService mScheduledExecutorService;

    private boolean mIsUnlocked;

    private Map<String, PackageData> mPackageDataMap = new ArrayMap<>();

    @Nullable
    private String mDefaultDialer;

    @Nullable
    private String mDefaultSmsApp;

    UserData(@UserIdInt int userId, @NonNull ScheduledExecutorService scheduledExecutorService) {
        mUserId = userId;
        mPerUserPeopleDataDir = new File(Environment.getDataSystemCeDirectory(mUserId), "people");
        mScheduledExecutorService = scheduledExecutorService;
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
    }

    void setUserStopped() {
        mIsUnlocked = false;
    }

    boolean isUnlocked() {
        return mIsUnlocked;
    }

    @WorkerThread
    void loadUserData() {
        mPerUserPeopleDataDir.mkdir();
        Map<String, PackageData> packageDataMap = PackageData.packagesDataFromDisk(
                mUserId, this::isDefaultDialer, this::isDefaultSmsApp, mScheduledExecutorService,
                mPerUserPeopleDataDir);
        mPackageDataMap.putAll(packageDataMap);
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

    /** Deletes the specified package data. */
    void deletePackageData(@NonNull String packageName) {
        PackageData packageData = mPackageDataMap.remove(packageName);
        if (packageData != null) {
            packageData.onDestroy();
        }
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

    @Nullable
    byte[] getBackupPayload() {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(baos);
        for (PackageData packageData : mPackageDataMap.values()) {
            try {
                byte[] conversationsBackupPayload =
                        packageData.getConversationStore().getBackupPayload();
                out.writeInt(conversationsBackupPayload.length);
                out.write(conversationsBackupPayload);
                out.writeUTF(packageData.getPackageName());
            } catch (IOException e) {
                Slog.e(TAG, "Failed to write conversations to backup payload.", e);
                return null;
            }
        }
        try {
            out.writeInt(CONVERSATIONS_END_TOKEN);
        } catch (IOException e) {
            Slog.e(TAG, "Failed to write conversations end token to backup payload.", e);
            return null;
        }
        return baos.toByteArray();
    }

    void restore(@NonNull byte[] payload) {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(payload));
        try {
            for (int conversationsPayloadSize = in.readInt();
                    conversationsPayloadSize != CONVERSATIONS_END_TOKEN;
                    conversationsPayloadSize = in.readInt()) {
                byte[] conversationsPayload = new byte[conversationsPayloadSize];
                in.readFully(conversationsPayload, 0, conversationsPayloadSize);
                String packageName = in.readUTF();
                getOrCreatePackageData(packageName).getConversationStore().restore(
                        conversationsPayload);
            }
        } catch (IOException e) {
            Slog.e(TAG, "Failed to restore conversations from backup payload.", e);
        }
    }

    private PackageData createPackageData(String packageName) {
        return new PackageData(packageName, mUserId, this::isDefaultDialer, this::isDefaultSmsApp,
                mScheduledExecutorService, mPerUserPeopleDataDir);
    }

    private boolean isDefaultDialer(String packageName) {
        return TextUtils.equals(mDefaultDialer, packageName);
    }

    private boolean isDefaultSmsApp(String packageName) {
        return TextUtils.equals(mDefaultSmsApp, packageName);
    }
}
