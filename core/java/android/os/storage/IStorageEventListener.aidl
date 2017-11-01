/**
 * Copyright (c) 2016, The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.os.storage;

import android.os.storage.DiskInfo;
import android.os.storage.VolumeInfo;
import android.os.storage.VolumeRecord;

/**
 * Callback class for receiving events from StorageManagerService.
 *
 * Don't change the existing transaction Ids as they could be used in the native code.
 * When adding a new method, assign the next available transaction id.
 *
 * @hide - Applications should use {@link android.os.storage.StorageEventListener} class for
 *         storage event callbacks.
 */
oneway interface IStorageEventListener {
    /**
     * Detection state of USB Mass Storage has changed
     *
     * @param available true if a UMS host is connected.
     */
    void onUsbMassStorageConnectionChanged(boolean connected) = 0;

    /**
     * Storage state has changed.
     *
     * @param path The volume mount path.
     * @param oldState The old state of the volume.
     * @param newState The new state of the volume. Note: State is one of the
     *            values returned by Environment.getExternalStorageState()
     */
    void onStorageStateChanged(in String path, in String oldState, in String newState) = 1;

    void onVolumeStateChanged(in VolumeInfo vol, int oldState, int newState) = 2;

    void onVolumeRecordChanged(in VolumeRecord rec) = 3;

    void onVolumeForgotten(in String fsUuid) = 4;

    void onDiskScanned(in DiskInfo disk, int volumeCount) = 5;

    void onDiskDestroyed(in DiskInfo disk) = 6;

    /**
     * Don't change the existing transaction Ids as they could be used in the native code.
     * When adding a new method, assign the next available transaction id.
     */
}