/*
 * Copyright (C) 2017 The Android Open Source Project
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

import android.util.Slog;

import java.util.ArrayList;
import java.util.List;

/**
 * Wrapper class for sending data from Android OS to StatsD.
 *
 * @hide
 */
public final class StatsLogEventWrapper implements Parcelable {
    static final boolean DEBUG = false;
    static final String TAG = "StatsLogEventWrapper";

    // Keep in sync with FieldValue.h enums
    private static final int EVENT_TYPE_UNKNOWN = 0;
    private static final int EVENT_TYPE_INT = 1; /* int32_t */
    private static final int EVENT_TYPE_LONG = 2; /* int64_t */
    private static final int EVENT_TYPE_FLOAT = 3;
    private static final int EVENT_TYPE_DOUBLE = 4;
    private static final int EVENT_TYPE_STRING = 5;
    private static final int EVENT_TYPE_STORAGE = 6;

    List<Integer> mTypes = new ArrayList<>();
    List<Object> mValues = new ArrayList<>();
    int mTag;
    long mElapsedTimeNs;
    long mWallClockTimeNs;

    public StatsLogEventWrapper(int tag, long elapsedTimeNs, long wallClockTimeNs) {
        this.mTag = tag;
        this.mElapsedTimeNs = elapsedTimeNs;
        this.mWallClockTimeNs = wallClockTimeNs;
    }

    /**
     * Boilerplate for Parcel.
     */
    public static final Parcelable.Creator<StatsLogEventWrapper> CREATOR = new
            Parcelable.Creator<StatsLogEventWrapper>() {
                public StatsLogEventWrapper createFromParcel(Parcel in) {
                    android.util.EventLog.writeEvent(0x534e4554, "112550251",
                            android.os.Binder.getCallingUid(), "");
                    // Purposefully leaving this method not implemented.
                    throw new RuntimeException("Not implemented");
                }

                public StatsLogEventWrapper[] newArray(int size) {
                    android.util.EventLog.writeEvent(0x534e4554, "112550251",
                            android.os.Binder.getCallingUid(), "");
                    // Purposefully leaving this method not implemented.
                    throw new RuntimeException("Not implemented");
                }
            };

    /**
     * Write a int value.
     */
    public void writeInt(int val) {
        mTypes.add(EVENT_TYPE_INT);
        mValues.add(val);
    }

    /**
     * Write a long value.
     */
    public void writeLong(long val) {
        mTypes.add(EVENT_TYPE_LONG);
        mValues.add(val);
    }

    /**
     * Write a string value.
     */
    public void writeString(String val) {
        mTypes.add(EVENT_TYPE_STRING);
        // use empty string for null
        mValues.add(val == null ? "" : val);
    }

    /**
     * Write a float value.
     */
    public void writeFloat(float val) {
        mTypes.add(EVENT_TYPE_FLOAT);
        mValues.add(val);
    }

    /**
     * Write a storage value.
     */
    public void writeStorage(byte[] val) {
        mTypes.add(EVENT_TYPE_STORAGE);
        mValues.add(val);
    }

    /**
     * Write a boolean value.
     */
    public void writeBoolean(boolean val) {
        mTypes.add(EVENT_TYPE_INT);
        mValues.add(val ? 1 : 0);
    }

    /**
     * Writes the stored fields to a byte array. Will first write a new-line character to denote
     * END_LIST before writing contents to byte array.
     */

    public void writeToParcel(Parcel out, int flags) {
        if (DEBUG) {
            Slog.d(TAG,
                    "Writing " + mTag + " " + mElapsedTimeNs + " " + mWallClockTimeNs + " and "
                            + mTypes.size() + " elements.");
        }
        out.writeInt(mTag);
        out.writeLong(mElapsedTimeNs);
        out.writeLong(mWallClockTimeNs);
        out.writeInt(mTypes.size());
        for (int i = 0; i < mTypes.size(); i++) {
            out.writeInt(mTypes.get(i));
            switch (mTypes.get(i)) {
                case EVENT_TYPE_INT:
                    out.writeInt((int) mValues.get(i));
                    break;
                case EVENT_TYPE_LONG:
                    out.writeLong((long) mValues.get(i));
                    break;
                case EVENT_TYPE_FLOAT:
                    out.writeFloat((float) mValues.get(i));
                    break;
                case EVENT_TYPE_DOUBLE:
                    out.writeDouble((double) mValues.get(i));
                    break;
                case EVENT_TYPE_STRING:
                    out.writeString((String) mValues.get(i));
                    break;
                case EVENT_TYPE_STORAGE:
                    out.writeByteArray((byte[]) mValues.get(i));
                    break;
                default:
                    break;
            }
        }
    }

    /**
     * Boilerplate for Parcel.
     */
    public int describeContents() {
        return 0;
    }
}
