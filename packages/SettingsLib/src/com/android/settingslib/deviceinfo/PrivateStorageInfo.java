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

import android.app.AppGlobals;
import android.app.usage.StorageStatsManager;
import android.content.Context;
import android.os.storage.VolumeInfo;
import android.util.Log;

import java.io.IOException;

/**
 * PrivateStorageInfo provides information about the total and free storage on the device.
 */
public class PrivateStorageInfo {
    private static final String TAG = "PrivateStorageInfo";

    public final long freeBytes;
    public final long totalBytes;

    public PrivateStorageInfo(long freeBytes, long totalBytes) {
        this.freeBytes = freeBytes;
        this.totalBytes = totalBytes;
    }

    public static PrivateStorageInfo getPrivateStorageInfo(StorageVolumeProvider sm) {
        final Context context = AppGlobals.getInitialApplication();
        final StorageStatsManager stats = context.getSystemService(StorageStatsManager.class);

        long privateFreeBytes = 0;
        long privateTotalBytes = 0;
        for (VolumeInfo info : sm.getVolumes()) {
            if (info.getType() == VolumeInfo.TYPE_PRIVATE && info.isMountedReadable()) {
                try {
                    privateTotalBytes += sm.getTotalBytes(stats, info);
                    privateFreeBytes += sm.getFreeBytes(stats, info);
                } catch (IOException e) {
                    Log.w(TAG, e);
                }
            }
        }
        return new PrivateStorageInfo(privateFreeBytes, privateTotalBytes);
    }

    public static long getTotalSize(VolumeInfo info, long totalInternalStorage) {
        final Context context = AppGlobals.getInitialApplication();
        final StorageStatsManager stats = context.getSystemService(StorageStatsManager.class);
        try {
            return stats.getTotalBytes(info.getFsUuid());
        } catch (IOException e) {
            Log.w(TAG, e);
            return 0;
        }
    }
}
