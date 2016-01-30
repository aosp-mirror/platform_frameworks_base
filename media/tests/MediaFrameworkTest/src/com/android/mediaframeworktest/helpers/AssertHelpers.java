/*
 * Copyright 2016 The Android Open Source Project
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

package com.android.mediaframeworktest.helpers;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;

import static junit.framework.Assert.assertEquals;
import static junit.framework.Assert.assertTrue;
import static junit.framework.Assert.fail;

/**
 * Helper set of methods to add extra useful assert functionality missing in junit.
 */
/**
 * (non-Javadoc)
 * @see android.hardware.camera2.cts.helpers.AssertHelpers
 */
public class AssertHelpers {

    private static final int MAX_FORMAT_STRING = 50;

    /**
     * Assert that at least one of the elements in data is non-zero.
     *
     * <p>An empty or a null array always fails.</p>
     */
    public static void assertArrayNotAllZeroes(String message, byte[] data) {
        int size = data.length;

        int i = 0;
        for (i = 0; i < size; ++i) {
            if (data[i] != 0) {
                break;
            }
        }

        assertTrue(message, i < size);
    }

    /**
     * Assert that every element in left is less than or equals to the corresponding element in
     * right.
     *
     * <p>Array sizes must match.</p>
     *
     * @param message Message to use in case the assertion fails
     * @param left Left array
     * @param right Right array
     */
    public static void assertArrayNotGreater(String message, float[] left, float[] right) {
        assertEquals("Array lengths did not match", left.length, right.length);

        String leftString = Arrays.toString(left);
        String rightString = Arrays.toString(right);

        for (int i = 0; i < left.length; ++i) {
            String msg = String.format(
                    "%s: (%s should be less than or equals than %s; item index %d; left = %s; " +
                    "right = %s)",
                    message, left[i], right[i], i, leftString, rightString);

            assertTrue(msg, left[i] <= right[i]);
        }
    }

    /**
     * Assert that every element in the value array is greater than the lower bound (exclusive).
     *
     * @param value an array of items
     * @param lowerBound the exclusive lower bound
     */
    public static void assertArrayWithinLowerBound(String message, float[] value, float lowerBound)
    {
        for (int i = 0; i < value.length; ++i) {
            assertTrue(
                    String.format("%s: (%s should be greater than than %s; item index %d in %s)",
                            message, value[i], lowerBound, i, Arrays.toString(value)),
                    value[i] > lowerBound);
        }
    }

    /**
     * Assert that every element in the value array is less than the upper bound (exclusive).
     *
     * @param value an array of items
     * @param upperBound the exclusive upper bound
     */
    public static void assertArrayWithinUpperBound(String message, float[] value, float upperBound)
    {
        for (int i = 0; i < value.length; ++i) {
            assertTrue(
                    String.format("%s: (%s should be less than than %s; item index %d in %s)",
                            message, value[i], upperBound, i, Arrays.toString(value)),
                    value[i] < upperBound);
        }
    }

    /**
     * Assert that {@code low <= value <= high}
     */
    public static void assertInRange(float value, float low, float high) {
        assertTrue(
                String.format("Value %s must be greater or equal to %s, but was lower", value, low),
                value >= low);
        assertTrue(
                String.format("Value %s must be less than or equal to %s, but was higher",
                        value, high),
                value <= high);

        // TODO: generic by using comparators
    }

    /**
     * Assert that the given array contains the given value.
     *
     * @param message message to print on failure.
     * @param actual array to test.
     * @param checkVals value to check for array membership.
     */
    public static <T> void assertArrayContains(String message, T[] actual, T checkVals) {
        assertCollectionContainsAnyOf(message, buildList(actual), Arrays.asList(checkVals));
    }


    /**
     * Assert that the given array contains the given value.
     *
     * @param message message to print on failure.
     * @param actual array to test.
     * @param checkVals value to check for array membership.
     */
    public static void assertArrayContains(String message, int[] actual, int checkVals) {
        assertCollectionContainsAnyOf(message, buildList(actual), Arrays.asList(checkVals));
    }

    /**
     * Assert that the given array contains at least one of the given values.
     *
     * @param message message to print on failure.
     * @param actual array to test
     * @param checkVals values to check for array membership.
     * @return the value contained, or null.
     */
    public static <T> T assertArrayContainsAnyOf(String message, T[] actual, T[] checkVals) {
        return assertCollectionContainsAnyOf(message, buildList(actual), buildList(checkVals));
    }

    /**
     * Assert that the given array contains at least one of the given values.
     *
     * @param message message to print on failure.
     * @param actual array to test
     * @param checkVals values to check for array membership.
     * @return the value contained.
     */
    public static int assertArrayContainsAnyOf(String message, int[] actual, int[] checkVals) {
        return assertCollectionContainsAnyOf(message, buildList(actual), buildList(checkVals));
    }

    /**
     * Assert that the given {@link Collection} contains at least one of the given values.
     *
     * @param message message to print on failure.
     * @param actual {@link Collection} to test.
     * @param checkVals a {@link Collection} of values to check for membership.
     * @return the value contained, or null.
     */
    public static <T> T assertCollectionContainsAnyOf(String message, Collection<T> actual,
                                                      Collection<T> checkVals) {
        boolean contains = false;
        T selected = null;
        for (T check : checkVals) {
            contains = actual.contains(check);
            if (contains) {
                selected = check;
                break;
            }
        }

        if (!contains) {
            fail(String.format("%s : No elements from %s in %s", message,
                    formatCollection(actual, MAX_FORMAT_STRING),
                    formatCollection(checkVals, MAX_FORMAT_STRING)));
        }
        return selected;
    }

    private static <T> List<T> buildList(T[] array) {
        return new ArrayList<T>(Arrays.asList(array));
    }

    private static List<Integer> buildList(int[] array) {
        List<Integer> list = new ArrayList<Integer>(array.length);
        for (Integer val : array) {
            list.add(val);
        }
        return list;
    }

    private static <T> String formatCollection(Collection<T> collection, int maxLen) {
        StringBuilder builder = new StringBuilder();
        builder.append("[");

        boolean first = true;
        for (T elem : collection) {
            String val = ((first) ? ", " : "") + ((elem != null) ? elem.toString() : "null");
            first = false;
            if ((builder.length() + val.length()) > maxLen - "...]".length()) {
                builder.append("...");
                break;
            } else {
                builder.append(val);
            }
        }
        builder.append("]");
        return builder.toString();
    }


    // Suppress default constructor for noninstantiability
    private AssertHelpers() { throw new AssertionError(); }
}
