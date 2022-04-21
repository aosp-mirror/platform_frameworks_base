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

package android.libcore;

import android.perftests.utils.BenchmarkState;
import android.perftests.utils.PerfStatusReporter;
import android.test.suitebuilder.annotation.LargeTest;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.util.Arrays;
import java.util.Collection;

@RunWith(Parameterized.class)
@LargeTest
public class DeepArrayOpsPerfTest {
    @Rule public PerfStatusReporter mPerfStatusReporter = new PerfStatusReporter();

    private Object[] mArray;
    private Object[] mArray2;

    @Parameterized.Parameter(0)
    public int mArrayLength;

    @Parameterized.Parameters(name = "mArrayLength({0})")
    public static Collection<Object[]> data() {
        return Arrays.asList(new Object[][] {{1}, {4}, {16}, {32}, {2048}});
    }

    @Before
    public void setUp() throws Exception {
        mArray = new Object[mArrayLength * 14];
        mArray2 = new Object[mArrayLength * 14];
        for (int i = 0; i < mArrayLength; i += 14) {
            mArray[i] = new IntWrapper(i);
            mArray2[i] = new IntWrapper(i);

            mArray[i + 1] = new16ElementObjectmArray();
            mArray2[i + 1] = new16ElementObjectmArray();

            mArray[i + 2] = new boolean[16];
            mArray2[i + 2] = new boolean[16];

            mArray[i + 3] = new byte[16];
            mArray2[i + 3] = new byte[16];

            mArray[i + 4] = new char[16];
            mArray2[i + 4] = new char[16];

            mArray[i + 5] = new short[16];
            mArray2[i + 5] = new short[16];

            mArray[i + 6] = new float[16];
            mArray2[i + 6] = new float[16];

            mArray[i + 7] = new long[16];
            mArray2[i + 7] = new long[16];

            mArray[i + 8] = new int[16];
            mArray2[i + 8] = new int[16];

            mArray[i + 9] = new double[16];
            mArray2[i + 9] = new double[16];

            // SubmArray types are concrete objects.
            mArray[i + 10] = new16ElementArray(String.class, String.class);
            mArray2[i + 10] = new16ElementArray(String.class, String.class);

            mArray[i + 11] = new16ElementArray(Integer.class, Integer.class);
            mArray2[i + 11] = new16ElementArray(Integer.class, Integer.class);

            // SubmArray types is an interface.
            mArray[i + 12] = new16ElementArray(CharSequence.class, String.class);
            mArray2[i + 12] = new16ElementArray(CharSequence.class, String.class);

            mArray[i + 13] = null;
            mArray2[i + 13] = null;
        }
    }

    @Test
    public void deepHashCode() {
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            Arrays.deepHashCode(mArray);
        }
    }

    @Test
    public void deepEquals() {
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            Arrays.deepEquals(mArray, mArray2);
        }
    }

    private static Object[] new16ElementObjectmArray() {
        Object[] array = new Object[16];
        for (int i = 0; i < 16; ++i) {
            array[i] = new IntWrapper(i);
        }

        return array;
    }

    @SuppressWarnings("unchecked")
    private static <T, V> T[] new16ElementArray(Class<T> mArrayType, Class<V> type)
            throws Exception {
        T[] array = (T[]) Array.newInstance(type, 16);
        if (!mArrayType.isAssignableFrom(type)) {
            throw new IllegalArgumentException(mArrayType + " is not assignable from " + type);
        }

        Constructor<V> constructor = type.getDeclaredConstructor(String.class);
        for (int i = 0; i < 16; ++i) {
            array[i] = (T) constructor.newInstance(String.valueOf(i + 1000));
        }

        return array;
    }

    /**
     * A class that provides very basic equals() and hashCode() operations and doesn't resort to
     * memoization tricks like {@link java.lang.Integer}.
     *
     * <p>Useful for providing equal objects that aren't the same (a.equals(b) but a != b).
     */
    public static final class IntWrapper {
        private final int mWrapped;

        public IntWrapper(int wrap) {
            mWrapped = wrap;
        }

        @Override
        public int hashCode() {
            return mWrapped;
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof IntWrapper)) {
                return false;
            }

            return ((IntWrapper) o).mWrapped == this.mWrapped;
        }
    }
}
