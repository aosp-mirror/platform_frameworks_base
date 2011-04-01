/*
 * Copyright (C) 2010 The Android Open Source Project
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

package android.mtp;

/**
 * This class encapsulates information about a storage unit on an MTP device.
 * This corresponds to the StorageInfo Dataset described in
 * section 5.2.2 of the MTP specification.
 */
public final class MtpStorageInfo {

    private int mStorageId;
    private long mMaxCapacity;
    private long mFreeSpace;
    private String mDescription;
    private String mVolumeIdentifier;

    // only instantiated via JNI
    private MtpStorageInfo() {
    }

    /**
     * Returns the storage ID for the storage unit.
     * The storage ID uniquely identifies the storage unit on the MTP device.
     *
     * @return the storage ID
     */
    public final int getStorageId() {
        return mStorageId;
    }

    /**
     * Returns the maximum storage capacity for the storage unit in bytes
     *
     * @return the maximum capacity
     */
    public final long getMaxCapacity() {
        return mMaxCapacity;
    }

   /**
     * Returns the amount of free space in the storage unit in bytes
     *
     * @return the amount of free space
     */
    public final long getFreeSpace() {
        return mFreeSpace;
    }

   /**
     * Returns the description string for the storage unit.
     * This is typically displayed to the user in the user interface on the
     * MTP host.
     *
     * @return the storage unit description
     */
    public final String getDescription() {
        return mDescription;
    }

   /**
     * Returns the volume identifier for the storage unit
     *
     * @return the storage volume identifier
     */
    public final String getVolumeIdentifier() {
        return mVolumeIdentifier;
    }
}
