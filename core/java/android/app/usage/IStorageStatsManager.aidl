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
 * limitations under the License.
 */

package android.app.usage;

import android.app.usage.StorageStats;
import android.app.usage.ExternalStorageStats;
import android.content.pm.ParceledListSlice;
import android.os.storage.CrateInfo;

/** {@hide} */
interface IStorageStatsManager {
    boolean isQuotaSupported(String volumeUuid, String callingPackage);
    boolean isReservedSupported(String volumeUuid, String callingPackage);
    long getTotalBytes(String volumeUuid, String callingPackage);
    long getFreeBytes(String volumeUuid, String callingPackage);
    long getCacheBytes(String volumeUuid, String callingPackage);
    long getCacheQuotaBytes(String volumeUuid, int uid, String callingPackage);
    StorageStats queryStatsForPackage(String volumeUuid, String packageName, int userId, String callingPackage);
    StorageStats queryStatsForUid(String volumeUuid, int uid, String callingPackage);
    StorageStats queryStatsForUser(String volumeUuid, int userId, String callingPackage);
    ExternalStorageStats queryExternalStatsForUser(String volumeUuid, int userId, String callingPackage);
    ParceledListSlice /* CrateInfo */ queryCratesForPackage(String volumeUuid, String packageName,
            int userId, String callingPackage);
    ParceledListSlice /* CrateInfo */ queryCratesForUid(String volumeUuid, int uid,
            String callingPackage);
    ParceledListSlice /* CrateInfo */ queryCratesForUser(String volumeUuid, int userId,
            String callingPackage);
}
