/*
 * Copyright (C) 2007 The Android Open Source Project
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

import android.annotation.UnsupportedAppUsage;

/**
 * {@link CharSequence} utility methods.
 */
public class CharSequences {

    /**
     * Adapts {@link CharSequence} to an array of ASCII (7-bits per character)
     * bytes.
     * 
     * @param bytes ASCII bytes
     */
    public static CharSequence forAsciiBytes(final byte[] bytes) {
        return new CharSequence() {
            public char charAt(int index) {
                return (char) bytes[index];
            }

            public int length() {
                return bytes.length;
            }

            public CharSequence subSequence(int start, int end) {
                return forAsciiBytes(bytes, start, end);
            }

            public String toString() {
                return new String(bytes);
            }
        };
    }

    /**
     * Adapts {@link CharSequence} to an array of ASCII (7-bits per character)
     * bytes.
     *
     * @param bytes ASCII bytes
     * @param start index, inclusive
     * @param end index, exclusive
     *
     * @throws IndexOutOfBoundsException if start or end are negative, if end
     *  is greater than length(), or if start is greater than end
     */
    public static CharSequence forAsciiBytes(final byte[] bytes,
            final int start, final int end) {
        validate(start, end, bytes.length);
        return new CharSequence() {
            public char charAt(int index) {
                return (char) bytes[index + start];
            }

            public int length() {
                return end - start;
            }

            public CharSequence subSequence(int newStart, int newEnd) {
                newStart -= start;
                newEnd -= start;
                validate(newStart, newEnd, length());
                return forAsciiBytes(bytes, newStart, newEnd);
            }

            public String toString() {
                return new String(bytes, start, length());
            }
        };
    }

    static void validate(int start, int end, int length) {
        if (start < 0) throw new IndexOutOfBoundsException();
        if (end < 0) throw new IndexOutOfBoundsException();
        if (end > length) throw new IndexOutOfBoundsException();
        if (start > end) throw new IndexOutOfBoundsException();
    }

    /**
     * Compares two character sequences for equality.
     */
    @UnsupportedAppUsage
    public static boolean equals(CharSequence a, CharSequence b) {
        if (a.length() != b.length()) {
            return false;
        }

        int length = a.length();
        for (int i = 0; i < length; i++) {
            if (a.charAt(i) != b.charAt(i)) {
                return false;
            }
        }
        return true;
    }
    
    /**
     * Compares two character sequences with API like {@link Comparable#compareTo}.
     * 
     * @param me The CharSequence that receives the compareTo call.
     * @param another The other CharSequence.
     * @return See {@link Comparable#compareTo}.
     */
    @UnsupportedAppUsage
    public static int compareToIgnoreCase(CharSequence me, CharSequence another) {
        // Code adapted from String#compareTo
        int myLen = me.length(), anotherLen = another.length();
        int myPos = 0, anotherPos = 0, result;
        int end = (myLen < anotherLen) ? myLen : anotherLen;

        while (myPos < end) {
            if ((result = Character.toLowerCase(me.charAt(myPos++))
                    - Character.toLowerCase(another.charAt(anotherPos++))) != 0) {
                return result;
            }
        }
        return myLen - anotherLen;
    }
}
