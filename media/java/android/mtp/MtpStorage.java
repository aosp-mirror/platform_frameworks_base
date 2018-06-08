/*
 * Copyright (C) 2011 The Android Open Source Project
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

import android.os.storage.StorageVolume;

/**
 * This class represents a storage unit on an MTP device.
 * Used only for MTP support in USB responder mode.
 * MtpStorageInfo is used in MTP host mode
 *
 * @hide
 */
public class MtpStorage {

    private final int mStorageId;
    private final String mPath;
    private final String mDescription;
    private final boolean mRemovable;
    private final long mMaxFileSize;

    public MtpStorage(StorageVolume volume, int storageId) {
        mStorageId = storageId;
        mPath = volume.getInternalPath();
        mDescription = volume.getDescription(null);
        mRemovable = volume.isRemovable();
        mMaxFileSize = volume.getMaxFileSize();
    }

    /**
     * Returns the storage ID for the storage unit
     *
     * @return the storage ID
     */
    public final int getStorageId() {
        return mStorageId;
    }

   /**
     * Returns the file path for the storage unit's storage in the file system
     *
     * @return the storage file path
     */
    public final String getPath() {
        return mPath;
    }

   /**
     * Returns the description string for the storage unit
     *
     * @return the storage unit description
     */
    public final String getDescription() {
        return mDescription;
    }

   /**
     * Returns true if the storage is removable.
     *
     * @return is removable
     */
    public final boolean isRemovable() {
        return mRemovable;
    }

   /**
     * Returns maximum file size for the storage, or zero if it is unbounded.
     *
     * @return maximum file size
     */
    public long getMaxFileSize() {
        return mMaxFileSize;
    }
}
