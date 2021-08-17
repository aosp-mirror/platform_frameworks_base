/*
 * Copyright (C) 2021 The Android Open Source Project
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
package com.android.server.usage;

import android.annotation.NonNull;
import android.annotation.SystemApi;
import android.content.pm.PackageStats;
import android.os.UserHandle;

/**
 * StorageStatsManager local system service interface.
 *
 * @hide Only for use within the system server.
 */
@SystemApi(client = SystemApi.Client.SYSTEM_SERVER)
public interface StorageStatsManagerLocal {
    /**
     * Class used to augment {@link PackageStats} with the data stored by the system on
     * behalf of apps in system specific directories
     * ({@link android.os.Environment#getDataSystemDirectory},
     * {@link android.os.Environment#getDataSystemCeDirectory}, etc).
     */
    interface StorageStatsAugmenter {
        /**
         * Augments {@link PackageStats} with data stored by the system for the given package.
         *
         * @param stats                   Structure to modify with usage data
         * @param packageName             Package name of the app whose data is stored by the
         *                                system and needs to be added to {@code stats}.
         * @param userHandle              Device user for which usage stats are being requested.
         * @param canCallerAccessAllStats Whether the caller who is requesting the storage stats
         *                                can query stats for packages other than itself. For
         *                                example, holding the PACKAGE_USAGE_STATS permission is one
         *                                way to accomplish this.
         */
        void augmentStatsForPackageForUser(
                @NonNull PackageStats stats,
                @NonNull String packageName,
                @NonNull UserHandle userHandle,
                boolean canCallerAccessAllStats);

        /**
         * Augments {@link PackageStats} with data stored by the system for the given uid.
         *
         * @param stats                   Structure to modify with usage data
         * @param uid                     Unique app ID for the app instance whose stats are being
         *                                requested.
         * @param canCallerAccessAllStats Whether the caller who is requesting the storage stats
         *                                can query stats for packages other than itself. For
         *                                example, holding the PACKAGE_USAGE_STATS permission is one
         *                                way to accomplish this.
         */
        void augmentStatsForUid(
                @NonNull PackageStats stats, int uid, boolean canCallerAccessAllStats);

        /**
         * Augments {@link PackageStats} with data stored by the system for the given device user.
         *
         * @param stats      Structure to modify with usage data
         * @param userHandle Device user whose data is stored by the system and needs to be added to
         *                   {@code stats}.
         */
        void augmentStatsForUser(@NonNull PackageStats stats, @NonNull UserHandle userHandle);
    }

    /**
     * Register a {@link StorageStatsAugmenter}.
     *
     * @param augmenter the {@link StorageStatsAugmenter} object to be registered.
     * @param tag       the identifier to be used for debugging in logs/trace.
     */
    void registerStorageStatsAugmenter(
            @NonNull StorageStatsAugmenter augmenter, @NonNull String tag);
}
