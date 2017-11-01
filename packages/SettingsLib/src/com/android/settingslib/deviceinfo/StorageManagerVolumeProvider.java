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

import android.app.usage.StorageStatsManager;
import android.os.storage.StorageManager;
import android.os.storage.VolumeInfo;

import java.io.IOException;
import java.util.List;

/**
 * StorageManagerVolumeProvider is a thin wrapper around the StorageManager to provide insight into
 * the storage volumes on a device.
 */
public class StorageManagerVolumeProvider implements StorageVolumeProvider {
    private StorageManager mStorageManager;

    public StorageManagerVolumeProvider(StorageManager sm) {
        mStorageManager = sm;
    }

    @Override
    public long getPrimaryStorageSize() {
        return mStorageManager.getPrimaryStorageSize();
    }

    @Override
    public List<VolumeInfo> getVolumes() {
        return mStorageManager.getVolumes();
    }

    @Override
    public VolumeInfo findEmulatedForPrivate(VolumeInfo privateVolume) {
        return mStorageManager.findEmulatedForPrivate(privateVolume);
    }

    @Override
    public long getTotalBytes(StorageStatsManager stats, VolumeInfo volume) throws IOException {
        return stats.getTotalBytes(volume.getFsUuid());
    }

    @Override
    public long getFreeBytes(StorageStatsManager stats, VolumeInfo volume) throws IOException {
        return stats.getFreeBytes(volume.getFsUuid());
    }
}
