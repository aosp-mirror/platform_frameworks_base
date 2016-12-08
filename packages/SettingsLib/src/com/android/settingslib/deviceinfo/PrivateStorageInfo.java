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

package com.android.settingslib.deviceinfo;

import android.os.storage.StorageManager;
import android.os.storage.VolumeInfo;
import android.util.Log;

import java.io.File;
import java.util.Objects;

/**
 * PrivateStorageInfo provides information about the total and free storage on the device.
 */
public class PrivateStorageInfo {
    private static final String TAG = "PrivateStorageInfo";
    public final long freeBytes;
    public final long totalBytes;

    private PrivateStorageInfo(long freeBytes, long totalBytes) {
        this.freeBytes = freeBytes;
        this.totalBytes = totalBytes;
    }

    public static PrivateStorageInfo getPrivateStorageInfo(StorageVolumeProvider sm) {
        long totalInternalStorage = sm.getPrimaryStorageSize();
        long privateFreeBytes = 0;
        long privateTotalBytes = 0;
        for (VolumeInfo info : sm.getVolumes()) {
            final File path = info.getPath();
            if (info.getType() != VolumeInfo.TYPE_PRIVATE || path == null) {
                continue;
            }
            privateTotalBytes += getTotalSize(info, totalInternalStorage);
            privateFreeBytes += path.getFreeSpace();
        }
        return new PrivateStorageInfo(privateFreeBytes, privateTotalBytes);
    }

    /**
     * Returns the total size in bytes for a given volume info.
     * @param info Info of the volume to check.
     * @param totalInternalStorage Total number of bytes in the internal storage to use if the
     *                             volume is the internal disk.
     */
    public static long getTotalSize(VolumeInfo info, long totalInternalStorage) {
        // Device could have more than one primary storage, which could be located in the
        // internal flash (UUID_PRIVATE_INTERNAL) or in an external disk.
        // If it's internal, try to get its total size from StorageManager first
        // (totalInternalStorage), because that size is more precise because it accounts for
        // the system partition.
        if (info.getType() == VolumeInfo.TYPE_PRIVATE
                && Objects.equals(info.getFsUuid(), StorageManager.UUID_PRIVATE_INTERNAL)
                && totalInternalStorage > 0) {
            return totalInternalStorage;
        } else {
            final File path = info.getPath();
            if (path == null) {
                // Should not happen, caller should have checked.
                Log.e(TAG, "info's path is null on getTotalSize(): " + info);
                return 0;
            }
            return path.getTotalSpace();
        }
    }

}
