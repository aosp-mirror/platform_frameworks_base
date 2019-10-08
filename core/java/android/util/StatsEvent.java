/*
 * Copyright (C) 2019 The Android Open Source Project
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

package android.util;

import static java.nio.charset.StandardCharsets.UTF_8;

import android.annotation.NonNull;
import android.annotation.Nullable;

import com.android.internal.annotations.GuardedBy;

/**
 * StatsEvent builds and stores the buffer sent over the statsd socket.
 * This class defines and encapsulates the socket protocol.
 * @hide
 **/
public final class StatsEvent implements AutoCloseable {
    private static final int POS_NUM_ELEMENTS = 1;
    private static final int POS_TIMESTAMP = POS_NUM_ELEMENTS + 1;

    private static final int LOGGER_ENTRY_MAX_PAYLOAD = 4068;

    // Max payload size is 4 KB less 4 bytes which are reserved for statsEventTag.
    // See android_util_StatsLog.cpp.
    private static final int MAX_EVENT_PAYLOAD = LOGGER_ENTRY_MAX_PAYLOAD - 4;

    private static final byte INT_TYPE = 0;
    private static final byte LONG_TYPE = 1;
    private static final byte STRING_TYPE = 2;
    private static final byte LIST_TYPE = 3;
    private static final byte FLOAT_TYPE = 4;

    private static final int INT_TYPE_SIZE = 5;
    private static final int FLOAT_TYPE_SIZE = 5;
    private static final int LONG_TYPE_SIZE = 9;

    private static final int STRING_TYPE_OVERHEAD = 5;
    private static final int LIST_TYPE_OVERHEAD = 2;

    public static final int SUCCESS = 0;
    public static final int ERROR_BUFFER_LIMIT_EXCEEDED = -1;
    public static final int ERROR_NO_TIMESTAMP = -2;
    public static final int ERROR_TIMESTAMP_ALREADY_WRITTEN = -3;
    public static final int ERROR_NO_ATOM_ID = -4;
    public static final int ERROR_ATOM_ID_ALREADY_WRITTEN = -5;
    public static final int ERROR_UID_TAG_COUNT_MISMATCH = -6;

    private static Object sLock = new Object();

    @GuardedBy("sLock")
    private static StatsEvent sPool;

    private final byte[] mBuffer = new byte[MAX_EVENT_PAYLOAD];
    private int mPos;
    private int mNumElements;
    private int mAtomId;

    private StatsEvent() {
        // Write LIST_TYPE to buffer
        mBuffer[0] = LIST_TYPE;
        reset();
    }

    private void reset() {
        // Reset state.
        mPos = POS_TIMESTAMP;
        mNumElements = 0;
        mAtomId = 0;
    }

    /**
     * Returns a StatsEvent object from the pool.
     **/
    @NonNull
    public static StatsEvent obtain() {
        final StatsEvent statsEvent;
        synchronized (sLock) {
            statsEvent = null == sPool ? new StatsEvent() : sPool;
            sPool = null;
        }
        statsEvent.reset();
        return statsEvent;
    }

    @Override
    public void close() {
        synchronized (sLock) {
            if (null == sPool) {
                sPool = this;
            }
        }
    }

    /**
     * Writes the event timestamp to the buffer.
     **/
    public int writeTimestampNs(final long timestampNs) {
        if (hasTimestamp()) {
            return ERROR_TIMESTAMP_ALREADY_WRITTEN;
        }
        return writeLong(timestampNs);
    }

    private boolean hasTimestamp() {
        return mPos > POS_TIMESTAMP;
    }

    private boolean hasAtomId() {
        return mAtomId != 0;
    }

    /**
     * Writes the atom id to the buffer.
     **/
    public int writeAtomId(final int atomId) {
        if (!hasTimestamp()) {
            return ERROR_NO_TIMESTAMP;
        } else if (hasAtomId()) {
            return ERROR_ATOM_ID_ALREADY_WRITTEN;
        }

        final int writeResult = writeInt(atomId);
        if (SUCCESS == writeResult) {
            mAtomId = atomId;
        }
        return writeResult;
    }

    /**
     * Appends the given int to the StatsEvent buffer.
     **/
    public int writeInt(final int value) {
        if (!hasTimestamp()) {
            return ERROR_NO_TIMESTAMP;
        } else if (!hasAtomId()) {
            return ERROR_NO_ATOM_ID;
        } else if (mPos + INT_TYPE_SIZE > MAX_EVENT_PAYLOAD) {
            return ERROR_BUFFER_LIMIT_EXCEEDED;
        }

        mBuffer[mPos] = INT_TYPE;
        copyInt(mBuffer, mPos + 1, value);
        mPos += INT_TYPE_SIZE;
        mNumElements++;
        return SUCCESS;
    }

    /**
     * Appends the given long to the StatsEvent buffer.
     **/
    public int writeLong(final long value) {
        if (!hasTimestamp()) {
            return ERROR_NO_TIMESTAMP;
        } else if (!hasAtomId()) {
            return ERROR_NO_ATOM_ID;
        } else if (mPos + LONG_TYPE_SIZE > MAX_EVENT_PAYLOAD) {
            return ERROR_BUFFER_LIMIT_EXCEEDED;
        }

        mBuffer[mPos] = LONG_TYPE;
        copyLong(mBuffer, mPos + 1, value);
        mPos += LONG_TYPE_SIZE;
        mNumElements++;
        return SUCCESS;
    }

    /**
     * Appends the given float to the StatsEvent buffer.
     **/
    public int writeFloat(final float value) {
        if (!hasTimestamp()) {
            return ERROR_NO_TIMESTAMP;
        } else if (!hasAtomId()) {
            return ERROR_NO_ATOM_ID;
        } else if (mPos + FLOAT_TYPE_SIZE > MAX_EVENT_PAYLOAD) {
            return ERROR_BUFFER_LIMIT_EXCEEDED;
        }

        mBuffer[mPos] = FLOAT_TYPE;
        copyInt(mBuffer, mPos + 1, Float.floatToIntBits(value));
        mPos += FLOAT_TYPE_SIZE;
        mNumElements++;
        return SUCCESS;
    }

    /**
     * Appends the given boolean to the StatsEvent buffer.
     **/
    public int writeBoolean(final boolean value) {
        return writeInt(value ? 1 : 0);
    }

    /**
     * Appends the given byte array to the StatsEvent buffer.
     **/
    public int writeByteArray(@NonNull final byte[] value) {
        if (!hasTimestamp()) {
            return ERROR_NO_TIMESTAMP;
        } else if (!hasAtomId()) {
            return ERROR_NO_ATOM_ID;
        } else if (mPos + STRING_TYPE_OVERHEAD + value.length > MAX_EVENT_PAYLOAD) {
            return ERROR_BUFFER_LIMIT_EXCEEDED;
        }

        mBuffer[mPos] = STRING_TYPE;
        copyInt(mBuffer, mPos + 1, value.length);
        System.arraycopy(value, 0, mBuffer, mPos + STRING_TYPE_OVERHEAD, value.length);
        mPos += STRING_TYPE_OVERHEAD + value.length;
        mNumElements++;
        return SUCCESS;
    }

    /**
     * Appends the given String to the StatsEvent buffer.
     **/
    public int writeString(@NonNull final String value) {
        final byte[] valueBytes = stringToBytes(value);
        return writeByteArray(valueBytes);
    }

    /**
     * Appends the AttributionNode specified as array of uids and array of tags.
     **/
    public int writeAttributionNode(@NonNull final int[] uids, @NonNull final String[] tags) {
        if (!hasTimestamp()) {
            return ERROR_NO_TIMESTAMP;
        } else if (!hasAtomId()) {
            return ERROR_NO_ATOM_ID;
        } else if (mPos + LIST_TYPE_OVERHEAD > MAX_EVENT_PAYLOAD) {
            return ERROR_BUFFER_LIMIT_EXCEEDED;
        }

        final int numTags = tags.length;
        final int numUids = uids.length;
        if (numTags != numUids) {
            return ERROR_UID_TAG_COUNT_MISMATCH;
        }

        int pos = mPos;
        mBuffer[pos] = LIST_TYPE;
        mBuffer[pos + 1] = (byte) numTags;
        pos += LIST_TYPE_OVERHEAD;
        for (int i = 0; i < numTags; i++) {
            final byte[] tagBytes = stringToBytes(tags[i]);

            if (pos + LIST_TYPE_OVERHEAD + INT_TYPE_SIZE
                    + STRING_TYPE_OVERHEAD + tagBytes.length > MAX_EVENT_PAYLOAD) {
                return ERROR_BUFFER_LIMIT_EXCEEDED;
            }

            mBuffer[pos] = LIST_TYPE;
            mBuffer[pos + 1] = 2;
            pos += LIST_TYPE_OVERHEAD;
            mBuffer[pos] = INT_TYPE;
            copyInt(mBuffer, pos + 1, uids[i]);
            pos += INT_TYPE_SIZE;
            mBuffer[pos] = STRING_TYPE;
            copyInt(mBuffer, pos + 1, tagBytes.length);
            System.arraycopy(tagBytes, 0, mBuffer, pos + STRING_TYPE_OVERHEAD, tagBytes.length);
            pos += STRING_TYPE_OVERHEAD + tagBytes.length;
        }
        mPos = pos;
        mNumElements++;
        return SUCCESS;
    }

    /**
     * Returns the byte array containing data in the statsd socket format.
     * @hide
     **/
    @NonNull
    public byte[] getBuffer() {
        // Encode number of elements in the buffer.
        mBuffer[POS_NUM_ELEMENTS] = (byte) mNumElements;
        return mBuffer;
    }

    /**
     * Returns number of bytes used by the buffer.
     * @hide
     **/
    public int size() {
        return mPos;
    }

    /**
     * Getter for atom id.
     * @hide
     **/
    public int getAtomId() {
        return mAtomId;
    }

    @NonNull
    private static byte[] stringToBytes(@Nullable final String value) {
        return (null == value ? "" : value).getBytes(UTF_8);
    }

    // Helper methods for copying primitives
    private static void copyInt(@NonNull byte[] buff, int pos, int value) {
        buff[pos] = (byte) (value);
        buff[pos + 1] = (byte) (value >> 8);
        buff[pos + 2] = (byte) (value >> 16);
        buff[pos + 3] = (byte) (value >> 24);
    }

    private static void copyLong(@NonNull byte[] buff, int pos, long value) {
        buff[pos] = (byte) (value);
        buff[pos + 1] = (byte) (value >> 8);
        buff[pos + 2] = (byte) (value >> 16);
        buff[pos + 3] = (byte) (value >> 24);
        buff[pos + 4] = (byte) (value >> 32);
        buff[pos + 5] = (byte) (value >> 40);
        buff[pos + 6] = (byte) (value >> 48);
        buff[pos + 7] = (byte) (value >> 56);
    }
}
