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
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.IPackageStatsObserver;
import android.content.pm.PackageManager;
import android.content.pm.PackageStats;
import android.content.pm.UserInfo;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import android.os.Process;
import android.os.RemoteException;
import android.os.UserManager;
import android.os.storage.VolumeInfo;
import android.util.Log;
import com.android.internal.os.BackgroundThread;
import com.android.internal.util.Preconditions;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

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
        Preconditions.checkNotNull(volume);

        mBackgroundHandler = new BackgroundHandler(BackgroundThread.get().getLooper(),
                volume,
                context.getPackageManager(),
                (UserManager) context.getSystemService(Context.USER_SERVICE));
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

    private class StatsObserver extends IPackageStatsObserver.Stub {
        private AtomicInteger mCount;
        private final ArrayList<PackageStats> mPackageStats;

        public StatsObserver(int count) {
            mCount = new AtomicInteger(count);
            mPackageStats = new ArrayList<>(count);
        }

        @Override
        public void onGetStatsCompleted(PackageStats packageStats, boolean succeeded)
                throws RemoteException {
            if (succeeded) {
                mPackageStats.add(packageStats);
            }

            if (mCount.decrementAndGet() == 0) {
                mStats.complete(mPackageStats);
            }
        }
    }

    private class BackgroundHandler extends Handler {
        static final int MSG_START_LOADING_SIZES = 0;
        private final VolumeInfo mVolume;
        private final PackageManager mPm;
        private final UserManager mUm;

        BackgroundHandler(Looper looper, @NonNull VolumeInfo volume, PackageManager pm, UserManager um) {
            super(looper);
            mVolume = volume;
            mPm = pm;
            mUm = um;
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_START_LOADING_SIZES: {
                    final List<ApplicationInfo> apps = mPm.getInstalledApplications(
                            PackageManager.GET_UNINSTALLED_PACKAGES
                                    | PackageManager.GET_DISABLED_COMPONENTS);

                    final List<ApplicationInfo> volumeApps = new ArrayList<>();
                    for (ApplicationInfo app : apps) {
                        if (Objects.equals(app.volumeUuid, mVolume.getFsUuid())) {
                            volumeApps.add(app);
                        }
                    }

                    List<UserInfo> users = mUm.getUsers();
                    final int count = users.size() * volumeApps.size();
                    if (count == 0) {
                        mStats.complete(new ArrayList<>());
                    }

                    // Kick off the async package size query for all apps.
                    final StatsObserver observer = new StatsObserver(count);
                    for (UserInfo user : users) {
                        for (ApplicationInfo app : volumeApps) {
                            mPm.getPackageSizeInfoAsUser(app.packageName, user.id,
                                    observer);
                        }
                    }
                }
            }
        }
    }
}
