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

package android.os;

/**
 * Used for receiving notifications from the StorageManager
 */
public interface StorageEventListener {
    /**
     * Called when the ability to share a volume has changed.
     * @param method the share-method which has changed.
     * @param available true if the share is available.
     */
    public void onShareAvailabilityChanged(String method, boolean available);

    /**
     * Called when media has been inserted
     * @param label the system defined label for the volume.
     * @param path the filesystem path for the volume.
     * @param major the major number of the device.
     * @param minor the minor number of the device.
     */
    public void onMediaInserted(String label, String path, int major, int minor);

    /**
     * Called when media has been removed
     * @param label the system defined label for the volume.
     * @param path the filesystem path for the volume.
     * @param major the major number of the device.
     * @param minor the minor number of the device.
     * @param clean the media was removed cleanly.
     */
    public void onMediaRemoved(String label, String path, int major, int minor, boolean clean);

    /**
     * Called when a volume has changed state
     * @param label the system defined label for the volume.
     * @param path the filesystem path for the volume.
     * @param oldState the old state as returned by {@link android.os.Environment.getExternalStorageState()}.
     * @param newState the old state as returned by {@link android.os.Environment.getExternalStorageState()}.
     */
    public void onVolumeStateChanged(String label, String path, String oldState, String newState);
}
