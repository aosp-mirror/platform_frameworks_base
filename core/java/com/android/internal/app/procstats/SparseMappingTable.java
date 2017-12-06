/*
 * Copyright (C) 2013 The Android Open Source Project
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

package com.android.internal.app.procstats;

import android.os.Build;
import android.os.Parcel;
import android.util.Slog;
import libcore.util.EmptyArray;

import java.util.ArrayList;
import java.util.Arrays;

import com.android.internal.util.GrowingArrayUtils;

/**
 * Class that contains a set of tables mapping byte ids to long values.
 *
 * This class is used to store the ProcessStats data.  This data happens to be
 * a set of very sparse tables, that is mostly append or overwrite, with infrequent
 * resets of the data.
 *
 * Data is stored as a list of large long[] arrays containing the actual values.  There are a
 * set of Table objects that each contain a small array mapping the byte IDs to a position
 * in the larger arrays.
 *
 * The data itself is either a single long value or a range of long values which are always
 * stored continguously in one of the long arrays. When the caller allocates a slot with
 * getOrAddKey, an int key is returned.  That key can be re-retreived with getKey without
 * allocating the value.  The data can then be set or retrieved with that key.
 */
public class SparseMappingTable {
    private static final String TAG = "SparseMappingTable";

    // How big each array is.
    public static final int ARRAY_SIZE = 4096;

    public static final int INVALID_KEY = 0xffffffff;

    // Where the "type"/"state" part of the data appears in an offset integer.
    private static final int ID_SHIFT = 0;
    private static final int ID_MASK = 0xff;
    // Where the "which array" part of the data appears in an offset integer.
    private static final int ARRAY_SHIFT = 8;
    private static final int ARRAY_MASK = 0xff;
    // Where the "index into array" part of the data appears in an offset integer.
    private static final int INDEX_SHIFT = 16;
    private static final int INDEX_MASK = 0xffff;

    private int mSequence;
    private int mNextIndex;
    private final ArrayList<long[]> mLongs = new ArrayList<long[]>();

    /**
     * A table of data as stored in a SparseMappingTable.
     */
    public static class Table {
        private SparseMappingTable mParent;
        private int mSequence = 1;
        private int[] mTable;
        private int mSize;

        public Table(SparseMappingTable parent) {
            mParent = parent;
            mSequence = parent.mSequence;
        }

        /**
         * Pulls the data from 'copyFrom' and stores it in our own longs table.
         *
         * @param copyFrom   The Table to copy from
         * @param valueCount The number of values to copy for each key
         */
        public void copyFrom(Table copyFrom, int valueCount) {
            mTable = null;
            mSize = 0;

            final int N = copyFrom.getKeyCount();
            for (int i=0; i<N; i++) {
                final int theirKey = copyFrom.getKeyAt(i);
                final long[] theirLongs = copyFrom.mParent.mLongs.get(getArrayFromKey(theirKey));

                final byte id = SparseMappingTable.getIdFromKey(theirKey);

                final int myKey = this.getOrAddKey((byte)id, valueCount);
                final long[] myLongs = mParent.mLongs.get(getArrayFromKey(myKey));

                System.arraycopy(theirLongs, getIndexFromKey(theirKey),
                        myLongs, getIndexFromKey(myKey), valueCount);
            }
        }

        /**
         * Allocates data in the buffer, and stores that key in the mapping for this
         * table.
         *
         * @param id    The id of the item (will be used in making the key)
         * @param count The number of bytes to allocate.  Must be less than
         *              SparseMappingTable.ARRAY_SIZE.
         *
         * @return The 'key' for this data value, which contains both the id itself
         *         and the location in the long arrays that the data is actually stored
         *         but should be considered opaque to the caller.
         */
        public int getOrAddKey(byte id, int count) {
            assertConsistency();

            final int idx = binarySearch(id);
            if (idx >= 0) {
                // Found
                return mTable[idx];
            } else {
                // Not found. Need to allocate it.

                // Get an array with enough space to store 'count' values.
                final ArrayList<long[]> list = mParent.mLongs;
                int whichArray = list.size()-1;
                long[] array = list.get(whichArray);
                if (mParent.mNextIndex + count > array.length) {
                    // if it won't fit then make a new array.
                    array = new long[ARRAY_SIZE];
                    list.add(array);
                    whichArray++;
                    mParent.mNextIndex = 0;
                }

                // The key is a combination of whichArray, which index in that array, and
                // the table value itself, which will be used for lookup
                final int key = (whichArray << ARRAY_SHIFT)
                        | (mParent.mNextIndex << INDEX_SHIFT)
                        | (((int)id) << ID_SHIFT);

                mParent.mNextIndex += count;

                // Store the key in the sparse lookup table for this Table object.
                mTable = GrowingArrayUtils.insert(mTable != null ? mTable : EmptyArray.INT,
                        mSize, ~idx, key);
                mSize++;

                return key;
            }
        }

        /**
         * Looks up a key in the table.
         *
         * @return The key from this table or INVALID_KEY if the id is not found.
         */
        public int getKey(byte id) {
            assertConsistency();

            final int idx = binarySearch(id);
            if (idx >= 0) {
                return mTable[idx];
            } else {
                return INVALID_KEY;
            }
        }

        /**
         * Get the value for the given key and offset from that key.
         *
         * @param key   A key as obtained from getKey or getOrAddKey.
         * @param value The value to set.
         */
        public long getValue(int key) {
            return getValue(key, 0);
        }

        /**
         * Get the value for the given key and offset from that key.
         *
         * @param key   A key as obtained from getKey or getOrAddKey.
         * @param index The offset from that key.  Must be less than the count
         *              provided to getOrAddKey when the space was allocated.
         * @param value The value to set.
         *
         * @return the value, or 0 in case of an error
         */
        public long getValue(int key, int index) {
            assertConsistency();

            try {
                final long[] array = mParent.mLongs.get(getArrayFromKey(key));
                return array[getIndexFromKey(key) + index];
            } catch (IndexOutOfBoundsException ex) {
                logOrThrow("key=0x" + Integer.toHexString(key)
                        + " index=" + index + " -- " + dumpInternalState(), ex);
                return 0;
            }
        }

        /**
         * Set the value for the given id at offset 0 from that id.
         * If the id is not found, return 0 instead.
         *
         * @param id    The id of the item.
         */
        public long getValueForId(byte id) {
            return getValueForId(id, 0);
        }

        /**
         * Set the value for the given id and index offset from that id.
         * If the id is not found, return 0 instead.
         *
         * @param id    The id of the item.
         * @param index The offset from that key.  Must be less than the count
         *              provided to getOrAddKey when the space was allocated.
         */
        public long getValueForId(byte id, int index) {
            assertConsistency();

            final int idx = binarySearch(id);
            if (idx >= 0) {
                final int key = mTable[idx];
                try {
                    final long[] array = mParent.mLongs.get(getArrayFromKey(key));
                    return array[getIndexFromKey(key) + index];
                } catch (IndexOutOfBoundsException ex) {
                    logOrThrow("id=0x" + Integer.toHexString(id) + " idx=" + idx
                            + " key=0x" + Integer.toHexString(key) + " index=" + index
                            + " -- " + dumpInternalState(), ex);
                    return 0;
                }
            } else {
                return 0;
            }
        }

        /**
         * Return the raw storage long[] for the given key.
         */
        public long[] getArrayForKey(int key) {
            assertConsistency();

            return mParent.mLongs.get(getArrayFromKey(key));
        }

        /**
         * Set the value for the given key and offset from that key.
         *
         * @param key   A key as obtained from getKey or getOrAddKey.
         * @param value The value to set.
         */
        public void setValue(int key, long value) {
            setValue(key, 0, value);
        }

        /**
         * Set the value for the given key and offset from that key.
         *
         * @param key   A key as obtained from getKey or getOrAddKey.
         * @param index The offset from that key.  Must be less than the count
         *              provided to getOrAddKey when the space was allocated.
         * @param value The value to set.
         */
        public void setValue(int key, int index, long value) {
            assertConsistency();

            if (value < 0) {
                logOrThrow("can't store negative values"
                        + " key=0x" + Integer.toHexString(key)
                        + " index=" + index + " value=" + value
                        + " -- " + dumpInternalState());
                return;
            }

            try {
                final long[] array = mParent.mLongs.get(getArrayFromKey(key));
                array[getIndexFromKey(key) + index] = value;
            } catch (IndexOutOfBoundsException ex) {
                logOrThrow("key=0x" + Integer.toHexString(key)
                        + " index=" + index + " value=" + value
                        + " -- " + dumpInternalState(), ex);
                return;
            }
        }

        /**
         * Clear out the table, and reset the sequence numbers so future writes
         * without allocations will assert.
         */
        public void resetTable() {
            // Clear out our table.
            mTable = null;
            mSize = 0;

            // Reset our sequence number.  This will make all read/write calls
            // start to fail, and then when we re-allocate it will be re-synced
            // to that of mParent.
            mSequence = mParent.mSequence;
        }

        /**
         * Write the keys stored in the table to the parcel. The parent must
         * be separately written. Does not save the actual data.
         */
        public void writeToParcel(Parcel out) {
            out.writeInt(mSequence);
            out.writeInt(mSize);
            for (int i=0; i<mSize; i++) {
                out.writeInt(mTable[i]);
            }
        }

        /**
         * Read the keys from the parcel. The parent (with its long array) must
         * have been previously initialized.
         */
        public boolean readFromParcel(Parcel in) {
            // Read the state
            mSequence = in.readInt();
            mSize = in.readInt();
            if (mSize != 0) {
                mTable = new int[mSize];
                for (int i=0; i<mSize; i++) {
                    mTable[i] = in.readInt();
                }
            } else {
                mTable = null;
            }

            // Make sure we're all healthy
            if (validateKeys(true)) {
                return true;
            } else {
                // Clear it out
                mSize = 0;
                mTable = null;
                return false;
            }
        }

        /**
         * Return the number of keys that have been added to this Table.
         */
        public int getKeyCount() {
            return mSize;
        }

        /**
         * Get the key at the given index in our table.
         */
        public int getKeyAt(int i) {
            return mTable[i];
        }

        /**
         * Throw an exception if one of a variety of internal consistency checks fails.
         */
        private void assertConsistency() {
            // Something with this checking isn't working and is triggering
            // more problems than it's helping to debug.
            //   Original bug: b/27045736
            //   New bug: b/27960286
            if (false) {
                // Assert that our sequence number matches mParent's.  If it isn't that means
                // we have been reset and our.  If our sequence is UNITIALIZED_SEQUENCE, then 
                // it's possible that everything is working fine and we just haven't been
                // written to since the last resetTable().
                if (mSequence != mParent.mSequence) {
                    if (mSequence < mParent.mSequence) {
                        logOrThrow("Sequence mismatch. SparseMappingTable.reset()"
                                + " called but not Table.resetTable() -- "
                                + dumpInternalState());
                        return;
                    } else if (mSequence > mParent.mSequence) {
                        logOrThrow("Sequence mismatch. Table.resetTable()"
                                + " called but not SparseMappingTable.reset() -- "
                                + dumpInternalState());
                        return;
                    }
                }
            }
        }

        /**
         * Finds the 'id' inside the array of length size (physical size of the array
         * is not used).
         *
         * @return The index of the array, or the bitwise not (~index) of where it
         * would go if you wanted to insert 'id' into the array.
         */
        private int binarySearch(byte id) {
            int lo = 0;
            int hi = mSize - 1;

            while (lo <= hi) {
                int mid = (lo + hi) >>> 1;
                byte midId = (byte)((mTable[mid] >> ID_SHIFT) & ID_MASK);

                if (midId < id) {
                    lo = mid + 1;
                } else if (midId > id) {
                    hi = mid - 1;
                } else {
                    return mid;  // id found
                }
            }
            return ~lo;  // id not present
        }

        /**
         * Check that all the keys are valid locations in the long arrays.
         *
         * If any aren't, log it and return false. Else return true.
         */
        private boolean validateKeys(boolean log) {
            ArrayList<long[]> longs = mParent.mLongs;
            final int longsSize = longs.size();

            final int N = mSize;
            for (int i=0; i<N; i++) {
                final int key = mTable[i];
                final int arrayIndex = getArrayFromKey(key);
                final int index = getIndexFromKey(key);
                if (arrayIndex >= longsSize || index >= longs.get(arrayIndex).length) {
                    if (log) {
                        Slog.w(TAG, "Invalid stats at index " + i + " -- " + dumpInternalState());
                    }
                    return false;
                }
            }

            return true;
        }

        public String dumpInternalState() {
            StringBuilder sb = new StringBuilder();
            sb.append("SparseMappingTable.Table{mSequence=");
            sb.append(mSequence);
            sb.append(" mParent.mSequence=");
            sb.append(mParent.mSequence);
            sb.append(" mParent.mLongs.size()=");
            sb.append(mParent.mLongs.size());
            sb.append(" mSize=");
            sb.append(mSize);
            sb.append(" mTable=");
            if (mTable == null) {
                sb.append("null");
            } else {
                final int N = mTable.length;
                sb.append('[');
                for (int i=0; i<N; i++) {
                    final int key = mTable[i];
                    sb.append("0x");
                    sb.append(Integer.toHexString((key >> ID_SHIFT) & ID_MASK));
                    sb.append("/0x");
                    sb.append(Integer.toHexString((key >> ARRAY_SHIFT) & ARRAY_MASK));
                    sb.append("/0x");
                    sb.append(Integer.toHexString((key >> INDEX_SHIFT) & INDEX_MASK));
                    if (i != N-1) {
                        sb.append(", ");
                    }
                }
                sb.append(']');
            }
            sb.append(" clazz=");
            sb.append(getClass().getName());
            sb.append('}');

            return sb.toString();
        }
    }

    public SparseMappingTable() {
        mLongs.add(new long[ARRAY_SIZE]);
    }

    /**
     * Wipe out all the data.
     */
    public void reset() {
        // Clear out mLongs, and prime it with a new array of data
        mLongs.clear();
        mLongs.add(new long[ARRAY_SIZE]);
        mNextIndex = 0;

        // Increment out sequence counter, because all of the tables will
        // now be out of sync with the data.
        mSequence++;
    }

    /**
     * Write the data arrays to the parcel.
     */
    public void writeToParcel(Parcel out) {
        out.writeInt(mSequence);
        out.writeInt(mNextIndex);
        final int N = mLongs.size();
        out.writeInt(N);
        for (int i=0; i<N-1; i++) {
            final long[] array = mLongs.get(i);
            out.writeInt(array.length);
            writeCompactedLongArray(out, array, array.length);
        }
        // save less for the last one. upon re-loading they'll just start a new array.
        final long[] lastLongs = mLongs.get(N-1);
        out.writeInt(mNextIndex);
        writeCompactedLongArray(out, lastLongs, mNextIndex);
    }

    /**
     * Read the data arrays from the parcel.
     */
    public void readFromParcel(Parcel in) {
        mSequence = in.readInt();
        mNextIndex = in.readInt();

        mLongs.clear();
        final int N = in.readInt();
        for (int i=0; i<N; i++) {
            final int size = in.readInt();
            final long[] array = new long[size];
            readCompactedLongArray(in, array, size);
            mLongs.add(array);
        }
    }

    /**
     * Return a string for debugging.
     */
    public String dumpInternalState(boolean includeData) {
        final StringBuilder sb = new StringBuilder();
        sb.append("SparseMappingTable{");
        sb.append("mSequence=");
        sb.append(mSequence);
        sb.append(" mNextIndex=");
        sb.append(mNextIndex);
        sb.append(" mLongs.size=");
        final int N = mLongs.size();
        sb.append(N);
        sb.append("\n");
        if (includeData) {
            for (int i=0; i<N; i++) {
                final long[] array = mLongs.get(i);
                for (int j=0; j<array.length; j++) {
                    if (i == N-1 && j == mNextIndex) {
                        break;
                    }
                    sb.append(String.format(" %4d %d 0x%016x %-19d\n", i, j, array[j], array[j]));
                }
            }
        }
        sb.append("}");
        return sb.toString();
    }

    /**
     * Write the long array to the parcel in a compacted form.  Does not allow negative
     * values in the array.
     */
    private static void writeCompactedLongArray(Parcel out, long[] array, int num) {
        for (int i=0; i<num; i++) {
            long val = array[i];
            if (val < 0) {
                Slog.w(TAG, "Time val negative: " + val);
                val = 0;
            }
            if (val <= Integer.MAX_VALUE) {
                out.writeInt((int)val);
            } else {
                int top = ~((int)((val>>32)&0x7fffffff));
                int bottom = (int)(val&0x0ffffffffL);
                out.writeInt(top);
                out.writeInt(bottom);
            }
        }
    }

    /**
     * Read the compacted array into the long[].
     */
    private static void readCompactedLongArray(Parcel in, long[] array, int num) {
        final int alen = array.length;
        if (num > alen) {
            logOrThrow("bad array lengths: got " + num + " array is " + alen);
            return;
        }
        int i;
        for (i=0; i<num; i++) {
            int val = in.readInt();
            if (val >= 0) {
                array[i] = val;
            } else {
                int bottom = in.readInt();
                array[i] = (((long)~val)<<32) | bottom;
            }
        }
        while (i < alen) {
            array[i] = 0;
            i++;
        }
    }

    /**
     * Extract the id from a key.
     */
    public static byte getIdFromKey(int key) {
        return (byte)((key >> ID_SHIFT) & ID_MASK);
    }

    /**
     * Gets the index of the array in the list of arrays.
     *
     * Not to be confused with getIndexFromKey.
     */
    public static int getArrayFromKey(int key) {
        return (key >> ARRAY_SHIFT) & ARRAY_MASK;
    }

    /**
     * Gets the index of a value in a long[].
     *
     * Not to be confused with getArrayFromKey.
     */
    public static int getIndexFromKey(int key) {
        return (key >> INDEX_SHIFT) & INDEX_MASK;
    }

    /**
     * Do a Slog.wtf or throw an exception (thereby crashing the system process if
     * this is a debug build.)
     */
    private static void logOrThrow(String message) {
        logOrThrow(message, new RuntimeException("Stack trace"));
    }

    /**
     * Do a Slog.wtf or throw an exception (thereby crashing the system process if
     * this is an eng build.)
     */
    private static void logOrThrow(String message, Throwable th) {
        Slog.e(TAG, message, th);
        if (Build.IS_ENG) {
            throw new RuntimeException(message, th);
        }
    }
}
