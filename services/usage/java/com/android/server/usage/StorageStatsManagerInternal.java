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
package com.android.server.usage;

import android.annotation.NonNull;
import android.annotation.UserIdInt;
import android.content.pm.PackageStats;

/**
 * StorageStatsManager local system service interface.
 *
 * Only for use within the system server.
 */
public abstract class StorageStatsManagerInternal {
    /**
     * Class used to augment {@link PackageStats} with the data stored by the system on
     * behalf of apps in system specific directories
     * ({@link android.os.Environment#getDataSystemDirectory},
     * {@link android.os.Environment#getDataSystemCeDirectory}, etc).
     */
    public interface StorageStatsAugmenter {
        void augmentStatsForPackage(@NonNull PackageStats stats,
                @NonNull String packageName, @UserIdInt int userId,
                boolean callerHasStatsPermission);
        void augmentStatsForUid(@NonNull PackageStats stats, int uid,
                boolean callerHasStatsPermission);
    }

    /**
     * Register a {@link StorageStatsAugmenter}.
     *
     * @param augmenter the {@link StorageStatsAugmenter} object to be registered.
     * @param tag the identifier to be used for debugging in logs/trace.
     */
    public abstract void registerStorageStatsAugmenter(@NonNull StorageStatsAugmenter augmenter,
            @NonNull String tag);
}
