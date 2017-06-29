/*
 * Copyright (C) 2016 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package android.os;

import android.perftests.utils.BenchmarkState;
import android.perftests.utils.PerfStatusReporter;
import android.support.test.filters.LargeTest;
import android.support.test.runner.AndroidJUnit4;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
@LargeTest
public class ParcelPerfTest {
    @Rule
    public PerfStatusReporter mPerfStatusReporter = new PerfStatusReporter();

    private Parcel mParcel;

    @Before
    public void setUp() {
        mParcel = Parcel.obtain();
        mParcel.setDataPosition(0);
        mParcel.setDataCapacity(8);
    }

    @After
    public void tearDown() {
        mParcel.recycle();
        mParcel = null;
    }

    @Test
    public void timeSetDataPosition() {
        final BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            mParcel.setDataPosition(0);
        }
    }

    @Test
    public void timeGetDataPosition() {
        final BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            mParcel.dataPosition();
        }
    }

    @Test
    public void timeSetDataSize() {
        final BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            mParcel.setDataSize(0);
        }
    }

    @Test
    public void timeGetDataSize() {
        final BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            mParcel.dataSize();
        }
    }

    @Test
    public void timeSetDataCapacity() {
        final BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            mParcel.setDataCapacity(0);
        }
    }

    @Test
    public void timeGetDataCapacity() {
        final BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            mParcel.dataCapacity();
        }
    }

    @Test
    public void timeWriteByte() {
        final BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        final byte val = 0xF;
        while (state.keepRunning()) {
            mParcel.setDataPosition(0);
            mParcel.writeByte(val);
        }
    }

    @Test
    public void timeReadByte() {
        final BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            mParcel.setDataPosition(0);
            mParcel.readByte();
        }
    }

    @Test
    public void timeWriteInt() {
        final BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        final int val = 0xF;
        while (state.keepRunning()) {
            mParcel.setDataPosition(0);
            mParcel.writeInt(val);
        }
    }

    @Test
    public void timeReadInt() {
        final BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            mParcel.setDataPosition(0);
            mParcel.readInt();
        }
    }

    @Test
    public void timeWriteLong() {
        final BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        final long val = 0xF;
        while (state.keepRunning()) {
            mParcel.setDataPosition(0);
            mParcel.writeLong(val);
        }
    }

    @Test
    public void timeReadLong() {
        final BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            mParcel.setDataPosition(0);
            mParcel.readLong();
        }
    }

    @Test
    public void timeObtainRecycle() {
        // Use up the pooled instances.
        // A lot bigger than the actual size but in case someone increased it.
        final int POOL_SIZE = 100;
        for (int i = 0; i < POOL_SIZE; i++) {
            Parcel.obtain();
        }

        final BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            Parcel.obtain().recycle();
        }
    }
}
