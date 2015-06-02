/*
 * Copyright (C) 2014 The Android Open Source Project
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

package android.hardware.camera2.utils;

import android.util.Log;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Various assortment of array utilities.
 */
public class ArrayUtils {

    private static final String TAG = "ArrayUtils";
    private static final boolean DEBUG = false;

    /** Return the index of {@code needle} in the {@code array}, or else {@code -1} */
    public static <T> int getArrayIndex(T[] array, T needle) {
        if (array == null) {
            return -1;
        }

        int index = 0;
        for (T elem : array) {
            if (Objects.equals(elem, needle)) {
                return index;
            }
            index++;
        }

        return -1;
    }

    /** Return the index of {@code needle} in the {@code array}, or else {@code -1} */
    public static int getArrayIndex(int[] array, int needle) {
        if (array == null) {
            return -1;
        }
        for (int i = 0; i < array.length; ++i) {
            if (array[i] == needle) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Create an {@code int[]} from the {@code List<>} by using {@code convertFrom} and
     * {@code convertTo} as a one-to-one map (via the index).
     *
     * <p>Strings not appearing in {@code convertFrom} are ignored (with a logged warning);
     * strings appearing in {@code convertFrom} but not {@code convertTo} are silently
     * dropped.</p>
     *
     * @param list Source list of strings
     * @param convertFrom Conversion list of strings
     * @param convertTo Conversion list of ints
     * @return An array of ints where the values correspond to the ones in {@code convertTo}
     *         or {@code null} if {@code list} was {@code null}
     */
    public static int[] convertStringListToIntArray(
            List<String> list, String[] convertFrom, int[] convertTo) {
        if (list == null) {
            return null;
        }

        List<Integer> convertedList = convertStringListToIntList(list, convertFrom, convertTo);

        int[] returnArray = new int[convertedList.size()];
        for (int i = 0; i < returnArray.length; ++i) {
            returnArray[i] = convertedList.get(i);
        }

        return returnArray;
    }

    /**
     * Create an {@code List<Integer>} from the {@code List<>} by using {@code convertFrom} and
     * {@code convertTo} as a one-to-one map (via the index).
     *
     * <p>Strings not appearing in {@code convertFrom} are ignored (with a logged warning);
     * strings appearing in {@code convertFrom} but not {@code convertTo} are silently
     * dropped.</p>
     *
     * @param list Source list of strings
     * @param convertFrom Conversion list of strings
     * @param convertTo Conversion list of ints
     * @return A list of ints where the values correspond to the ones in {@code convertTo}
     *         or {@code null} if {@code list} was {@code null}
     */
    public static List<Integer> convertStringListToIntList(
            List<String> list, String[] convertFrom, int[] convertTo) {
        if (list == null) {
            return null;
        }

        List<Integer> convertedList = new ArrayList<>(list.size());

        for (String str : list) {
            int strIndex = getArrayIndex(convertFrom, str);

            // Guard against unexpected values
            if (strIndex < 0) {
                if (DEBUG) Log.v(TAG, "Ignoring invalid value " + str);
                continue;
            }

            // Ignore values we can't map into (intentional)
            if (strIndex < convertTo.length) {
                convertedList.add(convertTo[strIndex]);
            }
        }

        return convertedList;
    }

    /**
     * Convert the list of integers in {@code list} to an {@code int} array.
     *
     * <p>Every element in {@code list} must be non-{@code null}.</p>
     *
     * @param list a list of non-{@code null} integers
     *
     * @return a new int array containing all the elements from {@code list}
     *
     * @throws NullPointerException if any of the elements in {@code list} were {@code null}
     */
    public static int[] toIntArray(List<Integer> list) {
        if (list == null) {
            return null;
        }

        int[] arr = new int[list.size()];
        int i = 0;
        for (int elem : list) {
            arr[i] = elem;
            i++;
        }

        return arr;
    }

    /**
     * Returns true if the given {@code array} contains the given element.
     *
     * @param array {@code array} to check for {@code elem}
     * @param elem {@code elem} to test for
     * @return {@code true} if the given element is contained
     */
    public static boolean contains(int[] array, int elem) {
        return getArrayIndex(array, elem) != -1;
    }

    /**
     * Returns true if the given {@code array} contains the given element.
     *
     * @param array {@code array} to check for {@code elem}
     * @param elem {@code elem} to test for
     * @return {@code true} if the given element is contained
     */
    public static <T> boolean contains(T[] array, T elem) {
        return getArrayIndex(array, elem) != -1;
    }

    private ArrayUtils() {
        throw new AssertionError();
    }
}
