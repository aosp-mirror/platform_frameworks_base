/*
 * Copyright (C) 2008 The Android Open Source Project
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

package android.os.storage;

import android.annotation.UnsupportedAppUsage;

/**
 * Used for receiving notifications from the StorageManager
 * 
 * @hide
 */
public class StorageEventListener {
    /**
     * Called when the detection state of a USB Mass Storage host has changed.
     * @param connected true if the USB mass storage is connected.
     */
    @UnsupportedAppUsage
    public void onUsbMassStorageConnectionChanged(boolean connected) {
    }

    /**
     * Called when storage has changed state
     * @param path the filesystem path for the storage
     * @param oldState the old state as returned by {@link android.os.Environment#getExternalStorageState()}.
     * @param newState the old state as returned by {@link android.os.Environment#getExternalStorageState()}.
     */
    @UnsupportedAppUsage
    public void onStorageStateChanged(String path, String oldState, String newState) {
    }

    @UnsupportedAppUsage
    public void onVolumeStateChanged(VolumeInfo vol, int oldState, int newState) {
    }

    @UnsupportedAppUsage
    public void onVolumeRecordChanged(VolumeRecord rec) {
    }

    @UnsupportedAppUsage
    public void onVolumeForgotten(String fsUuid) {
    }

    @UnsupportedAppUsage
    public void onDiskScanned(DiskInfo disk, int volumeCount) {
    }

    @UnsupportedAppUsage
    public void onDiskDestroyed(DiskInfo disk) {
    }
}
