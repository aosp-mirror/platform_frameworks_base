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

import android.content.Context;
import android.net.TrafficStats;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.UserHandle;

import com.android.internal.util.IndentingPrintWriter;
import com.android.internal.util.Preconditions;

import java.io.CharArrayWriter;
import java.io.File;

/**
 * Information about a storage volume that may be mounted. This is a legacy
 * specialization of {@link VolumeInfo} which describes the volume for a
 * specific user.
 * <p>
 * This class may be deprecated in the future.
 *
 * @hide
 */
public class StorageVolume implements Parcelable {

    private final String mId;
    private final int mStorageId;
    private final File mPath;
    private final String mDescription;
    private final boolean mPrimary;
    private final boolean mRemovable;
    private final boolean mEmulated;
    private final long mMtpReserveSize;
    private final boolean mAllowMassStorage;
    private final long mMaxFileSize;
    private final UserHandle mOwner;
    private final String mFsUuid;
    private final String mState;

    // StorageVolume extra for ACTION_MEDIA_REMOVED, ACTION_MEDIA_UNMOUNTED, ACTION_MEDIA_CHECKING,
    // ACTION_MEDIA_NOFS, ACTION_MEDIA_MOUNTED, ACTION_MEDIA_SHARED, ACTION_MEDIA_UNSHARED,
    // ACTION_MEDIA_BAD_REMOVAL, ACTION_MEDIA_UNMOUNTABLE and ACTION_MEDIA_EJECT broadcasts.
    public static final String EXTRA_STORAGE_VOLUME = "storage_volume";

    public static final int STORAGE_ID_INVALID = 0x00000000;
    public static final int STORAGE_ID_PRIMARY = 0x00010001;

    public StorageVolume(String id, int storageId, File path, String description, boolean primary,
            boolean removable, boolean emulated, long mtpReserveSize, boolean allowMassStorage,
            long maxFileSize, UserHandle owner, String fsUuid, String state) {
        mId = Preconditions.checkNotNull(id);
        mStorageId = storageId;
        mPath = Preconditions.checkNotNull(path);
        mDescription = Preconditions.checkNotNull(description);
        mPrimary = primary;
        mRemovable = removable;
        mEmulated = emulated;
        mMtpReserveSize = mtpReserveSize;
        mAllowMassStorage = allowMassStorage;
        mMaxFileSize = maxFileSize;
        mOwner = Preconditions.checkNotNull(owner);
        mFsUuid = fsUuid;
        mState = Preconditions.checkNotNull(state);
    }

    private StorageVolume(Parcel in) {
        mId = in.readString();
        mStorageId = in.readInt();
        mPath = new File(in.readString());
        mDescription = in.readString();
        mPrimary = in.readInt() != 0;
        mRemovable = in.readInt() != 0;
        mEmulated = in.readInt() != 0;
        mMtpReserveSize = in.readLong();
        mAllowMassStorage = in.readInt() != 0;
        mMaxFileSize = in.readLong();
        mOwner = in.readParcelable(null);
        mFsUuid = in.readString();
        mState = in.readString();
    }

    public String getId() {
        return mId;
    }

    /**
     * Returns the mount path for the volume.
     *
     * @return the mount path
     */
    public String getPath() {
        return mPath.toString();
    }

    public File getPathFile() {
        return mPath;
    }

    /**
     * Returns a user visible description of the volume.
     *
     * @return the volume description
     */
    public String getDescription(Context context) {
        return mDescription;
    }

    public boolean isPrimary() {
        return mPrimary;
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
        return (int) (mMtpReserveSize / TrafficStats.MB_IN_BYTES);
    }

    /**
     * Returns true if this volume can be shared via USB mass storage.
     *
     * @return whether mass storage is allowed
     */
    public boolean allowMassStorage() {
        return mAllowMassStorage;
    }

    /**
     * Returns maximum file size for the volume, or zero if it is unbounded.
     *
     * @return maximum file size
     */
    public long getMaxFileSize() {
        return mMaxFileSize;
    }

    public UserHandle getOwner() {
        return mOwner;
    }

    public String getUuid() {
        return mFsUuid;
    }

    /**
     * Parse and return volume UUID as FAT volume ID, or return -1 if unable to
     * parse or UUID is unknown.
     */
    public int getFatVolumeId() {
        if (mFsUuid == null || mFsUuid.length() != 9) {
            return -1;
        }
        try {
            return (int) Long.parseLong(mFsUuid.replace("-", ""), 16);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    public String getUserLabel() {
        return mDescription;
    }

    public String getState() {
        return mState;
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
        final CharArrayWriter writer = new CharArrayWriter();
        dump(new IndentingPrintWriter(writer, "    ", 80));
        return writer.toString();
    }

    public void dump(IndentingPrintWriter pw) {
        pw.println("StorageVolume:");
        pw.increaseIndent();
        pw.printPair("mId", mId);
        pw.printPair("mStorageId", mStorageId);
        pw.printPair("mPath", mPath);
        pw.printPair("mDescription", mDescription);
        pw.printPair("mPrimary", mPrimary);
        pw.printPair("mRemovable", mRemovable);
        pw.printPair("mEmulated", mEmulated);
        pw.printPair("mMtpReserveSize", mMtpReserveSize);
        pw.printPair("mAllowMassStorage", mAllowMassStorage);
        pw.printPair("mMaxFileSize", mMaxFileSize);
        pw.printPair("mOwner", mOwner);
        pw.printPair("mFsUuid", mFsUuid);
        pw.printPair("mState", mState);
        pw.decreaseIndent();
    }

    public static final Creator<StorageVolume> CREATOR = new Creator<StorageVolume>() {
        @Override
        public StorageVolume createFromParcel(Parcel in) {
            return new StorageVolume(in);
        }

        @Override
        public StorageVolume[] newArray(int size) {
            return new StorageVolume[size];
        }
    };

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel parcel, int flags) {
        parcel.writeString(mId);
        parcel.writeInt(mStorageId);
        parcel.writeString(mPath.toString());
        parcel.writeString(mDescription);
        parcel.writeInt(mPrimary ? 1 : 0);
        parcel.writeInt(mRemovable ? 1 : 0);
        parcel.writeInt(mEmulated ? 1 : 0);
        parcel.writeLong(mMtpReserveSize);
        parcel.writeInt(mAllowMassStorage ? 1 : 0);
        parcel.writeLong(mMaxFileSize);
        parcel.writeParcelable(mOwner, flags);
        parcel.writeString(mFsUuid);
        parcel.writeString(mState);
    }
}
