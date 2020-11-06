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

import static org.junit.Assert.assertEquals;

import android.os.PersistableBundle;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

@RunWith(AndroidJUnit4.class)
@SmallTest
public class PersistableBundleUtilsTest {
    private static final String TEST_KEY = "testKey";
    private static final String TEST_STRING_PREFIX = "testString";
    private static final int[] TEST_INT_ARRAY = new int[] {0, 1, 2, 3, 4};

    private static final int NUM_COLLECTION_ENTRIES = 10;

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
    public void testConversionLossless() throws Exception {
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
}
