/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.server.vcn.util;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.os.PersistableBundle;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;

@RunWith(AndroidJUnit4.class)
@SmallTest
public class PersistableBundleUtilsTest {
    private static final String TEST_KEY = "testKey";
    private static final String TEST_STRING_PREFIX = "testString";
    private static final int[] TEST_INT_ARRAY = new int[] {0, 1, 2, 3, 4};

    private static final int NUM_COLLECTION_ENTRIES = 10;

    private static class TestKey {
        private static final String TEST_INTEGER_KEY =
                "mTestInteger"; // Purposely colliding with keys of test class to ensure namespacing
        private final int mTestInteger;

        TestKey(int testInteger) {
            mTestInteger = testInteger;
        }

        TestKey(PersistableBundle in) {
            mTestInteger = in.getInt(TEST_INTEGER_KEY);
        }

        public PersistableBundle toPersistableBundle() {
            final PersistableBundle result = new PersistableBundle();

            result.putInt(TEST_INTEGER_KEY, mTestInteger);

            return result;
        }

        @Override
        public int hashCode() {
            return Objects.hash(mTestInteger);
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof TestKey)) {
                return false;
            }

            final TestKey other = (TestKey) o;
            return mTestInteger == other.mTestInteger;
        }
    }

    private static class TestClass {
        private static final String TEST_INTEGER_KEY = "mTestInteger";
        private final int mTestInteger;

        private static final String TEST_INT_ARRAY_KEY = "mTestIntArray";
        private final int[] mTestIntArray;

        private static final String TEST_STRING_KEY = "mTestString";
        private final String mTestString;

        private static final String TEST_PERSISTABLE_BUNDLE_KEY = "mTestPersistableBundle";
        private final PersistableBundle mTestPersistableBundle;

        TestClass(
                int testInteger,
                int[] testIntArray,
                String testString,
                PersistableBundle testPersistableBundle) {
            mTestInteger = testInteger;
            mTestIntArray = testIntArray;
            mTestString = testString;
            mTestPersistableBundle = testPersistableBundle;
        }

        TestClass(PersistableBundle in) {
            mTestInteger = in.getInt(TEST_INTEGER_KEY);
            mTestIntArray = in.getIntArray(TEST_INT_ARRAY_KEY);
            mTestString = in.getString(TEST_STRING_KEY);
            mTestPersistableBundle = in.getPersistableBundle(TEST_PERSISTABLE_BUNDLE_KEY);
        }

        public PersistableBundle toPersistableBundle() {
            final PersistableBundle result = new PersistableBundle();

            result.putInt(TEST_INTEGER_KEY, mTestInteger);
            result.putIntArray(TEST_INT_ARRAY_KEY, mTestIntArray);
            result.putString(TEST_STRING_KEY, mTestString);
            result.putPersistableBundle(TEST_PERSISTABLE_BUNDLE_KEY, mTestPersistableBundle);

            return result;
        }

        @Override
        public int hashCode() {
            return Objects.hash(
                    mTestInteger,
                    Arrays.hashCode(mTestIntArray),
                    mTestString,
                    mTestPersistableBundle);
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof TestClass)) {
                return false;
            }

            final TestClass other = (TestClass) o;

            // TODO: Add a proper equals() to PersistableBundle. But in the meantime, force
            // TODO: unparcelling in order to allow test comparison.
            if (mTestPersistableBundle.size() != other.mTestPersistableBundle.size()) {
                return false;
            }

            return mTestInteger == other.mTestInteger
                    && Arrays.equals(mTestIntArray, other.mTestIntArray)
                    && mTestString.equals(other.mTestString)
                    && mTestPersistableBundle.kindofEquals(other.mTestPersistableBundle);
        }
    }

    @Test
    public void testListConversionLossless() throws Exception {
        final List<TestClass> sourceList = new ArrayList<>();
        for (int i = 0; i < NUM_COLLECTION_ENTRIES; i++) {
            final PersistableBundle innerBundle = new PersistableBundle();
            innerBundle.putInt(TEST_KEY, i);

            sourceList.add(new TestClass(i, TEST_INT_ARRAY, TEST_STRING_PREFIX + i, innerBundle));
        }

        final PersistableBundle bundled =
                PersistableBundleUtils.fromList(sourceList, TestClass::toPersistableBundle);
        final List<TestClass> resultList = PersistableBundleUtils.toList(bundled, TestClass::new);

        assertEquals(sourceList, resultList);
    }

    @Test
    public void testMapConversionLossless() throws Exception {
        final LinkedHashMap<TestKey, TestClass> sourceMap = new LinkedHashMap<>();
        for (int i = 0; i < NUM_COLLECTION_ENTRIES; i++) {
            final TestKey key = new TestKey(i * i);

            final PersistableBundle innerBundle = new PersistableBundle();
            innerBundle.putInt(TEST_KEY, i);
            final TestClass value =
                    new TestClass(i, TEST_INT_ARRAY, TEST_STRING_PREFIX + i, innerBundle);

            sourceMap.put(key, value);
        }

        final PersistableBundle bundled =
                PersistableBundleUtils.fromMap(
                        sourceMap, TestKey::toPersistableBundle, TestClass::toPersistableBundle);
        final LinkedHashMap<TestKey, TestClass> resultList =
                PersistableBundleUtils.toMap(bundled, TestKey::new, TestClass::new);

        assertEquals(sourceMap, resultList);
    }

    @Test
    public void testByteArrayConversionLossless() {
        final byte[] byteArray = "testByteArrayConversionLossless".getBytes();

        PersistableBundle bundle = PersistableBundleUtils.fromByteArray(byteArray);
        byte[] result = PersistableBundleUtils.toByteArray(bundle);

        assertArrayEquals(byteArray, result);
    }

    @Test
    public void testIntegerConversionLossless() throws Exception {
        final int testInt = 1;
        final PersistableBundle integerBundle =
                PersistableBundleUtils.INTEGER_SERIALIZER.toPersistableBundle(testInt);
        final int result =
                PersistableBundleUtils.INTEGER_DESERIALIZER.fromPersistableBundle(integerBundle);

        assertEquals(testInt, result);
    }

    private PersistableBundle getTestBundle() {
        final PersistableBundle bundle = new PersistableBundle();

        bundle.putBoolean(TEST_KEY + "Boolean", true);
        bundle.putBooleanArray(TEST_KEY + "BooleanArray", new boolean[] {true, false});
        bundle.putDouble(TEST_KEY + "Double", 0.1);
        bundle.putDoubleArray(TEST_KEY + "DoubleArray", new double[] {0.1, 0.2, 0.3});
        bundle.putInt(TEST_KEY + "Int", 1);
        bundle.putIntArray(TEST_KEY + "IntArray", new int[] {1, 2});
        bundle.putLong(TEST_KEY + "Long", 5L);
        bundle.putLongArray(TEST_KEY + "LongArray", new long[] {0L, -1L, -2L});
        bundle.putString(TEST_KEY + "String", "TEST");
        bundle.putStringArray(TEST_KEY + "StringArray", new String[] {"foo", "bar", "bas"});
        bundle.putPersistableBundle(
                TEST_KEY + "PersistableBundle",
                new TestClass(1, TEST_INT_ARRAY, TEST_STRING_PREFIX, new PersistableBundle())
                        .toPersistableBundle());

        return bundle;
    }

    @Test
    public void testMinimizeBundle() throws Exception {
        final String[] minimizedKeys =
                new String[] {
                    TEST_KEY + "Boolean",
                    TEST_KEY + "BooleanArray",
                    TEST_KEY + "Double",
                    TEST_KEY + "DoubleArray",
                    TEST_KEY + "Int",
                    TEST_KEY + "IntArray",
                    TEST_KEY + "Long",
                    TEST_KEY + "LongArray",
                    TEST_KEY + "String",
                    TEST_KEY + "StringArray",
                    TEST_KEY + "PersistableBundle"
                };

        final PersistableBundle testBundle = getTestBundle();
        testBundle.putBoolean(TEST_KEY + "Boolean2", true);

        final PersistableBundle minimized =
                PersistableBundleUtils.minimizeBundle(testBundle, minimizedKeys);

        // Verify that the minimized bundle is NOT the same in size OR values due to the extra
        // Boolean2 key
        assertFalse(PersistableBundleUtils.isEqual(testBundle, minimized));

        // Verify that removing the extra key from the source bundle results in equality.
        testBundle.remove(TEST_KEY + "Boolean2");
        assertTrue(PersistableBundleUtils.isEqual(testBundle, minimized));
    }

    @Test
    public void testToFromDiskStableBytes() throws Exception {
        final PersistableBundle testBundle = getTestBundle();
        final PersistableBundle result =
                PersistableBundleUtils.fromDiskStableBytes(
                        PersistableBundleUtils.toDiskStableBytes(testBundle));
        assertTrue(PersistableBundleUtils.isEqual(testBundle, result));
    }

    @Test
    public void testEquality_identical() throws Exception {
        final PersistableBundle left = getTestBundle();
        final PersistableBundle right = getTestBundle();

        assertTrue(PersistableBundleUtils.isEqual(left, right));
    }

    @Test
    public void testEquality_different() throws Exception {
        final PersistableBundle left = getTestBundle();
        final PersistableBundle right = getTestBundle();

        left.putBoolean(TEST_KEY + "Boolean2", true);
        assertFalse(PersistableBundleUtils.isEqual(left, right));

        left.remove(TEST_KEY + "Boolean2");
        assertTrue(PersistableBundleUtils.isEqual(left, right));
    }

    @Test
    public void testEquality_null() throws Exception {
        assertFalse(PersistableBundleUtils.isEqual(getTestBundle(), null));
        assertFalse(PersistableBundleUtils.isEqual(null, getTestBundle()));
        assertTrue(PersistableBundleUtils.isEqual(null, null));
    }
}
