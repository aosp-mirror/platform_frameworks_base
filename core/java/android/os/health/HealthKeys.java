/*
 * Copyright (C) 2016 The Android Open Source Project
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

package android.os.health;

import android.annotation.TestApi;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.Field;
import java.util.Arrays;

/**
 * Constants and stuff for the android.os.health package.
 *
 * @hide
 */
@TestApi
public class HealthKeys {

    /**
     * No valid key will ever be 0.
     */
    public static final int UNKNOWN_KEY = 0;

    /*
     * Base key for each of the different classes. There is
     * nothing intrinsic to the operation of the value of the
     * keys. It's just segmented for better debugging. The
     * classes don't mix them anway.
     */
    public static final int BASE_UID = 10000;
    public static final int BASE_PID = 20000;
    public static final int BASE_PROCESS = 30000;
    public static final int BASE_PACKAGE = 40000;
    public static final int BASE_SERVICE = 50000;

    /*
     * The types of values supported by HealthStats.
     */
    public static final int TYPE_TIMER = 0;
    public static final int TYPE_MEASUREMENT = 1;
    public static final int TYPE_STATS = 2;
    public static final int TYPE_TIMERS = 3;
    public static final int TYPE_MEASUREMENTS = 4;

    public static final int TYPE_COUNT = 5;

    /**
     * Annotation to mark public static final int fields that are to be used
     * as field keys in HealthStats.
     */
    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.FIELD})
    public @interface Constant {
        /**
         * One of the TYPE_* constants above.
         */
        int type();
    }

    /**
     * Class to gather the constants defined in a class full of constants and
     * build the key indices used by HealthStatsWriter and HealthStats.
     *
     * @hide
     */
    @TestApi
    public static class Constants {
        private final String mDataType;
        private final int[][] mKeys = new int[TYPE_COUNT][];

        /**
         * Pass in a class to gather the public static final int fields that are
         * tagged with the @Constant annotation.
         */
        public Constants(Class clazz) {
            // Save the class name for debugging
            mDataType = clazz.getSimpleName();

            // Iterate through the list of fields on this class, and build the
            // constant arrays for these fields.
            final Field[] fields = clazz.getDeclaredFields();
            final Class<Constant> annotationClass = Constant.class;

            final int N = fields.length;

            final SortedIntArray[] keys = new SortedIntArray[mKeys.length];
            for (int i=0; i<keys.length; i++) {
                keys[i] = new SortedIntArray(N);
            }

            for (int i=0; i<N; i++) {
                final Field field = fields[i];
                final Constant constant = field.getAnnotation(annotationClass);
                if (constant != null) {
                    final int type = constant.type();
                    if (type >= keys.length) {
                        throw new RuntimeException("Unknown Constant type " + type
                                + " on " + field);
                    }
                    try {
                        keys[type].addValue(field.getInt(null));
                    } catch (IllegalAccessException ex) {
                        throw new RuntimeException("Can't read constant value type=" + type
                                + " field=" + field, ex);
                    }
                }
            }

            for (int i=0; i<keys.length; i++) {
                mKeys[i] = keys[i].getArray();
            }
        }

        /**
         * Get a string representation of this class. Useful for debugging. It will be the
         * simple name of the class passed in the constructor.
         */
        public String getDataType() {
            return mDataType;
        }

        /**
         * Return how many keys there are for the given field type.
         *
         * @see TYPE_TIMER
         * @see TYPE_MEASUREMENT
         * @see TYPE_TIMERS
         * @see TYPE_MEASUREMENTS
         * @see TYPE_STATS
         */
        public int getSize(int type) {
            return mKeys[type].length;
        }

        /**
         * Return the index for the given type and key combination in the array of field
         * keys or values.
         *
         * @see TYPE_TIMER
         * @see TYPE_MEASUREMENT
         * @see TYPE_TIMERS
         * @see TYPE_MEASUREMENTS
         * @see TYPE_STATS
         */
        public int getIndex(int type, int key) {
            final int index = Arrays.binarySearch(mKeys[type], key);
            if (index >= 0) {
                return index;
            } else {
                throw new RuntimeException("Unknown Constant " + key + " (of type "
                        + type + " )");
            }
        }

        /**
         * Get the array of keys for the given field type.
         */
        public int[] getKeys(int type) {
            return mKeys[type];
        }
    }

    /**
     * An array of fixed size that will be sorted.
     */
    private static class SortedIntArray {
        int mCount;
        int[] mArray;

        /**
         * Construct with the maximum number of values.
         */
        SortedIntArray(int maxCount) {
            mArray = new int[maxCount];
        }

        /**
         * Add a value.
         */
        void addValue(int value) {
            mArray[mCount++] = value;
        }

        /**
         * Get the array of values that have been added, with the values in
         * numerically increasing order.
         */
        int[] getArray() {
            if (mCount == mArray.length) {
                Arrays.sort(mArray);
                return mArray;
            } else {
                final int[] result = new int[mCount];
                System.arraycopy(mArray, 0, result, 0, mCount);
                Arrays.sort(result);
                return result;
            }
        }
    }
}


