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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.TestApi;
import android.annotation.UnsupportedAppUsage;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.UserHandle;
import android.provider.DocumentsContract;

import com.android.internal.util.IndentingPrintWriter;
import com.android.internal.util.Preconditions;

import java.io.CharArrayWriter;
import java.io.File;
import java.util.Locale;

/**
 * Information about a shared/external storage volume for a specific user.
 *
 * <p>
 * A device always has one (and one only) primary storage volume, but it could have extra volumes,
 * like SD cards and USB drives. This object represents the logical view of a storage
 * volume for a specific user: different users might have different views for the same physical
 * volume (for example, if the volume is a built-in emulated storage).
 *
 * <p>
 * The storage volume is not necessarily mounted, applications should use {@link #getState()} to
 * verify its state.
 *
 * <p>
 * Applications willing to read or write to this storage volume needs to get a permission from the
 * user first, which can be achieved in the following ways:
 *
 * <ul>
 * <li>To get access to standard directories (like the {@link Environment#DIRECTORY_PICTURES}), they
 * can use the {@link #createAccessIntent(String)}. This is the recommend way, since it provides a
 * simpler API and narrows the access to the given directory (and its descendants).
 * <li>To get access to any directory (and its descendants), they can use the Storage Acess
 * Framework APIs (such as {@link Intent#ACTION_OPEN_DOCUMENT} and
 * {@link Intent#ACTION_OPEN_DOCUMENT_TREE}, although these APIs do not guarantee the user will
 * select this specific volume.
 * <li>To get read and write access to the primary storage volume, applications can declare the
 * {@link android.Manifest.permission#READ_EXTERNAL_STORAGE} and
 * {@link android.Manifest.permission#WRITE_EXTERNAL_STORAGE} permissions respectively, with the
 * latter including the former. This approach is discouraged, since users may be hesitant to grant
 * broad access to all files contained on a storage device.
 * </ul>
 *
 * <p>It can be obtained through {@link StorageManager#getStorageVolumes()} and
 * {@link StorageManager#getPrimaryStorageVolume()} and also as an extra in some broadcasts
 * (see {@link #EXTRA_STORAGE_VOLUME}).
 *
 * <p>
 * See {@link Environment#getExternalStorageDirectory()} for more info about shared/external
 * storage semantics.
 */
// NOTE: This is a legacy specialization of VolumeInfo which describes the volume for a specific
// user, but is now part of the public API.
public final class StorageVolume implements Parcelable {

    @UnsupportedAppUsage
    private final String mId;
    @UnsupportedAppUsage
    private final File mPath;
    private final File mInternalPath;
    @UnsupportedAppUsage
    private final String mDescription;
    @UnsupportedAppUsage
    private final boolean mPrimary;
    @UnsupportedAppUsage
    private final boolean mRemovable;
    private final boolean mEmulated;
    private final boolean mAllowMassStorage;
    private final long mMaxFileSize;
    private final UserHandle mOwner;
    private final String mFsUuid;
    private final String mState;

    /**
     * Name of the {@link Parcelable} extra in the {@link Intent#ACTION_MEDIA_REMOVED},
     * {@link Intent#ACTION_MEDIA_UNMOUNTED}, {@link Intent#ACTION_MEDIA_CHECKING},
     * {@link Intent#ACTION_MEDIA_NOFS}, {@link Intent#ACTION_MEDIA_MOUNTED},
     * {@link Intent#ACTION_MEDIA_SHARED}, {@link Intent#ACTION_MEDIA_BAD_REMOVAL},
     * {@link Intent#ACTION_MEDIA_UNMOUNTABLE}, and {@link Intent#ACTION_MEDIA_EJECT} broadcast that
     * contains a {@link StorageVolume}.
     */
    // Also sent on ACTION_MEDIA_UNSHARED, which is @hide
    public static final String EXTRA_STORAGE_VOLUME = "android.os.storage.extra.STORAGE_VOLUME";

    /**
     * Name of the String extra used by {@link #createAccessIntent(String) createAccessIntent}.
     *
     * @hide
     */
    public static final String EXTRA_DIRECTORY_NAME = "android.os.storage.extra.DIRECTORY_NAME";

    /**
     * Name of the intent used by {@link #createAccessIntent(String) createAccessIntent}.
     */
    private static final String ACTION_OPEN_EXTERNAL_DIRECTORY =
            "android.os.storage.action.OPEN_EXTERNAL_DIRECTORY";

    /** {@hide} */
    public static final int STORAGE_ID_INVALID = 0x00000000;
    /** {@hide} */
    public static final int STORAGE_ID_PRIMARY = 0x00010001;

    /** {@hide} */
    public StorageVolume(String id, File path, File internalPath, String description,
            boolean primary, boolean removable, boolean emulated, boolean allowMassStorage,
            long maxFileSize, UserHandle owner, String fsUuid, String state) {
        mId = Preconditions.checkNotNull(id);
        mPath = Preconditions.checkNotNull(path);
        mInternalPath = Preconditions.checkNotNull(internalPath);
        mDescription = Preconditions.checkNotNull(description);
        mPrimary = primary;
        mRemovable = removable;
        mEmulated = emulated;
        mAllowMassStorage = allowMassStorage;
        mMaxFileSize = maxFileSize;
        mOwner = Preconditions.checkNotNull(owner);
        mFsUuid = fsUuid;
        mState = Preconditions.checkNotNull(state);
    }

    private StorageVolume(Parcel in) {
        mId = in.readString();
        mPath = new File(in.readString());
        mInternalPath = new File(in.readString());
        mDescription = in.readString();
        mPrimary = in.readInt() != 0;
        mRemovable = in.readInt() != 0;
        mEmulated = in.readInt() != 0;
        mAllowMassStorage = in.readInt() != 0;
        mMaxFileSize = in.readLong();
        mOwner = in.readParcelable(null);
        mFsUuid = in.readString();
        mState = in.readString();
    }

    /** {@hide} */
    @UnsupportedAppUsage
    public String getId() {
        return mId;
    }

    /**
     * Returns the mount path for the volume.
     *
     * @return the mount path
     * @hide
     */
    @TestApi
    public String getPath() {
        return mPath.toString();
    }

    /**
     * Returns the path of the underlying filesystem.
     *
     * @return the internal path
     * @hide
     */
    public String getInternalPath() {
        return mInternalPath.toString();
    }

    /** {@hide} */
    @UnsupportedAppUsage
    public File getPathFile() {
        return mPath;
    }

    /**
     * Returns a user-visible description of the volume.
     *
     * @return the volume description
     */
    public String getDescription(Context context) {
        return mDescription;
    }

    /**
     * Returns true if the volume is the primary shared/external storage, which is the volume
     * backed by {@link Environment#getExternalStorageDirectory()}.
     */
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
     * Returns true if this volume can be shared via USB mass storage.
     *
     * @return whether mass storage is allowed
     * @hide
     */
    @UnsupportedAppUsage
    public boolean allowMassStorage() {
        return mAllowMassStorage;
    }

    /**
     * Returns maximum file size for the volume, or zero if it is unbounded.
     *
     * @return maximum file size
     * @hide
     */
    @UnsupportedAppUsage
    public long getMaxFileSize() {
        return mMaxFileSize;
    }

    /** {@hide} */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 115609023)
    public UserHandle getOwner() {
        return mOwner;
    }

    /**
     * Gets the volume UUID, if any.
     */
    public @Nullable String getUuid() {
        return mFsUuid;
    }

    /** {@hide} */
    public @Nullable String getNormalizedUuid() {
        return mFsUuid != null ? mFsUuid.toLowerCase(Locale.US) : null;
    }

    /**
     * Parse and return volume UUID as FAT volume ID, or return -1 if unable to
     * parse or UUID is unknown.
     * @hide
     */
    @UnsupportedAppUsage
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

    /** {@hide} */
    @UnsupportedAppUsage
    public String getUserLabel() {
        return mDescription;
    }

    /**
     * Returns the current state of the volume.
     *
     * @return one of {@link Environment#MEDIA_UNKNOWN}, {@link Environment#MEDIA_REMOVED},
     *         {@link Environment#MEDIA_UNMOUNTED}, {@link Environment#MEDIA_CHECKING},
     *         {@link Environment#MEDIA_NOFS}, {@link Environment#MEDIA_MOUNTED},
     *         {@link Environment#MEDIA_MOUNTED_READ_ONLY}, {@link Environment#MEDIA_SHARED},
     *         {@link Environment#MEDIA_BAD_REMOVAL}, or {@link Environment#MEDIA_UNMOUNTABLE}.
     */
    public String getState() {
        return mState;
    }

    /**
     * Builds an intent to give access to a standard storage directory or entire volume after
     * obtaining the user's approval.
     * <p>
     * When invoked, the system will ask the user to grant access to the requested directory (and
     * its descendants). The result of the request will be returned to the activity through the
     * {@code onActivityResult} method.
     * <p>
     * To gain access to descendants (child, grandchild, etc) documents, use
     * {@link DocumentsContract#buildDocumentUriUsingTree(Uri, String)}, or
     * {@link DocumentsContract#buildChildDocumentsUriUsingTree(Uri, String)} with the returned URI.
     * <p>
     * If your application only needs to store internal data, consider using
     * {@link Context#getExternalFilesDirs(String) Context.getExternalFilesDirs},
     * {@link Context#getExternalCacheDirs()}, or {@link Context#getExternalMediaDirs()}, which
     * require no permissions to read or write.
     * <p>
     * Access to the entire volume is only available for non-primary volumes (for the primary
     * volume, apps can use the {@link android.Manifest.permission#READ_EXTERNAL_STORAGE} and
     * {@link android.Manifest.permission#WRITE_EXTERNAL_STORAGE} permissions) and should be used
     * with caution, since users are more likely to deny access when asked for entire volume access
     * rather than specific directories.
     *
     * @param directoryName must be one of {@link Environment#DIRECTORY_MUSIC},
     *            {@link Environment#DIRECTORY_PODCASTS}, {@link Environment#DIRECTORY_RINGTONES},
     *            {@link Environment#DIRECTORY_ALARMS}, {@link Environment#DIRECTORY_NOTIFICATIONS},
     *            {@link Environment#DIRECTORY_PICTURES}, {@link Environment#DIRECTORY_MOVIES},
     *            {@link Environment#DIRECTORY_DOWNLOADS}, {@link Environment#DIRECTORY_DCIM}, or
     *            {@link Environment#DIRECTORY_DOCUMENTS}, or {@code null} to request access to the
     *            entire volume.
     * @return intent to request access, or {@code null} if the requested directory is invalid for
     *         that volume.
     * @see DocumentsContract
     * @deprecated Callers should migrate to using {@link Intent#ACTION_OPEN_DOCUMENT_TREE} instead.
     *             Launching this {@link Intent} on devices running
     *             {@link android.os.Build.VERSION_CODES#Q} or higher, will immediately finish
     *             with a result code of {@link android.app.Activity#RESULT_CANCELED}.
     */
    @Deprecated
    public @Nullable Intent createAccessIntent(String directoryName) {
        if ((isPrimary() && directoryName == null) ||
                (directoryName != null && !Environment.isStandardDirectory(directoryName))) {
            return null;
        }
        final Intent intent = new Intent(ACTION_OPEN_EXTERNAL_DIRECTORY);
        intent.putExtra(EXTRA_STORAGE_VOLUME, this);
        intent.putExtra(EXTRA_DIRECTORY_NAME, directoryName);
        return intent;
    }

    /**
     * Builds an {@link Intent#ACTION_OPEN_DOCUMENT_TREE} to allow the user to grant access to any
     * directory subtree (or entire volume) from the {@link android.provider.DocumentsProvider}s
     * available on the device. The initial location of the document navigation will be the root of
     * this {@link StorageVolume}.
     *
     * Note that the returned {@link Intent} simply suggests that the user picks this {@link
     * StorageVolume} by default, but the user may select a different location. Callers must respect
     * the user's chosen location, even if it is different from the originally requested location.
     *
     * @return intent to {@link Intent#ACTION_OPEN_DOCUMENT_TREE} initially showing the contents
     *         of this {@link StorageVolume}
     * @see Intent#ACTION_OPEN_DOCUMENT_TREE
     */
    @NonNull public Intent createOpenDocumentTreeIntent() {
        final String rootId = isEmulated()
                ? DocumentsContract.EXTERNAL_STORAGE_PRIMARY_EMULATED_ROOT_ID
                : mFsUuid;
        final Uri rootUri = DocumentsContract.buildRootUri(
                DocumentsContract.EXTERNAL_STORAGE_PROVIDER_AUTHORITY, rootId);
        final Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
                .putExtra(DocumentsContract.EXTRA_INITIAL_URI, rootUri)
                .putExtra(DocumentsContract.EXTRA_SHOW_ADVANCED, true);
        return intent;
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
        final StringBuilder buffer = new StringBuilder("StorageVolume: ").append(mDescription);
        if (mFsUuid != null) {
            buffer.append(" (").append(mFsUuid).append(")");
        }
        return buffer.toString();
    }

    /** {@hide} */
    // TODO: find out where toString() is called internally and replace these calls by dump().
    public String dump() {
        final CharArrayWriter writer = new CharArrayWriter();
        dump(new IndentingPrintWriter(writer, "    ", 80));
        return writer.toString();
    }

    /** {@hide} */
    public void dump(IndentingPrintWriter pw) {
        pw.println("StorageVolume:");
        pw.increaseIndent();
        pw.printPair("mId", mId);
        pw.printPair("mPath", mPath);
        pw.printPair("mInternalPath", mInternalPath);
        pw.printPair("mDescription", mDescription);
        pw.printPair("mPrimary", mPrimary);
        pw.printPair("mRemovable", mRemovable);
        pw.printPair("mEmulated", mEmulated);
        pw.printPair("mAllowMassStorage", mAllowMassStorage);
        pw.printPair("mMaxFileSize", mMaxFileSize);
        pw.printPair("mOwner", mOwner);
        pw.printPair("mFsUuid", mFsUuid);
        pw.printPair("mState", mState);
        pw.decreaseIndent();
    }

    public static final @android.annotation.NonNull Creator<StorageVolume> CREATOR = new Creator<StorageVolume>() {
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
        parcel.writeString(mPath.toString());
        parcel.writeString(mInternalPath.toString());
        parcel.writeString(mDescription);
        parcel.writeInt(mPrimary ? 1 : 0);
        parcel.writeInt(mRemovable ? 1 : 0);
        parcel.writeInt(mEmulated ? 1 : 0);
        parcel.writeInt(mAllowMassStorage ? 1 : 0);
        parcel.writeLong(mMaxFileSize);
        parcel.writeParcelable(mOwner, flags);
        parcel.writeString(mFsUuid);
        parcel.writeString(mState);
    }
}
