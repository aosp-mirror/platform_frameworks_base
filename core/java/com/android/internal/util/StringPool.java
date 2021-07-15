/*
 * Copyright (C) 2010 The Android Open Source Project
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

package com.android.internal.util;

/**
 * A pool of string instances. Unlike the {@link String#intern() VM's
 * interned strings}, this pool provides no guarantee of reference equality.
 * It is intended only to save allocations. This class is not thread safe.
 *
 * @hide
 */
public final class StringPool {

    private final String[] mPool = new String[512];

    /**
     * Constructs string pool.
     */
    public StringPool() {
    }

    private static boolean contentEquals(String s, char[] chars, int start, int length) {
        if (s.length() != length) {
            return false;
        }
        for (int i = 0; i < length; i++) {
            if (chars[start + i] != s.charAt(i)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Returns a string equal to {@code new String(array, start, length)}.
     *
     * @param array  buffer containing string chars
     * @param start  offset in {@code array} where string starts
     * @param length length of string
     * @return string equal to {@code new String(array, start, length)}
     */
    public String get(char[] array, int start, int length) {
        // Compute an arbitrary hash of the content
        int hashCode = 0;
        for (int i = start; i < start + length; i++) {
            hashCode = (hashCode * 31) + array[i];
        }

        // Pick a bucket using Doug Lea's supplemental secondaryHash function (from HashMap)
        hashCode ^= (hashCode >>> 20) ^ (hashCode >>> 12);
        hashCode ^= (hashCode >>> 7) ^ (hashCode >>> 4);
        int index = hashCode & (mPool.length - 1);

        String pooled = mPool[index];
        if (pooled != null && contentEquals(pooled, array, start, length)) {
            return pooled;
        }

        String result = new String(array, start, length);
        mPool[index] = result;
        return result;
    }
}
