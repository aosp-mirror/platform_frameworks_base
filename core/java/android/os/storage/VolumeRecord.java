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

import android.annotation.UnsupportedAppUsage;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.DebugUtils;
import android.util.TimeUtils;

import com.android.internal.util.IndentingPrintWriter;
import com.android.internal.util.Preconditions;

import java.util.Objects;

/**
 * Metadata for a storage volume which may not be currently present.
 *
 * @hide
 */
public class VolumeRecord implements Parcelable {
    public static final String EXTRA_FS_UUID =
            "android.os.storage.extra.FS_UUID";

    public static final int USER_FLAG_INITED = 1 << 0;
    public static final int USER_FLAG_SNOOZED = 1 << 1;

    public final int type;
    public final String fsUuid;
    public String partGuid;
    public String nickname;
    public int userFlags;
    public long createdMillis;
    public long lastTrimMillis;
    public long lastBenchMillis;

    public VolumeRecord(int type, String fsUuid) {
        this.type = type;
        this.fsUuid = Preconditions.checkNotNull(fsUuid);
    }

    @UnsupportedAppUsage
    public VolumeRecord(Parcel parcel) {
        type = parcel.readInt();
        fsUuid = parcel.readString();
        partGuid = parcel.readString();
        nickname = parcel.readString();
        userFlags = parcel.readInt();
        createdMillis = parcel.readLong();
        lastTrimMillis = parcel.readLong();
        lastBenchMillis = parcel.readLong();
    }

    public int getType() {
        return type;
    }

    public String getFsUuid() {
        return fsUuid;
    }

    public String getNickname() {
        return nickname;
    }

    public boolean isInited() {
        return (userFlags & USER_FLAG_INITED) != 0;
    }

    public boolean isSnoozed() {
        return (userFlags & USER_FLAG_SNOOZED) != 0;
    }

    public void dump(IndentingPrintWriter pw) {
        pw.println("VolumeRecord:");
        pw.increaseIndent();
        pw.printPair("type", DebugUtils.valueToString(VolumeInfo.class, "TYPE_", type));
        pw.printPair("fsUuid", fsUuid);
        pw.printPair("partGuid", partGuid);
        pw.println();
        pw.printPair("nickname", nickname);
        pw.printPair("userFlags",
                DebugUtils.flagsToString(VolumeRecord.class, "USER_FLAG_", userFlags));
        pw.println();
        pw.printPair("createdMillis", TimeUtils.formatForLogging(createdMillis));
        pw.printPair("lastTrimMillis", TimeUtils.formatForLogging(lastTrimMillis));
        pw.printPair("lastBenchMillis", TimeUtils.formatForLogging(lastBenchMillis));
        pw.decreaseIndent();
        pw.println();
    }

    @Override
    public VolumeRecord clone() {
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
        if (o instanceof VolumeRecord) {
            return Objects.equals(fsUuid, ((VolumeRecord) o).fsUuid);
        } else {
            return false;
        }
    }

    @Override
    public int hashCode() {
        return fsUuid.hashCode();
    }

    @UnsupportedAppUsage
    public static final @android.annotation.NonNull Creator<VolumeRecord> CREATOR = new Creator<VolumeRecord>() {
        @Override
        public VolumeRecord createFromParcel(Parcel in) {
            return new VolumeRecord(in);
        }

        @Override
        public VolumeRecord[] newArray(int size) {
            return new VolumeRecord[size];
        }
    };

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel parcel, int flags) {
        parcel.writeInt(type);
        parcel.writeString(fsUuid);
        parcel.writeString(partGuid);
        parcel.writeString(nickname);
        parcel.writeInt(userFlags);
        parcel.writeLong(createdMillis);
        parcel.writeLong(lastTrimMillis);
        parcel.writeLong(lastBenchMillis);
    }
}
