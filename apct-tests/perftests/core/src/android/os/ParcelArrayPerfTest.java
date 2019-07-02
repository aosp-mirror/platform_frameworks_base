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

import androidx.test.filters.LargeTest;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;

import java.util.Arrays;
import java.util.Collection;

@RunWith(Parameterized.class)
@LargeTest
public class ParcelArrayPerfTest {
    @Rule
    public PerfStatusReporter mPerfStatusReporter = new PerfStatusReporter();

    @Parameters(name = "size={0}")
    public static Collection<Object[]> data() {
        return Arrays.asList(new Object[][] { {1}, {10}, {100}, {1000} });
    }

    private final int mSize;

    private Parcel mWriteParcel;

    private byte[] mByteArray;
    private int[] mIntArray;
    private long[] mLongArray;

    private Parcel mByteParcel;
    private Parcel mIntParcel;
    private Parcel mLongParcel;

    public ParcelArrayPerfTest(int size) {
        mSize = size;
    }

    @Before
    public void setUp() {
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

    @After
    public void tearDown() {
        mWriteParcel.recycle();
        mWriteParcel = null;
    }

    @Test
    public void timeWriteByteArray() {
        final BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            mWriteParcel.setDataPosition(0);
            mWriteParcel.writeByteArray(mByteArray);
        }
    }

    @Test
    public void timeCreateByteArray() {
        final BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            mByteParcel.setDataPosition(0);
            mByteParcel.createByteArray();
        }
    }

    @Test
    public void timeReadByteArray() {
        final BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            mByteParcel.setDataPosition(0);
            mByteParcel.readByteArray(mByteArray);
        }
    }

    @Test
    public void timeWriteIntArray() {
        final BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            mWriteParcel.setDataPosition(0);
            mWriteParcel.writeIntArray(mIntArray);
        }
    }

    @Test
    public void timeCreateIntArray() {
        final BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            mIntParcel.setDataPosition(0);
            mIntParcel.createIntArray();
        }
    }

    @Test
    public void timeReadIntArray() {
        final BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            mIntParcel.setDataPosition(0);
            mIntParcel.readIntArray(mIntArray);
        }
    }

    @Test
    public void timeWriteLongArray() {
        final BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            mWriteParcel.setDataPosition(0);
            mWriteParcel.writeLongArray(mLongArray);
        }
    }

    @Test
    public void timeCreateLongArray() {
        final BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            mLongParcel.setDataPosition(0);
            mLongParcel.createLongArray();
        }
    }

    @Test
    public void timeReadLongArray() {
        final BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            mLongParcel.setDataPosition(0);
            mLongParcel.readLongArray(mLongArray);
        }
    }
}
