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

package android.os.storage;

import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.Log;

/**
 * A class representing a storage volume
 * @hide
 */
public class StorageVolume implements Parcelable {

    private static final String TAG = "StorageVolume";

    private final String mPath;
    private final String mDescription;
    private final boolean mRemovable;
    private final boolean mEmulated;
    private final int mMtpReserveSpace;
    private int mStorageId;

    public StorageVolume(String path, String description,
            boolean removable, boolean emulated, int mtpReserveSpace) {
        mPath = path;
        mDescription = description;
        mRemovable = removable;
        mEmulated = emulated;
        mMtpReserveSpace = mtpReserveSpace;
    }

    // for parcelling only
    private StorageVolume(String path, String description,
            boolean removable, boolean emulated, int mtpReserveSpace, int storageId) {
        mPath = path;
        mDescription = description;
        mRemovable = removable;
        mEmulated = emulated;
        mMtpReserveSpace = mtpReserveSpace;
        mStorageId = storageId;
    }

    /**
     * Returns the mount path for the volume.
     *
     * @return the mount path
     */
    public String getPath() {
        return mPath;
    }

    /**
     * Returns a user visible description of the volume.
     *
     * @return the volume description
     */
    public String getDescription() {
        return mDescription;
    }

    /**
     * Returns true if the volume is removable.
     *
     * @return is removable
     */
    public boolean isRemovable() {
        return mRemovable;
    }

    /**
     * Returns true if the volume is emulated.
     *
     * @return is removable
     */
    public boolean isEmulated() {
        return mEmulated;
    }

    /**
     * Returns the MTP storage ID for the volume.
     * this is also used for the storage_id column in the media provider.
     *
     * @return MTP storage ID
     */
    public int getStorageId() {
        return mStorageId;
    }

    /**
     * Do not call this unless you are MountService
     */
    public void setStorageId(int index) {
        // storage ID is 0x00010001 for primary storage,
        // then 0x00020001, 0x00030001, etc. for secondary storages
        mStorageId = ((index + 1) << 16) + 1;
    }

    /**
     * Number of megabytes of space to leave unallocated by MTP.
     * MTP will subtract this value from the free space it reports back
     * to the host via GetStorageInfo, and will not allow new files to
     * be added via MTP if there is less than this amount left free in the storage.
     * If MTP has dedicated storage this value should be zero, but if MTP is
     * sharing storage with the rest of the system, set this to a positive value
     * to ensure that MTP activity does not result in the storage being
     * too close to full.
     *
     * @return MTP reserve space
     */
    public int getMtpReserveSpace() {
        return mMtpReserveSpace;
    }

    @Override
    public boolean equals(Object obj) {
        if (obj instanceof StorageVolume && mPath != null) {
            StorageVolume volume = (StorageVolume)obj;
            return (mPath.equals(volume.mPath));
        }
        return false;
    }

    @Override
    public int hashCode() {
        return mPath.hashCode();
    }

    @Override
    public String toString() {
        return mPath;
    }

    public static final Parcelable.Creator<StorageVolume> CREATOR =
        new Parcelable.Creator<StorageVolume>() {
        public StorageVolume createFromParcel(Parcel in) {
            String path = in.readString();
            String description = in.readString();
            int removable = in.readInt();
            int emulated = in.readInt();
            int storageId = in.readInt();
            int mtpReserveSpace = in.readInt();
            return new StorageVolume(path, description,
                    removable == 1, emulated == 1,
                    mtpReserveSpace, storageId);
        }

        public StorageVolume[] newArray(int size) {
            return new StorageVolume[size];
        }
    };

    public int describeContents() {
        return 0;
    }

    public void writeToParcel(Parcel parcel, int flags) {
        parcel.writeString(mPath);
        parcel.writeString(mDescription);
        parcel.writeInt(mRemovable ? 1 : 0);
        parcel.writeInt(mEmulated ? 1 : 0);
        parcel.writeInt(mStorageId);
        parcel.writeInt(mMtpReserveSpace);
    }
}
