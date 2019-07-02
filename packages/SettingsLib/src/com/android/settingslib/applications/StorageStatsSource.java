/*
 * Copyright (C) 2017 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.settingslib.applications;

import android.app.usage.StorageStats;
import android.app.usage.StorageStatsManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.UserHandle;

import androidx.annotation.VisibleForTesting;

import java.io.IOException;

/**
 * StorageStatsSource wraps the StorageStatsManager for testability purposes.
 */
public class StorageStatsSource {
    private StorageStatsManager mStorageStatsManager;

    public StorageStatsSource(Context context) {
        mStorageStatsManager = context.getSystemService(StorageStatsManager.class);
    }

    public StorageStatsSource.ExternalStorageStats getExternalStorageStats(String volumeUuid,
            UserHandle user) throws IOException {
        return new StorageStatsSource.ExternalStorageStats(
                mStorageStatsManager.queryExternalStatsForUser(volumeUuid, user));
    }

    public StorageStatsSource.AppStorageStats getStatsForUid(String volumeUuid, int uid)
            throws IOException {
        return new StorageStatsSource.AppStorageStatsImpl(
                mStorageStatsManager.queryStatsForUid(volumeUuid, uid));
    }

    public StorageStatsSource.AppStorageStats getStatsForPackage(
            String volumeUuid, String packageName, UserHandle user)
            throws PackageManager.NameNotFoundException, IOException {
        return new StorageStatsSource.AppStorageStatsImpl(
                mStorageStatsManager.queryStatsForPackage(volumeUuid, packageName, user));
    }

    public long getCacheQuotaBytes(String volumeUuid, int uid) {
        return mStorageStatsManager.getCacheQuotaBytes(volumeUuid, uid);
    }

    /**
     * Static class that provides methods for querying the amount of external storage available as
     * well as breaking it up into several media types.
     */
    public static class ExternalStorageStats {
        public long totalBytes;
        public long audioBytes;
        public long videoBytes;
        public long imageBytes;
        public long appBytes;

        /** Convenience method for testing. */
        @VisibleForTesting
        public ExternalStorageStats(
                long totalBytes, long audioBytes, long videoBytes, long imageBytes, long appBytes) {
            this.totalBytes = totalBytes;
            this.audioBytes = audioBytes;
            this.videoBytes = videoBytes;
            this.imageBytes = imageBytes;
            this.appBytes = appBytes;
        }

        /**
         * Creates an ExternalStorageStats from the system version of ExternalStorageStats. They are
         * identical other than the utility method created for test purposes.
         * @param stats The stats to copy to wrap.
         */
        public ExternalStorageStats(android.app.usage.ExternalStorageStats stats) {
            totalBytes = stats.getTotalBytes();
            audioBytes = stats.getAudioBytes();
            videoBytes = stats.getVideoBytes();
            imageBytes = stats.getImageBytes();
            appBytes = stats.getAppBytes();
        }
    }

    /**
     * Interface that exists to simplify testing. The platform {@link StorageStats} is too new and
     * robolectric cannot see it. It simply wraps a StorageStats object and forwards method calls
     * to the real object
     */
    public interface AppStorageStats {
        long getCodeBytes();
        long getDataBytes();
        long getCacheBytes();
        long getTotalBytes();
    }

    /**
     * Simple implementation of AppStorageStats that will allow you to query the StorageStats object
     * passed in for storage information about an app.
     */
    public static class AppStorageStatsImpl implements
            StorageStatsSource.AppStorageStats {
        private StorageStats mStats;

        public AppStorageStatsImpl(StorageStats stats) {
            mStats = stats;
        }

        public long getCodeBytes() {
            return mStats.getCodeBytes();
        }

        public long getDataBytes() {
            return mStats.getDataBytes();
        }

        public long getCacheBytes() {
            return mStats.getCacheBytes();
        }

        public long getTotalBytes() {
            return mStats.getAppBytes() + mStats.getDataBytes();
        }
    }
}