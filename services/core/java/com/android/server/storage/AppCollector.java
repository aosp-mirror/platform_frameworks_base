/*
 * Copyright (C) 2016 The Android Open Source Project
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

package com.android.server.storage;

import android.annotation.NonNull;
import android.app.usage.StorageStats;
import android.app.usage.StorageStatsManager;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.pm.PackageStats;
import android.content.pm.UserInfo;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.UserManager;
import android.os.storage.VolumeInfo;
import android.util.Log;

import com.android.internal.os.BackgroundThread;
import com.android.internal.util.Preconditions;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * AppCollector asynchronously collects package sizes.
 */
public class AppCollector {
    private static String TAG = "AppCollector";

    private CompletableFuture<List<PackageStats>> mStats;
    private final BackgroundHandler mBackgroundHandler;

    /**
     * Constrcuts a new AppCollector which runs on the provided volume.
     * @param context Android context used to get
     * @param volume Volume to check for apps.
     */
    public AppCollector(Context context, @NonNull VolumeInfo volume) {
        Objects.requireNonNull(volume);

        mBackgroundHandler = new BackgroundHandler(BackgroundThread.get().getLooper(),
                volume,
                context.getPackageManager(),
                (UserManager) context.getSystemService(Context.USER_SERVICE),
                (StorageStatsManager) context.getSystemService(Context.STORAGE_STATS_SERVICE));
    }

    /**
     * Returns a list of package stats for the context and volume. Note that in a multi-user
     * environment, this may return stats for the same package multiple times. These "duplicate"
     * entries will have the package stats for the package for a given user, not the package in
     * aggregate.
     * @param timeoutMillis Milliseconds before timing out and returning early with null.
     */
    public List<PackageStats> getPackageStats(long timeoutMillis) {
        synchronized(this) {
            if (mStats == null) {
                mStats = new CompletableFuture<>();
                mBackgroundHandler.sendEmptyMessage(BackgroundHandler.MSG_START_LOADING_SIZES);
            }
        }

        List<PackageStats> value = null;
        try {
            value = mStats.get(timeoutMillis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException | ExecutionException e) {
            Log.e(TAG, "An exception occurred while getting app storage", e);
        } catch (TimeoutException e) {
            Log.e(TAG, "AppCollector timed out");
        }
        return value;
    }

    private class BackgroundHandler extends Handler {
        static final int MSG_START_LOADING_SIZES = 0;
        private final VolumeInfo mVolume;
        private final PackageManager mPm;
        private final UserManager mUm;
        private final StorageStatsManager mStorageStatsManager;

        BackgroundHandler(Looper looper, @NonNull VolumeInfo volume,
                PackageManager pm, UserManager um, StorageStatsManager storageStatsManager) {
            super(looper);
            mVolume = volume;
            mPm = pm;
            mUm = um;
            mStorageStatsManager = storageStatsManager;
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_START_LOADING_SIZES: {
                    List<PackageStats> stats = new ArrayList<>();
                    List<UserInfo> users = mUm.getUsers();
                    for (int userCount = 0, userSize = users.size();
                            userCount < userSize; userCount++) {
                        UserInfo user = users.get(userCount);
                        final List<ApplicationInfo> apps = mPm.getInstalledApplicationsAsUser(
                                PackageManager.MATCH_DISABLED_COMPONENTS, user.id);

                        for (int appCount = 0, size = apps.size(); appCount < size; appCount++) {
                            ApplicationInfo app = apps.get(appCount);
                            if (!Objects.equals(app.volumeUuid, mVolume.getFsUuid())) {
                                continue;
                            }

                            try {
                                StorageStats storageStats =
                                        mStorageStatsManager.queryStatsForPackage(app.storageUuid,
                                                app.packageName, user.getUserHandle());
                                PackageStats packageStats = new PackageStats(app.packageName,
                                        user.id);
                                packageStats.cacheSize = storageStats.getCacheBytes();
                                packageStats.codeSize = storageStats.getAppBytes();
                                packageStats.dataSize = storageStats.getDataBytes();
                                stats.add(packageStats);
                            } catch (NameNotFoundException | IOException e) {
                                Log.e(TAG, "An exception occurred while fetching app size", e);
                            }
                        }
                    }

                    mStats.complete(stats);
                }
            }
        }
    }
}
