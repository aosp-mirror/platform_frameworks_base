/*
 * Copyright (C) 2011 The Android Open Source Project
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

package com.android.settingslib.deviceinfo;

import android.app.usage.ExternalStorageStats;
import android.app.usage.StorageStats;
import android.app.usage.StorageStatsManager;
import android.content.Context;
import android.content.pm.UserInfo;
import android.os.AsyncTask;
import android.os.Environment;
import android.os.SystemClock;
import android.os.UserHandle;
import android.os.UserManager;
import android.os.storage.StorageVolume;
import android.os.storage.VolumeInfo;
import android.util.Log;
import android.util.SparseArray;
import android.util.SparseLongArray;

import java.io.IOException;
import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.List;

/**
 * Utility for measuring the disk usage of internal storage or a physical
 * {@link StorageVolume}.
 */
public class StorageMeasurement {
    private static final String TAG = "StorageMeasurement";

    public static class MeasurementDetails {
        /** Size of storage device. */
        public long totalSize;
        /** Size of available space. */
        public long availSize;
        /** Size of all cached data. */
        public long cacheSize;

        /**
         * Total disk space used by everything.
         * <p>
         * Key is {@link UserHandle}.
         */
        public SparseLongArray usersSize = new SparseLongArray();

        /**
         * Total disk space used by apps.
         * <p>
         * Key is {@link UserHandle}.
         */
        public SparseLongArray appsSize = new SparseLongArray();

        /**
         * Total disk space used by media on shared storage.
         * <p>
         * First key is {@link UserHandle}. Second key is media type, such as
         * {@link Environment#DIRECTORY_PICTURES}.
         */
        public SparseArray<HashMap<String, Long>> mediaSize = new SparseArray<>();

        /**
         * Total disk space used by non-media on shared storage.
         * <p>
         * Key is {@link UserHandle}.
         */
        public SparseLongArray miscSize = new SparseLongArray();

        @Override
        public String toString() {
            return "MeasurementDetails: [totalSize: " + totalSize + " availSize: " + availSize
                    + " cacheSize: " + cacheSize + " mediaSize: " + mediaSize
                    + " miscSize: " + miscSize + "usersSize: " + usersSize + "]";
        }
    }

    public interface MeasurementReceiver {
        void onDetailsChanged(MeasurementDetails details);
    }

    private WeakReference<MeasurementReceiver> mReceiver;

    private final Context mContext;
    private final UserManager mUser;
    private final StorageStatsManager mStats;

    private final VolumeInfo mVolume;
    private final VolumeInfo mSharedVolume;

    public StorageMeasurement(Context context, VolumeInfo volume, VolumeInfo sharedVolume) {
        mContext = context.getApplicationContext();
        mUser = mContext.getSystemService(UserManager.class);
        mStats = mContext.getSystemService(StorageStatsManager.class);

        mVolume = volume;
        mSharedVolume = sharedVolume;
    }

    public void setReceiver(MeasurementReceiver receiver) {
        if (mReceiver == null || mReceiver.get() == null) {
            mReceiver = new WeakReference<MeasurementReceiver>(receiver);
        }
    }

    public void forceMeasure() {
        measure();
    }

    public void measure() {
        new MeasureTask().execute();
    }

    public void onDestroy() {
        mReceiver = null;
    }

    private class MeasureTask extends AsyncTask<Void, Void, MeasurementDetails> {
        @Override
        protected MeasurementDetails doInBackground(Void... params) {
            return measureExactStorage();
        }

        @Override
        protected void onPostExecute(MeasurementDetails result) {
            final MeasurementReceiver receiver = (mReceiver != null) ? mReceiver.get() : null;
            if (receiver != null) {
                receiver.onDetailsChanged(result);
            }
        }
    }

    private MeasurementDetails measureExactStorage() {
        final List<UserInfo> users = mUser.getUsers();

        final long start = SystemClock.elapsedRealtime();

        final MeasurementDetails details = new MeasurementDetails();
        if (mVolume == null) return details;

        if (mVolume.getType() == VolumeInfo.TYPE_PUBLIC
                || mVolume.getType() == VolumeInfo.TYPE_STUB) {
            details.totalSize = mVolume.getPath().getTotalSpace();
            details.availSize = mVolume.getPath().getUsableSpace();
            return details;
        }

        try {
            details.totalSize = mStats.getTotalBytes(mVolume.fsUuid);
            details.availSize = mStats.getFreeBytes(mVolume.fsUuid);
        } catch (IOException e) {
            // The storage volume became null while we were measuring it.
            Log.w(TAG, e);
            return details;
        }

        final long finishTotal = SystemClock.elapsedRealtime();
        Log.d(TAG, "Measured total storage in " + (finishTotal - start) + "ms");

        if (mSharedVolume != null && mSharedVolume.isMountedReadable()) {
            for (UserInfo user : users) {
                final HashMap<String, Long> mediaMap = new HashMap<>();
                details.mediaSize.put(user.id, mediaMap);

                final ExternalStorageStats stats;
                try {
                    stats = mStats.queryExternalStatsForUser(mSharedVolume.fsUuid,
                            UserHandle.of(user.id));
                } catch (IOException e) {
                    Log.w(TAG, e);
                    continue;
                }

                addValue(details.usersSize, user.id, stats.getTotalBytes());

                // Track detailed data types
                mediaMap.put(Environment.DIRECTORY_MUSIC, stats.getAudioBytes());
                mediaMap.put(Environment.DIRECTORY_MOVIES, stats.getVideoBytes());
                mediaMap.put(Environment.DIRECTORY_PICTURES, stats.getImageBytes());

                final long miscBytes = stats.getTotalBytes() - stats.getAudioBytes()
                        - stats.getVideoBytes() - stats.getImageBytes();
                addValue(details.miscSize, user.id, miscBytes);
            }
        }

        final long finishShared = SystemClock.elapsedRealtime();
        Log.d(TAG, "Measured shared storage in " + (finishShared - finishTotal) + "ms");

        if ((mVolume.getType() == VolumeInfo.TYPE_PRIVATE) && mVolume.isMountedReadable()) {
            for (UserInfo user : users) {
                final StorageStats stats;
                try {
                    stats = mStats.queryStatsForUser(mVolume.fsUuid, UserHandle.of(user.id));
                } catch (IOException e) {
                    Log.w(TAG, e);
                    continue;
                }

                // Only count code once against current user
                if (user.id == UserHandle.myUserId()) {
                    addValue(details.usersSize, user.id, stats.getAppBytes());
                }

                addValue(details.usersSize, user.id, stats.getDataBytes());
                addValue(details.appsSize, user.id, stats.getAppBytes() + stats.getDataBytes());

                details.cacheSize += stats.getCacheBytes();
            }
        }

        final long finishPrivate = SystemClock.elapsedRealtime();
        Log.d(TAG, "Measured private storage in " + (finishPrivate - finishShared) + "ms");

        return details;
    }

    private static void addValue(SparseLongArray array, int key, long value) {
        array.put(key, array.get(key) + value);
    }
}
