/*
 * Copyright (C) 2012 The Android Open Source Project
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

import com.google.caliper.Param;
import com.google.caliper.SimpleBenchmark;

public class ParcelArrayBenchmark extends SimpleBenchmark {

    @Param({ "1", "10", "100", "1000" })
    private int mSize;

    private Parcel mWriteParcel;

    private byte[] mByteArray;
    private int[] mIntArray;
    private long[] mLongArray;

    private Parcel mByteParcel;
    private Parcel mIntParcel;
    private Parcel mLongParcel;

    @Override
    protected void setUp() {
        mWriteParcel = Parcel.obtain();

        mByteArray = new byte[mSize];
        mIntArray = new int[mSize];
        mLongArray = new long[mSize];

        mByteParcel = Parcel.obtain();
        mByteParcel.writeByteArray(mByteArray);
        mIntParcel = Parcel.obtain();
        mIntParcel.writeIntArray(mIntArray);
        mLongParcel = Parcel.obtain();
        mLongParcel.writeLongArray(mLongArray);
    }

    @Override
    protected void tearDown() {
        mWriteParcel.recycle();
        mWriteParcel = null;
    }

    public void timeWriteByteArray(int reps) {
        for (int i = 0; i < reps; i++) {
            mWriteParcel.setDataPosition(0);
            mWriteParcel.writeByteArray(mByteArray);
        }
    }

    public void timeCreateByteArray(int reps) {
        for (int i = 0; i < reps; i++) {
            mByteParcel.setDataPosition(0);
            mByteParcel.createByteArray();
        }
    }

    public void timeReadByteArray(int reps) {
        for (int i = 0; i < reps; i++) {
            mByteParcel.setDataPosition(0);
            mByteParcel.readByteArray(mByteArray);
        }
    }

    public void timeWriteIntArray(int reps) {
        for (int i = 0; i < reps; i++) {
            mWriteParcel.setDataPosition(0);
            mWriteParcel.writeIntArray(mIntArray);
        }
    }

    public void timeCreateIntArray(int reps) {
        for (int i = 0; i < reps; i++) {
            mIntParcel.setDataPosition(0);
            mIntParcel.createIntArray();
        }
    }

    public void timeReadIntArray(int reps) {
        for (int i = 0; i < reps; i++) {
            mIntParcel.setDataPosition(0);
            mIntParcel.readIntArray(mIntArray);
        }
    }

    public void timeWriteLongArray(int reps) {
        for (int i = 0; i < reps; i++) {
            mWriteParcel.setDataPosition(0);
            mWriteParcel.writeLongArray(mLongArray);
        }
    }

    public void timeCreateLongArray(int reps) {
        for (int i = 0; i < reps; i++) {
            mLongParcel.setDataPosition(0);
            mLongParcel.createLongArray();
        }
    }

    public void timeReadLongArray(int reps) {
        for (int i = 0; i < reps; i++) {
            mLongParcel.setDataPosition(0);
            mLongParcel.readLongArray(mLongArray);
        }
    }

}
