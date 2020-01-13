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
import android.compat.annotation.UnsupportedAppUsage;
import android.content.res.Resources;
import android.os.Build;
import android.os.Parcel;
import android.os.Parcelable;
import android.text.TextUtils;
import android.util.DebugUtils;

import com.android.internal.util.IndentingPrintWriter;
import com.android.internal.util.Preconditions;

import java.io.CharArrayWriter;
import java.util.Objects;

/**
 * Information about a physical disk which may contain one or more
 * {@link VolumeInfo}.
 *
 * @hide
 */
public class DiskInfo implements Parcelable {
    public static final String ACTION_DISK_SCANNED =
            "android.os.storage.action.DISK_SCANNED";
    public static final String EXTRA_DISK_ID =
            "android.os.storage.extra.DISK_ID";
    public static final String EXTRA_VOLUME_COUNT =
            "android.os.storage.extra.VOLUME_COUNT";

    public static final int FLAG_ADOPTABLE = 1 << 0;
    public static final int FLAG_DEFAULT_PRIMARY = 1 << 1;
    public static final int FLAG_SD = 1 << 2;
    public static final int FLAG_USB = 1 << 3;

    public final String id;
    @UnsupportedAppUsage
    public final int flags;
    @UnsupportedAppUsage
    public long size;
    @UnsupportedAppUsage
    public String label;
    /** Hacky; don't rely on this count */
    public int volumeCount;
    public String sysPath;

    public DiskInfo(String id, int flags) {
        this.id = Preconditions.checkNotNull(id);
        this.flags = flags;
    }

    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 115609023)
    public DiskInfo(Parcel parcel) {
        id = parcel.readString();
        flags = parcel.readInt();
        size = parcel.readLong();
        label = parcel.readString();
        volumeCount = parcel.readInt();
        sysPath = parcel.readString();
    }

    @UnsupportedAppUsage
    public @NonNull String getId() {
        return id;
    }

    private boolean isInteresting(String label) {
        if (TextUtils.isEmpty(label)) {
            return false;
        }
        if (label.equalsIgnoreCase("ata")) {
            return false;
        }
        if (label.toLowerCase().contains("generic")) {
            return false;
        }
        if (label.toLowerCase().startsWith("usb")) {
            return false;
        }
        if (label.toLowerCase().startsWith("multiple")) {
            return false;
        }
        return true;
    }

    @UnsupportedAppUsage
    public @Nullable String getDescription() {
        final Resources res = Resources.getSystem();
        if ((flags & FLAG_SD) != 0) {
            if (isInteresting(label)) {
                return res.getString(com.android.internal.R.string.storage_sd_card_label, label);
            } else {
                return res.getString(com.android.internal.R.string.storage_sd_card);
            }
        } else if ((flags & FLAG_USB) != 0) {
            if (isInteresting(label)) {
                return res.getString(com.android.internal.R.string.storage_usb_drive_label, label);
            } else {
                return res.getString(com.android.internal.R.string.storage_usb_drive);
            }
        } else {
            return null;
        }
    }

    public @Nullable String getShortDescription() {
        final Resources res = Resources.getSystem();
        if (isSd()) {
            return res.getString(com.android.internal.R.string.storage_sd_card);
        } else if (isUsb()) {
            return res.getString(com.android.internal.R.string.storage_usb_drive);
        } else {
            return null;
        }
    }

    @UnsupportedAppUsage
    public boolean isAdoptable() {
        return (flags & FLAG_ADOPTABLE) != 0;
    }

    @UnsupportedAppUsage
    public boolean isDefaultPrimary() {
        return (flags & FLAG_DEFAULT_PRIMARY) != 0;
    }

    @UnsupportedAppUsage
    public boolean isSd() {
        return (flags & FLAG_SD) != 0;
    }

    @UnsupportedAppUsage
    public boolean isUsb() {
        return (flags & FLAG_USB) != 0;
    }

    @Override
    public String toString() {
        final CharArrayWriter writer = new CharArrayWriter();
        dump(new IndentingPrintWriter(writer, "    ", 80));
        return writer.toString();
    }

    public void dump(IndentingPrintWriter pw) {
        pw.println("DiskInfo{" + id + "}:");
        pw.increaseIndent();
        pw.printPair("flags", DebugUtils.flagsToString(getClass(), "FLAG_", flags));
        pw.printPair("size", size);
        pw.printPair("label", label);
        pw.println();
        pw.printPair("sysPath", sysPath);
        pw.decreaseIndent();
        pw.println();
    }

    @Override
    public DiskInfo clone() {
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
        if (o instanceof DiskInfo) {
            return Objects.equals(id, ((DiskInfo) o).id);
        } else {
            return false;
        }
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }

    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 115609023)
    public static final @android.annotation.NonNull Creator<DiskInfo> CREATOR = new Creator<DiskInfo>() {
        @Override
        public DiskInfo createFromParcel(Parcel in) {
            return new DiskInfo(in);
        }

        @Override
        public DiskInfo[] newArray(int size) {
            return new DiskInfo[size];
        }
    };

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel parcel, int flags) {
        parcel.writeString(id);
        parcel.writeInt(this.flags);
        parcel.writeLong(size);
        parcel.writeString(label);
        parcel.writeInt(volumeCount);
        parcel.writeString(sysPath);
    }
}
