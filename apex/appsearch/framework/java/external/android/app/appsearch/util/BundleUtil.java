/*
 * Copyright 2020 The Android Open Source Project
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

package android.app.appsearch.util;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.Bundle;
import android.os.Parcel;
import android.util.SparseArray;

import java.util.ArrayList;
import java.util.Arrays;

/**
 * Utilities for working with {@link android.os.Bundle}.
 *
 * @hide
 */
public final class BundleUtil {
    private BundleUtil() {}

    /**
     * Deeply checks two bundles are equal or not.
     *
     * <p>Two bundles will be considered equal if they contain the same keys, and each value is also
     * equal. Bundle values are compared using deepEquals.
     */
    public static boolean deepEquals(@Nullable Bundle one, @Nullable Bundle two) {
        if (one == null && two == null) {
            return true;
        }
        if (one == null || two == null) {
            return false;
        }
        if (one.size() != two.size()) {
            return false;
        }
        if (!one.keySet().equals(two.keySet())) {
            return false;
        }
        // Bundle inherit its equals() from Object.java, which only compare their memory address.
        // We should iterate all keys and check their presents and values in both bundle.
        for (String key : one.keySet()) {
            if (!bundleValueEquals(one.get(key), two.get(key))) {
                return false;
            }
        }
        return true;
    }

    /**
     * Deeply checks whether two values in a Bundle are equal or not.
     *
     * <p>Values of type Bundle are compared using {@link #deepEquals}.
     */
    private static boolean bundleValueEquals(@Nullable Object one, @Nullable Object two) {
        if (one == null && two == null) {
            return true;
        }
        if (one == null || two == null) {
            return false;
        }
        if (one.equals(two)) {
            return true;
        }
        if (one instanceof Bundle && two instanceof Bundle) {
            return deepEquals((Bundle) one, (Bundle) two);
        } else if (one instanceof int[] && two instanceof int[]) {
            return Arrays.equals((int[]) one, (int[]) two);
        } else if (one instanceof byte[] && two instanceof byte[]) {
            return Arrays.equals((byte[]) one, (byte[]) two);
        } else if (one instanceof char[] && two instanceof char[]) {
            return Arrays.equals((char[]) one, (char[]) two);
        } else if (one instanceof long[] && two instanceof long[]) {
            return Arrays.equals((long[]) one, (long[]) two);
        } else if (one instanceof float[] && two instanceof float[]) {
            return Arrays.equals((float[]) one, (float[]) two);
        } else if (one instanceof short[] && two instanceof short[]) {
            return Arrays.equals((short[]) one, (short[]) two);
        } else if (one instanceof double[] && two instanceof double[]) {
            return Arrays.equals((double[]) one, (double[]) two);
        } else if (one instanceof boolean[] && two instanceof boolean[]) {
            return Arrays.equals((boolean[]) one, (boolean[]) two);
        } else if (one instanceof Object[] && two instanceof Object[]) {
            Object[] arrayOne = (Object[]) one;
            Object[] arrayTwo = (Object[]) two;
            if (arrayOne.length != arrayTwo.length) {
                return false;
            }
            if (Arrays.equals(arrayOne, arrayTwo)) {
                return true;
            }
            for (int i = 0; i < arrayOne.length; i++) {
                if (!bundleValueEquals(arrayOne[i], arrayTwo[i])) {
                    return false;
                }
            }
            return true;
        } else if (one instanceof ArrayList && two instanceof ArrayList) {
            ArrayList<?> listOne = (ArrayList<?>) one;
            ArrayList<?> listTwo = (ArrayList<?>) two;
            if (listOne.size() != listTwo.size()) {
                return false;
            }
            for (int i = 0; i < listOne.size(); i++) {
                if (!bundleValueEquals(listOne.get(i), listTwo.get(i))) {
                    return false;
                }
            }
            return true;
        } else if (one instanceof SparseArray && two instanceof SparseArray) {
            SparseArray<?> arrayOne = (SparseArray<?>) one;
            SparseArray<?> arrayTwo = (SparseArray<?>) two;
            if (arrayOne.size() != arrayTwo.size()) {
                return false;
            }
            for (int i = 0; i < arrayOne.size(); i++) {
                if (arrayOne.keyAt(i) != arrayTwo.keyAt(i)
                        || !bundleValueEquals(arrayOne.valueAt(i), arrayTwo.valueAt(i))) {
                    return false;
                }
            }
            return true;
        }
        return false;
    }

    /**
     * Calculates the hash code for a bundle.
     *
     * <p>The hash code is only effected by the contents in the bundle. Bundles will get consistent
     * hash code if they have same contents.
     */
    public static int deepHashCode(@Nullable Bundle bundle) {
        if (bundle == null) {
            return 0;
        }
        int[] hashCodes = new int[bundle.size() + 1];
        int hashCodeIdx = 0;
        // Bundle inherit its hashCode() from Object.java, which only relative to their memory
        // address. Bundle doesn't have an order, so we should iterate all keys and combine
        // their value's hashcode into an array. And use the hashcode of the array to be
        // the hashcode of the bundle.
        // Because bundle.keySet() doesn't guarantee any particular order, we need to sort the keys
        // in case the iteration order varies from run to run.
        String[] keys = bundle.keySet().toArray(new String[0]);
        Arrays.sort(keys);
        // Hash the keys so we can detect key-only differences
        hashCodes[hashCodeIdx++] = Arrays.hashCode(keys);
        for (int keyIdx = 0; keyIdx < keys.length; keyIdx++) {
            Object value = bundle.get(keys[keyIdx]);
            if (value instanceof Bundle) {
                hashCodes[hashCodeIdx++] = deepHashCode((Bundle) value);
            } else if (value instanceof int[]) {
                hashCodes[hashCodeIdx++] = Arrays.hashCode((int[]) value);
            } else if (value instanceof byte[]) {
                hashCodes[hashCodeIdx++] = Arrays.hashCode((byte[]) value);
            } else if (value instanceof char[]) {
                hashCodes[hashCodeIdx++] = Arrays.hashCode((char[]) value);
            } else if (value instanceof long[]) {
                hashCodes[hashCodeIdx++] = Arrays.hashCode((long[]) value);
            } else if (value instanceof float[]) {
                hashCodes[hashCodeIdx++] = Arrays.hashCode((float[]) value);
            } else if (value instanceof short[]) {
                hashCodes[hashCodeIdx++] = Arrays.hashCode((short[]) value);
            } else if (value instanceof double[]) {
                hashCodes[hashCodeIdx++] = Arrays.hashCode((double[]) value);
            } else if (value instanceof boolean[]) {
                hashCodes[hashCodeIdx++] = Arrays.hashCode((boolean[]) value);
            } else if (value instanceof String[]) {
                // Optimization to avoid Object[] handler creating an inner array for common cases
                hashCodes[hashCodeIdx++] = Arrays.hashCode((String[]) value);
            } else if (value instanceof Object[]) {
                Object[] array = (Object[]) value;
                int[] innerHashCodes = new int[array.length];
                for (int j = 0; j < array.length; j++) {
                    if (array[j] instanceof Bundle) {
                        innerHashCodes[j] = deepHashCode((Bundle) array[j]);
                    } else if (array[j] != null) {
                        innerHashCodes[j] = array[j].hashCode();
                    }
                }
                hashCodes[hashCodeIdx++] = Arrays.hashCode(innerHashCodes);
            } else if (value instanceof ArrayList) {
                ArrayList<?> list = (ArrayList<?>) value;
                int[] innerHashCodes = new int[list.size()];
                for (int j = 0; j < innerHashCodes.length; j++) {
                    Object item = list.get(j);
                    if (item instanceof Bundle) {
                        innerHashCodes[j] = deepHashCode((Bundle) item);
                    } else if (item != null) {
                        innerHashCodes[j] = item.hashCode();
                    }
                }
                hashCodes[hashCodeIdx++] = Arrays.hashCode(innerHashCodes);
            } else if (value instanceof SparseArray) {
                SparseArray<?> array = (SparseArray<?>) value;
                int[] innerHashCodes = new int[array.size() * 2];
                for (int j = 0; j < array.size(); j++) {
                    innerHashCodes[j * 2] = array.keyAt(j);
                    Object item = array.valueAt(j);
                    if (item instanceof Bundle) {
                        innerHashCodes[j * 2 + 1] = deepHashCode((Bundle) item);
                    } else if (item != null) {
                        innerHashCodes[j * 2 + 1] = item.hashCode();
                    }
                }
                hashCodes[hashCodeIdx++] = Arrays.hashCode(innerHashCodes);
            } else {
                hashCodes[hashCodeIdx++] = value.hashCode();
            }
        }
        return Arrays.hashCode(hashCodes);
    }

    /**
     * Deeply clones a Bundle.
     *
     * <p>Values which are Bundles, Lists or Arrays are deeply copied themselves.
     */
    @NonNull
    public static Bundle deepCopy(@NonNull Bundle bundle) {
        // Write bundle to bytes
        Parcel parcel = Parcel.obtain();
        try {
            parcel.writeBundle(bundle);
            byte[] serializedMessage = parcel.marshall();

            // Read bundle from bytes
            parcel.unmarshall(serializedMessage, 0, serializedMessage.length);
            parcel.setDataPosition(0);
            return parcel.readBundle();
        } finally {
            parcel.recycle();
        }
    }
}
