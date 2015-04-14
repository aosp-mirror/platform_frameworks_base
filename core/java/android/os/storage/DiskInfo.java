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

import android.content.res.Resources;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.DebugUtils;

import com.android.internal.util.IndentingPrintWriter;
import com.android.internal.util.Preconditions;

import java.io.CharArrayWriter;

/**
 * Information about a physical disk which may contain one or more
 * {@link VolumeInfo}.
 *
 * @hide
 */
public class DiskInfo implements Parcelable {
    public static final String EXTRA_DISK_ID = "android.os.storage.extra.DISK_ID";

    public static final int FLAG_ADOPTABLE = 1 << 0;
    public static final int FLAG_DEFAULT_PRIMARY = 1 << 1;
    public static final int FLAG_SD = 1 << 2;
    public static final int FLAG_USB = 1 << 3;

    public final String id;
    public final int flags;
    public long size;
    public String label;
    public String[] volumeIds;

    public DiskInfo(String id, int flags) {
        this.id = Preconditions.checkNotNull(id);
        this.flags = flags;
    }

    public DiskInfo(Parcel parcel) {
        id = parcel.readString();
        flags = parcel.readInt();
        size = parcel.readLong();
        label = parcel.readString();
        volumeIds = parcel.readStringArray();
    }

    public String getDescription() {
        // TODO: splice vendor label into these strings
        if ((flags & FLAG_SD) != 0) {
            return Resources.getSystem().getString(com.android.internal.R.string.storage_sd_card);
        } else if ((flags & FLAG_USB) != 0) {
            return Resources.getSystem().getString(com.android.internal.R.string.storage_usb);
        } else {
            return null;
        }
    }

    public boolean isSd() {
        return (flags & FLAG_SD) != 0;
    }

    public boolean isUsb() {
        return (flags & FLAG_USB) != 0;
    }

    public boolean isAdoptable() {
        return (flags & FLAG_ADOPTABLE) != 0;
    }

    @Override
    public String toString() {
        final CharArrayWriter writer = new CharArrayWriter();
        dump(new IndentingPrintWriter(writer, "    ", 80));
        return writer.toString();
    }

    public void dump(IndentingPrintWriter pw) {
        pw.println("DiskInfo:");
        pw.increaseIndent();
        pw.printPair("id", id);
        pw.printPair("flags", DebugUtils.flagsToString(getClass(), "FLAG_", flags));
        pw.printPair("size", size);
        pw.printPair("label", label);
        pw.printPair("volumeIds", volumeIds);
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

    public static final Creator<DiskInfo> CREATOR = new Creator<DiskInfo>() {
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
        parcel.writeStringArray(volumeIds);
    }
}
