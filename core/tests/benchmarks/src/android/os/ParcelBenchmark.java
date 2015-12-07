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

import com.google.caliper.AfterExperiment;
import com.google.caliper.BeforeExperiment;

public class ParcelBenchmark {

    private Parcel mParcel;

    @BeforeExperiment
    protected void setUp() {
        mParcel = Parcel.obtain();
    }

    @AfterExperiment
    protected void tearDown() {
        mParcel.recycle();
        mParcel = null;
    }

    public void timeWriteByte(int reps) {
        final byte val = 0xF;
        for (int i = 0; i < reps; i++) {
            mParcel.writeByte(val);
        }
    }

    public void timeReadByte(int reps) {
        mParcel.setDataCapacity(reps);
        for (int i = 0; i < reps; i++) {
            mParcel.readByte();
        }
    }

    public void timeWriteInt(int reps) {
        final int val = 0xF;
        for (int i = 0; i < reps; i++) {
            mParcel.writeInt(val);
        }
    }

    public void timeReadInt(int reps) {
        mParcel.setDataCapacity(reps << 2);
        for (int i = 0; i < reps; i++) {
            mParcel.readInt();
        }
    }

    public void timeWriteLong(int reps) {
        final long val = 0xF;
        for (int i = 0; i < reps; i++) {
            mParcel.writeLong(val);
        }
    }

    public void timeReadLong(int reps) {
        mParcel.setDataCapacity(reps << 3);
        for (int i = 0; i < reps; i++) {
            mParcel.readLong();
        }
    }
}
