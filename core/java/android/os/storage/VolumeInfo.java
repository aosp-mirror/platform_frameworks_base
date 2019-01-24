/*
 * Copyright (C) 2015 The Android Open Source Project
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
import android.annotation.UnsupportedAppUsage;
import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;
import android.net.Uri;
import android.os.Environment;
import android.os.IVold;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.UserHandle;
import android.provider.DocumentsContract;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.DebugUtils;
import android.util.SparseArray;
import android.util.SparseIntArray;

import com.android.internal.R;
import com.android.internal.util.IndentingPrintWriter;
import com.android.internal.util.Preconditions;

import java.io.CharArrayWriter;
import java.io.File;
import java.util.Comparator;
import java.util.Locale;
import java.util.Objects;

/**
 * Information about a storage volume that may be mounted. A volume may be a
 * partition on a physical {@link DiskInfo}, an emulated volume above some other
 * storage medium, or a standalone container like an ASEC or OBB.
 * <p>
 * Volumes may be mounted with various flags:
 * <ul>
 * <li>{@link #MOUNT_FLAG_PRIMARY} means the volume provides primary external
 * storage, historically found at {@code /sdcard}.
 * <li>{@link #MOUNT_FLAG_VISIBLE} means the volume is visible to third-party
 * apps for direct filesystem access. The system should send out relevant
 * storage broadcasts and index any media on visible volumes. Visible volumes
 * are considered a more stable part of the device, which is why we take the
 * time to index them. In particular, transient volumes like USB OTG devices
 * <em>should not</em> be marked as visible; their contents should be surfaced
 * to apps through the Storage Access Framework.
 * </ul>
 *
 * @hide
 */
public class VolumeInfo implements Parcelable {
    public static final String ACTION_VOLUME_STATE_CHANGED =
            "android.os.storage.action.VOLUME_STATE_CHANGED";
    public static final String EXTRA_VOLUME_ID =
            "android.os.storage.extra.VOLUME_ID";
    public static final String EXTRA_VOLUME_STATE =
            "android.os.storage.extra.VOLUME_STATE";

    /** Stub volume representing internal private storage */
    public static final String ID_PRIVATE_INTERNAL = "private";
    /** Real volume representing internal emulated storage */
    public static final String ID_EMULATED_INTERNAL = "emulated";

    @UnsupportedAppUsage
    public static final int TYPE_PUBLIC = IVold.VOLUME_TYPE_PUBLIC;
    public static final int TYPE_PRIVATE = IVold.VOLUME_TYPE_PRIVATE;
    @UnsupportedAppUsage
    public static final int TYPE_EMULATED = IVold.VOLUME_TYPE_EMULATED;
    public static final int TYPE_ASEC = IVold.VOLUME_TYPE_ASEC;
    public static final int TYPE_OBB = IVold.VOLUME_TYPE_OBB;
    public static final int TYPE_STUB = IVold.VOLUME_TYPE_STUB;

    public static final int STATE_UNMOUNTED = IVold.VOLUME_STATE_UNMOUNTED;
    public static final int STATE_CHECKING = IVold.VOLUME_STATE_CHECKING;
    public static final int STATE_MOUNTED = IVold.VOLUME_STATE_MOUNTED;
    public static final int STATE_MOUNTED_READ_ONLY = IVold.VOLUME_STATE_MOUNTED_READ_ONLY;
    public static final int STATE_FORMATTING = IVold.VOLUME_STATE_FORMATTING;
    public static final int STATE_EJECTING = IVold.VOLUME_STATE_EJECTING;
    public static final int STATE_UNMOUNTABLE = IVold.VOLUME_STATE_UNMOUNTABLE;
    public static final int STATE_REMOVED = IVold.VOLUME_STATE_REMOVED;
    public static final int STATE_BAD_REMOVAL = IVold.VOLUME_STATE_BAD_REMOVAL;

    public static final int MOUNT_FLAG_PRIMARY = IVold.MOUNT_FLAG_PRIMARY;
    public static final int MOUNT_FLAG_VISIBLE = IVold.MOUNT_FLAG_VISIBLE;

    private static SparseArray<String> sStateToEnvironment = new SparseArray<>();
    private static ArrayMap<String, String> sEnvironmentToBroadcast = new ArrayMap<>();
    private static SparseIntArray sStateToDescrip = new SparseIntArray();

    private static final Comparator<VolumeInfo>
            sDescriptionComparator = new Comparator<VolumeInfo>() {
        @Override
        public int compare(VolumeInfo lhs, VolumeInfo rhs) {
            if (VolumeInfo.ID_PRIVATE_INTERNAL.equals(lhs.getId())) {
                return -1;
            } else if (lhs.getDescription() == null) {
                return 1;
            } else if (rhs.getDescription() == null) {
                return -1;
            } else {
                return lhs.getDescription().compareTo(rhs.getDescription());
            }
        }
    };

    static {
        sStateToEnvironment.put(VolumeInfo.STATE_UNMOUNTED, Environment.MEDIA_UNMOUNTED);
        sStateToEnvironment.put(VolumeInfo.STATE_CHECKING, Environment.MEDIA_CHECKING);
        sStateToEnvironment.put(VolumeInfo.STATE_MOUNTED, Environment.MEDIA_MOUNTED);
        sStateToEnvironment.put(VolumeInfo.STATE_MOUNTED_READ_ONLY, Environment.MEDIA_MOUNTED_READ_ONLY);
        sStateToEnvironment.put(VolumeInfo.STATE_FORMATTING, Environment.MEDIA_UNMOUNTED);
        sStateToEnvironment.put(VolumeInfo.STATE_EJECTING, Environment.MEDIA_EJECTING);
        sStateToEnvironment.put(VolumeInfo.STATE_UNMOUNTABLE, Environment.MEDIA_UNMOUNTABLE);
        sStateToEnvironment.put(VolumeInfo.STATE_REMOVED, Environment.MEDIA_REMOVED);
        sStateToEnvironment.put(VolumeInfo.STATE_BAD_REMOVAL, Environment.MEDIA_BAD_REMOVAL);

        sEnvironmentToBroadcast.put(Environment.MEDIA_UNMOUNTED, Intent.ACTION_MEDIA_UNMOUNTED);
        sEnvironmentToBroadcast.put(Environment.MEDIA_CHECKING, Intent.ACTION_MEDIA_CHECKING);
        sEnvironmentToBroadcast.put(Environment.MEDIA_MOUNTED, Intent.ACTION_MEDIA_MOUNTED);
        sEnvironmentToBroadcast.put(Environment.MEDIA_MOUNTED_READ_ONLY, Intent.ACTION_MEDIA_MOUNTED);
        sEnvironmentToBroadcast.put(Environment.MEDIA_EJECTING, Intent.ACTION_MEDIA_EJECT);
        sEnvironmentToBroadcast.put(Environment.MEDIA_UNMOUNTABLE, Intent.ACTION_MEDIA_UNMOUNTABLE);
        sEnvironmentToBroadcast.put(Environment.MEDIA_REMOVED, Intent.ACTION_MEDIA_REMOVED);
        sEnvironmentToBroadcast.put(Environment.MEDIA_BAD_REMOVAL, Intent.ACTION_MEDIA_BAD_REMOVAL);

        sStateToDescrip.put(VolumeInfo.STATE_UNMOUNTED, R.string.ext_media_status_unmounted);
        sStateToDescrip.put(VolumeInfo.STATE_CHECKING, R.string.ext_media_status_checking);
        sStateToDescrip.put(VolumeInfo.STATE_MOUNTED, R.string.ext_media_status_mounted);
        sStateToDescrip.put(VolumeInfo.STATE_MOUNTED_READ_ONLY, R.string.ext_media_status_mounted_ro);
        sStateToDescrip.put(VolumeInfo.STATE_FORMATTING, R.string.ext_media_status_formatting);
        sStateToDescrip.put(VolumeInfo.STATE_EJECTING, R.string.ext_media_status_ejecting);
        sStateToDescrip.put(VolumeInfo.STATE_UNMOUNTABLE, R.string.ext_media_status_unmountable);
        sStateToDescrip.put(VolumeInfo.STATE_REMOVED, R.string.ext_media_status_removed);
        sStateToDescrip.put(VolumeInfo.STATE_BAD_REMOVAL, R.string.ext_media_status_bad_removal);
    }

    /** vold state */
    public final String id;
    @UnsupportedAppUsage
    public final int type;
    @UnsupportedAppUsage
    public final DiskInfo disk;
    public final String partGuid;
    public int mountFlags = 0;
    public int mountUserId = UserHandle.USER_NULL;
    @UnsupportedAppUsage
    public int state = STATE_UNMOUNTED;
    public String fsType;
    @UnsupportedAppUsage
    public String fsUuid;
    @UnsupportedAppUsage
    public String fsLabel;
    @UnsupportedAppUsage
    public String path;
    @UnsupportedAppUsage
    public String internalPath;

    public VolumeInfo(String id, int type, DiskInfo disk, String partGuid) {
        this.id = Preconditions.checkNotNull(id);
        this.type = type;
        this.disk = disk;
        this.partGuid = partGuid;
    }

    @UnsupportedAppUsage
    public VolumeInfo(Parcel parcel) {
        id = parcel.readString();
        type = parcel.readInt();
        if (parcel.readInt() != 0) {
            disk = DiskInfo.CREATOR.createFromParcel(parcel);
        } else {
            disk = null;
        }
        partGuid = parcel.readString();
        mountFlags = parcel.readInt();
        mountUserId = parcel.readInt();
        state = parcel.readInt();
        fsType = parcel.readString();
        fsUuid = parcel.readString();
        fsLabel = parcel.readString();
        path = parcel.readString();
        internalPath = parcel.readString();
    }

    @UnsupportedAppUsage
    public static @NonNull String getEnvironmentForState(int state) {
        final String envState = sStateToEnvironment.get(state);
        if (envState != null) {
            return envState;
        } else {
            return Environment.MEDIA_UNKNOWN;
        }
    }

    public static @Nullable String getBroadcastForEnvironment(String envState) {
        return sEnvironmentToBroadcast.get(envState);
    }

    public static @Nullable String getBroadcastForState(int state) {
        return getBroadcastForEnvironment(getEnvironmentForState(state));
    }

    public static @NonNull Comparator<VolumeInfo> getDescriptionComparator() {
        return sDescriptionComparator;
    }

    @UnsupportedAppUsage
    public @NonNull String getId() {
        return id;
    }

    @UnsupportedAppUsage
    public @Nullable DiskInfo getDisk() {
        return disk;
    }

    @UnsupportedAppUsage
    public @Nullable String getDiskId() {
        return (disk != null) ? disk.id : null;
    }

    @UnsupportedAppUsage
    public int getType() {
        return type;
    }

    @UnsupportedAppUsage
    public int getState() {
        return state;
    }

    public int getStateDescription() {
        return sStateToDescrip.get(state, 0);
    }

    @UnsupportedAppUsage
    public @Nullable String getFsUuid() {
        return fsUuid;
    }

    public @Nullable String getNormalizedFsUuid() {
        return fsUuid != null ? fsUuid.toLowerCase(Locale.US) : null;
    }

    @UnsupportedAppUsage
    public int getMountUserId() {
        return mountUserId;
    }

    @UnsupportedAppUsage
    public @Nullable String getDescription() {
        if (ID_PRIVATE_INTERNAL.equals(id) || ID_EMULATED_INTERNAL.equals(id)) {
            return Resources.getSystem().getString(com.android.internal.R.string.storage_internal);
        } else if (!TextUtils.isEmpty(fsLabel)) {
            return fsLabel;
        } else {
            return null;
        }
    }

    @UnsupportedAppUsage
    public boolean isMountedReadable() {
        return state == STATE_MOUNTED || state == STATE_MOUNTED_READ_ONLY;
    }

    @UnsupportedAppUsage
    public boolean isMountedWritable() {
        return state == STATE_MOUNTED;
    }

    @UnsupportedAppUsage
    public boolean isPrimary() {
        return (mountFlags & MOUNT_FLAG_PRIMARY) != 0;
    }

    @UnsupportedAppUsage
    public boolean isPrimaryPhysical() {
        return isPrimary() && (getType() == TYPE_PUBLIC);
    }

    @UnsupportedAppUsage
    public boolean isVisible() {
        return (mountFlags & MOUNT_FLAG_VISIBLE) != 0;
    }

    public boolean isVisibleForUser(int userId) {
        if ((type == TYPE_PUBLIC || type == TYPE_STUB) && mountUserId == userId) {
            return isVisible();
        } else if (type == TYPE_EMULATED) {
            return isVisible();
        } else {
            return false;
        }
    }

    public boolean isVisibleForRead(int userId) {
        return isVisibleForUser(userId);
    }

    @UnsupportedAppUsage
    public boolean isVisibleForWrite(int userId) {
        return isVisibleForUser(userId);
    }

    @UnsupportedAppUsage
    public File getPath() {
        return (path != null) ? new File(path) : null;
    }

    @UnsupportedAppUsage
    public File getInternalPath() {
        return (internalPath != null) ? new File(internalPath) : null;
    }

    @UnsupportedAppUsage
    public File getPathForUser(int userId) {
        if (path == null) {
            return null;
        } else if (type == TYPE_PUBLIC || type == TYPE_STUB) {
            return new File(path);
        } else if (type == TYPE_EMULATED) {
            return new File(path, Integer.toString(userId));
        } else {
            return null;
        }
    }

    /**
     * Path which is accessible to apps holding
     * {@link android.Manifest.permission#WRITE_MEDIA_STORAGE}.
     */
    @UnsupportedAppUsage
    public File getInternalPathForUser(int userId) {
        if (path == null) {
            return null;
        } else if (type == TYPE_PUBLIC || type == TYPE_STUB) {
            // TODO: plumb through cleaner path from vold
            return new File(path.replace("/storage/", "/mnt/media_rw/"));
        } else {
            return getPathForUser(userId);
        }
    }

    @UnsupportedAppUsage
    public StorageVolume buildStorageVolume(Context context, int userId, boolean reportUnmounted) {
        final StorageManager storage = context.getSystemService(StorageManager.class);

        final boolean removable;
        final boolean emulated;
        final boolean allowMassStorage = false;
        final String envState = reportUnmounted
                ? Environment.MEDIA_UNMOUNTED : getEnvironmentForState(state);

        File userPath = getPathForUser(userId);
        if (userPath == null) {
            userPath = new File("/dev/null");
        }
        File internalPath = getInternalPathForUser(userId);
        if (internalPath == null) {
            internalPath = new File("/dev/null");
        }

        String description = null;
        String derivedFsUuid = fsUuid;
        long maxFileSize = 0;

        if (type == TYPE_EMULATED) {
            emulated = true;

            final VolumeInfo privateVol = storage.findPrivateForEmulated(this);
            if (privateVol != null) {
                description = storage.getBestVolumeDescription(privateVol);
                derivedFsUuid = privateVol.fsUuid;
            }

            if (ID_EMULATED_INTERNAL.equals(id)) {
                removable = false;
            } else {
                removable = true;
            }

        } else if (type == TYPE_PUBLIC || type == TYPE_STUB) {
            emulated = false;
            removable = true;

            description = storage.getBestVolumeDescription(this);

            if ("vfat".equals(fsType)) {
                maxFileSize = 4294967295L;
            }

        } else {
            throw new IllegalStateException("Unexpected volume type " + type);
        }

        if (description == null) {
            description = context.getString(android.R.string.unknownName);
        }

        return new StorageVolume(id, userPath, internalPath, description, isPrimary(), removable,
                emulated, allowMassStorage, maxFileSize, new UserHandle(userId),
                derivedFsUuid, envState);
    }

    @UnsupportedAppUsage
    public static int buildStableMtpStorageId(String fsUuid) {
        if (TextUtils.isEmpty(fsUuid)) {
            return StorageVolume.STORAGE_ID_INVALID;
        } else {
            int hash = 0;
            for (int i = 0; i < fsUuid.length(); ++i) {
                hash = 31 * hash + fsUuid.charAt(i);
            }
            hash = (hash ^ (hash << 16)) & 0xffff0000;
            // Work around values that the spec doesn't allow, or that we've
            // reserved for primary
            if (hash == 0x00000000) hash = 0x00020000;
            if (hash == 0x00010000) hash = 0x00020000;
            if (hash == 0xffff0000) hash = 0xfffe0000;
            return hash | 0x0001;
        }
    }

    // TODO: avoid this layering violation
    private static final String DOCUMENT_AUTHORITY = "com.android.externalstorage.documents";
    private static final String DOCUMENT_ROOT_PRIMARY_EMULATED = "primary";

    /**
     * Build an intent to browse the contents of this volume. Only valid for
     * {@link #TYPE_EMULATED} or {@link #TYPE_PUBLIC}.
     */
    @UnsupportedAppUsage
    public @Nullable Intent buildBrowseIntent() {
        return buildBrowseIntentForUser(UserHandle.myUserId());
    }

    public @Nullable Intent buildBrowseIntentForUser(int userId) {
        final Uri uri;
        if ((type == VolumeInfo.TYPE_PUBLIC || type == VolumeInfo.TYPE_STUB)
                && mountUserId == userId) {
            uri = DocumentsContract.buildRootUri(DOCUMENT_AUTHORITY, fsUuid);
        } else if (type == VolumeInfo.TYPE_EMULATED && isPrimary()) {
            uri = DocumentsContract.buildRootUri(DOCUMENT_AUTHORITY,
                    DOCUMENT_ROOT_PRIMARY_EMULATED);
        } else {
            return null;
        }

        final Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.addCategory(Intent.CATEGORY_DEFAULT);
        intent.setDataAndType(uri, DocumentsContract.Root.MIME_TYPE_ITEM);

        // note that docsui treats this as *force* show advanced. So sending
        // false permits advanced to be shown based on user preferences.
        intent.putExtra(DocumentsContract.EXTRA_SHOW_ADVANCED, isPrimary());
        return intent;
    }

    @Override
    public String toString() {
        final CharArrayWriter writer = new CharArrayWriter();
        dump(new IndentingPrintWriter(writer, "    ", 80));
        return writer.toString();
    }

    public void dump(IndentingPrintWriter pw) {
        pw.println("VolumeInfo{" + id + "}:");
        pw.increaseIndent();
        pw.printPair("type", DebugUtils.valueToString(getClass(), "TYPE_", type));
        pw.printPair("diskId", getDiskId());
        pw.printPair("partGuid", partGuid);
        pw.printPair("mountFlags", DebugUtils.flagsToString(getClass(), "MOUNT_FLAG_", mountFlags));
        pw.printPair("mountUserId", mountUserId);
        pw.printPair("state", DebugUtils.valueToString(getClass(), "STATE_", state));
        pw.println();
        pw.printPair("fsType", fsType);
        pw.printPair("fsUuid", fsUuid);
        pw.printPair("fsLabel", fsLabel);
        pw.println();
        pw.printPair("path", path);
        pw.printPair("internalPath", internalPath);
        pw.decreaseIndent();
        pw.println();
    }

    @Override
    public VolumeInfo clone() {
        final Parcel temp = Parcel.obtain();
        try {
            writeToParcel(temp, 0);
            temp.setDataPosition(0);
            return CREATOR.createFromParcel(temp);
        } finally {
            temp.recycle();
        }
    }

    @Override
    public boolean equals(Object o) {
        if (o instanceof VolumeInfo) {
            return Objects.equals(id, ((VolumeInfo) o).id);
        } else {
            return false;
        }
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }

    @UnsupportedAppUsage
    public static final Creator<VolumeInfo> CREATOR = new Creator<VolumeInfo>() {
        @Override
        public VolumeInfo createFromParcel(Parcel in) {
            return new VolumeInfo(in);
        }

        @Override
        public VolumeInfo[] newArray(int size) {
            return new VolumeInfo[size];
        }
    };

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel parcel, int flags) {
        parcel.writeString(id);
        parcel.writeInt(type);
        if (disk != null) {
            parcel.writeInt(1);
            disk.writeToParcel(parcel, flags);
        } else {
            parcel.writeInt(0);
        }
        parcel.writeString(partGuid);
        parcel.writeInt(mountFlags);
        parcel.writeInt(mountUserId);
        parcel.writeInt(state);
        parcel.writeString(fsType);
        parcel.writeString(fsUuid);
        parcel.writeString(fsLabel);
        parcel.writeString(path);
        parcel.writeString(internalPath);
    }
}
