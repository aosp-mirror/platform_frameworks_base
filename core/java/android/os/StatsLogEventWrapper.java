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

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Wrapper class for sending data from Android OS to StatsD.
 *
 * @hide
 */
public final class StatsLogEventWrapper implements Parcelable {
    private ByteArrayOutputStream mStorage = new ByteArrayOutputStream();

    // Below are constants copied from log/log.h
    private static final int EVENT_TYPE_INT = 0;  /* int32_t */
    private static final int EVENT_TYPE_LONG = 1; /* int64_t */
    private static final int EVENT_TYPE_STRING = 2;
    private static final int EVENT_TYPE_LIST = 3;
    private static final int EVENT_TYPE_FLOAT = 4;

    // Keep this in sync with system/core/logcat/event.logtags
    private static final int STATS_BUFFER_TAG_ID = 1937006964;
    /**
     * Creates a log_event that is binary-encoded as implemented in
     * system/core/liblog/log_event_list.c; this allows us to use the same parsing logic in statsd
     * for pushed and pulled data. The write* methods must be called in the same order as their
     * field number. There is no checking that the correct number of write* methods is called.
     * We also write an END_LIST character before beginning to write to parcel, but this END_LIST
     * may be unnecessary.
     *
     * @param tag    The integer representing the tag for this event.
     * @param fields The number of fields specified in this event.
     */
    public StatsLogEventWrapper(long elapsedNanos, int tag, int fields) {
        // Write four bytes from tag, starting with least-significant bit.
        // For pulled data, this tag number is not really used. We use the same tag number as
        // pushed ones to be consistent.
        write4Bytes(STATS_BUFFER_TAG_ID);
        mStorage.write(EVENT_TYPE_LIST); // This is required to start the log entry.
        mStorage.write(fields + 2); // Indicate number of elements in this list. +1 for the tag
        // The first element is the elapsed realtime.
        writeLong(elapsedNanos);
        // The second element is the real atom tag number
        writeInt(tag);
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

    private void write4Bytes(int val) {
        mStorage.write(val);
        mStorage.write(val >>> 8);
        mStorage.write(val >>> 16);
        mStorage.write(val >>> 24);
    }

    private void write8Bytes(long val) {
        write4Bytes((int) (val & 0xFFFFFFFF)); // keep the lowe 32-bits
        write4Bytes((int) (val >>> 32)); // Write the high 32-bits.
    }

    /**
     * Adds 32-bit integer to output.
     */
    public void writeInt(int val) {
        mStorage.write(EVENT_TYPE_INT);
        write4Bytes(val);
    }

    /**
     * Adds 64-bit long to output.
     */
    public void writeLong(long val) {
        mStorage.write(EVENT_TYPE_LONG);
        write8Bytes(val);
    }

    /**
     * Adds a 4-byte floating point value to output.
     */
    public void writeFloat(float val) {
        int v = Float.floatToIntBits(val);
        mStorage.write(EVENT_TYPE_FLOAT);
        write4Bytes(v);
    }

    /**
     * Adds a string to the output.
     */
    public void writeString(String val) {
        mStorage.write(EVENT_TYPE_STRING);
        write4Bytes(val.length());
        byte[] bytes = val.getBytes(StandardCharsets.UTF_8);
        mStorage.write(bytes, 0, bytes.length);
    }

    /**
     * Writes the stored fields to a byte array. Will first write a new-line character to denote
     * END_LIST before writing contents to byte array.
     */
    public void writeToParcel(Parcel out, int flags) {
        mStorage.write(10); // new-line character is same as END_LIST
        out.writeByteArray(mStorage.toByteArray());
    }

    /**
     * Boilerplate for Parcel.
     */
    public int describeContents() {
        return 0;
    }
}
