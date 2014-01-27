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

package com.android.tools.layoutlib.java;

/**
 * Defines the same class as the java.lang.IntegralToString which was added in
 * Dalvik VM. This hack, provides a replacement for that class which can't be
 * loaded in the standard JVM since it's in the java package and standard JVM
 * doesn't have it. Since it's no longer in java.lang, access to package
 * private methods and classes has been replaced by the closes matching public
 * implementation.
 * <p/>
 * Extracted from API level 18, file:
 * platform/libcore/luni/src/main/java/java/lang/IntegralToString.java
 */
public final class IntegralToString {
    /**
     * When appending to an AbstractStringBuilder, this thread-local char[] lets us avoid
     * allocation of a temporary array. (We can't write straight into the AbstractStringBuilder
     * because it's almost as expensive to work out the exact length of the result as it is to
     * do the formatting. We could try being conservative and "delete"-ing the unused space
     * afterwards, but then we'd need to duplicate convertInt and convertLong rather than share
     * the code.)
     */
    private static final ThreadLocal<char[]> BUFFER = new ThreadLocal<char[]>() {
        @Override protected char[] initialValue() {
            return new char[20]; // Maximum length of a base-10 long.
        }
    };

    /**
     * These tables are used to special-case toString computation for
     * small values.  This serves three purposes: it reduces memory usage;
     * it increases performance for small values; and it decreases the
     * number of comparisons required to do the length computation.
     * Elements of this table are lazily initialized on first use.
     * No locking is necessary, i.e., we use the non-volatile, racy
     * single-check idiom.
     */
    private static final String[] SMALL_NONNEGATIVE_VALUES = new String[100];
    private static final String[] SMALL_NEGATIVE_VALUES = new String[100];

    /** TENS[i] contains the tens digit of the number i, 0 <= i <= 99. */
    private static final char[] TENS = {
        '0', '0', '0', '0', '0', '0', '0', '0', '0', '0',
        '1', '1', '1', '1', '1', '1', '1', '1', '1', '1',
        '2', '2', '2', '2', '2', '2', '2', '2', '2', '2',
        '3', '3', '3', '3', '3', '3', '3', '3', '3', '3',
        '4', '4', '4', '4', '4', '4', '4', '4', '4', '4',
        '5', '5', '5', '5', '5', '5', '5', '5', '5', '5',
        '6', '6', '6', '6', '6', '6', '6', '6', '6', '6',
        '7', '7', '7', '7', '7', '7', '7', '7', '7', '7',
        '8', '8', '8', '8', '8', '8', '8', '8', '8', '8',
        '9', '9', '9', '9', '9', '9', '9', '9', '9', '9'
    };

    /** Ones [i] contains the tens digit of the number i, 0 <= i <= 99. */
    private static final char[] ONES = {
        '0', '1', '2', '3', '4', '5', '6', '7', '8', '9',
        '0', '1', '2', '3', '4', '5', '6', '7', '8', '9',
        '0', '1', '2', '3', '4', '5', '6', '7', '8', '9',
        '0', '1', '2', '3', '4', '5', '6', '7', '8', '9',
        '0', '1', '2', '3', '4', '5', '6', '7', '8', '9',
        '0', '1', '2', '3', '4', '5', '6', '7', '8', '9',
        '0', '1', '2', '3', '4', '5', '6', '7', '8', '9',
        '0', '1', '2', '3', '4', '5', '6', '7', '8', '9',
        '0', '1', '2', '3', '4', '5', '6', '7', '8', '9',
        '0', '1', '2', '3', '4', '5', '6', '7', '8', '9',
    };

    /**
     * Table for MOD / DIV 10 computation described in Section 10-21
     * of Hank Warren's "Hacker's Delight" online addendum.
     * http://www.hackersdelight.org/divcMore.pdf
     */
    private static final char[] MOD_10_TABLE = {
        0, 1, 2, 2, 3, 3, 4, 5, 5, 6, 7, 7, 8, 8, 9, 0
    };

    /**
     * The digits for every supported radix.
     */
    private static final char[] DIGITS = {
        '0', '1', '2', '3', '4', '5', '6', '7', '8', '9',
        'a', 'b', 'c', 'd', 'e', 'f', 'g', 'h', 'i', 'j',
        'k', 'l', 'm', 'n', 'o', 'p', 'q', 'r', 's', 't',
        'u', 'v', 'w', 'x', 'y', 'z'
    };

    private static final char[] UPPER_CASE_DIGITS = {
        '0', '1', '2', '3', '4', '5', '6', '7', '8', '9',
        'A', 'B', 'C', 'D', 'E', 'F', 'G', 'H', 'I', 'J',
        'K', 'L', 'M', 'N', 'O', 'P', 'Q', 'R', 'S', 'T',
        'U', 'V', 'W', 'X', 'Y', 'Z'
    };

    private IntegralToString() {
    }

    /**
     * Equivalent to Integer.toString(i, radix).
     */
    public static String intToString(int i, int radix) {
        if (radix < Character.MIN_RADIX || radix > Character.MAX_RADIX) {
            radix = 10;
        }
        if (radix == 10) {
            return intToString(i);
        }

        /*
         * If i is positive, negate it. This is the opposite of what one might
         * expect. It is necessary because the range of the negative values is
         * strictly larger than that of the positive values: there is no
         * positive value corresponding to Integer.MIN_VALUE.
         */
        boolean negative = false;
        if (i < 0) {
            negative = true;
        } else {
            i = -i;
        }

        int bufLen = radix < 8 ? 33 : 12;  // Max chars in result (conservative)
        char[] buf = new char[bufLen];
        int cursor = bufLen;

        do {
            int q = i / radix;
            buf[--cursor] = DIGITS[radix * q - i];
            i = q;
        } while (i != 0);

        if (negative) {
            buf[--cursor] = '-';
        }

        return new String(buf, cursor, bufLen - cursor);
    }

    /**
     * Equivalent to Integer.toString(i).
     */
    public static String intToString(int i) {
        return convertInt(null, i);
    }

    /**
     * Equivalent to sb.append(Integer.toString(i)).
     */
    public static void appendInt(StringBuilder sb, int i) {
        convertInt(sb, i);
    }

    /**
     * Returns the string representation of i and leaves sb alone if sb is null.
     * Returns null and appends the string representation of i to sb if sb is non-null.
     */
    private static String convertInt(StringBuilder sb, int i) {
        boolean negative = false;
        String quickResult = null;
        if (i < 0) {
            negative = true;
            i = -i;
            if (i < 100) {
                if (i < 0) {
                    // If -n is still negative, n is Integer.MIN_VALUE
                    quickResult = "-2147483648";
                } else {
                    quickResult = SMALL_NEGATIVE_VALUES[i];
                    if (quickResult == null) {
                        SMALL_NEGATIVE_VALUES[i] = quickResult =
                                i < 10 ? stringOf('-', ONES[i]) : stringOf('-', TENS[i], ONES[i]);
                    }
                }
            }
        } else {
            if (i < 100) {
                quickResult = SMALL_NONNEGATIVE_VALUES[i];
                if (quickResult == null) {
                    SMALL_NONNEGATIVE_VALUES[i] = quickResult =
                            i < 10 ? stringOf(ONES[i]) : stringOf(TENS[i], ONES[i]);
                }
            }
        }
        if (quickResult != null) {
            if (sb != null) {
                sb.append(quickResult);
                return null;
            }
            return quickResult;
        }

        int bufLen = 11; // Max number of chars in result
        char[] buf = (sb != null) ? BUFFER.get() : new char[bufLen];
        int cursor = bufLen;

        // Calculate digits two-at-a-time till remaining digits fit in 16 bits
        while (i >= (1 << 16)) {
            // Compute q = n/100 and r = n % 100 as per "Hacker's Delight" 10-8
            int q = (int) ((0x51EB851FL * i) >>> 37);
            int r = i - 100*q;
            buf[--cursor] = ONES[r];
            buf[--cursor] = TENS[r];
            i = q;
        }

        // Calculate remaining digits one-at-a-time for performance
        while (i != 0) {
            // Compute q = n/10 and r = n % 10 as per "Hacker's Delight" 10-8
            int q = (0xCCCD * i) >>> 19;
            int r = i - 10*q;
            buf[--cursor] = DIGITS[r];
            i = q;
        }

        if (negative) {
            buf[--cursor] = '-';
        }

        if (sb != null) {
            sb.append(buf, cursor, bufLen - cursor);
            return null;
        } else {
            return new String(buf, cursor, bufLen - cursor);
        }
    }

    /**
     * Equivalent to Long.toString(v, radix).
     */
    public static String longToString(long v, int radix) {
        int i = (int) v;
        if (i == v) {
            return intToString(i, radix);
        }

        if (radix < Character.MIN_RADIX || radix > Character.MAX_RADIX) {
            radix = 10;
        }
        if (radix == 10) {
            return longToString(v);
        }

        /*
         * If v is positive, negate it. This is the opposite of what one might
         * expect. It is necessary because the range of the negative values is
         * strictly larger than that of the positive values: there is no
         * positive value corresponding to Integer.MIN_VALUE.
         */
        boolean negative = false;
        if (v < 0) {
            negative = true;
        } else {
            v = -v;
        }

        int bufLen = radix < 8 ? 65 : 23;  // Max chars in result (conservative)
        char[] buf = new char[bufLen];
        int cursor = bufLen;

        do {
            long q = v / radix;
            buf[--cursor] = DIGITS[(int) (radix * q - v)];
            v = q;
        } while (v != 0);

        if (negative) {
            buf[--cursor] = '-';
        }

        return new String(buf, cursor, bufLen - cursor);
    }

    /**
     * Equivalent to Long.toString(l).
     */
    public static String longToString(long l) {
        return convertLong(null, l);
    }

    /**
     * Equivalent to sb.append(Long.toString(l)).
     */
    public static void appendLong(StringBuilder sb, long l) {
        convertLong(sb, l);
    }

    /**
     * Returns the string representation of n and leaves sb alone if sb is null.
     * Returns null and appends the string representation of n to sb if sb is non-null.
     */
    private static String convertLong(StringBuilder sb, long n) {
        int i = (int) n;
        if (i == n) {
            return convertInt(sb, i);
        }

        boolean negative = (n < 0);
        if (negative) {
            n = -n;
            if (n < 0) {
                // If -n is still negative, n is Long.MIN_VALUE
                String quickResult = "-9223372036854775808";
                if (sb != null) {
                    sb.append(quickResult);
                    return null;
                }
                return quickResult;
            }
        }

        int bufLen = 20; // Maximum number of chars in result
        char[] buf = (sb != null) ? BUFFER.get() : new char[bufLen];

        int low = (int) (n % 1000000000); // Extract low-order 9 digits
        int cursor = intIntoCharArray(buf, bufLen, low);

        // Zero-pad Low order part to 9 digits
        while (cursor != (bufLen - 9)) {
            buf[--cursor] = '0';
        }

        /*
         * The remaining digits are (n - low) / 1,000,000,000.  This
         * "exact division" is done as per the online addendum to Hank Warren's
         * "Hacker's Delight" 10-20, http://www.hackersdelight.org/divcMore.pdf
         */
        n = ((n - low) >>> 9) * 0x8E47CE423A2E9C6DL;

        /*
         * If the remaining digits fit in an int, emit them using a
         * single call to intIntoCharArray. Otherwise, strip off the
         * low-order digit, put it in buf, and then call intIntoCharArray
         * on the remaining digits (which now fit in an int).
         */
        if ((n & (-1L << 32)) == 0) {
            cursor = intIntoCharArray(buf, cursor, (int) n);
        } else {
            /*
             * Set midDigit to n % 10
             */
            int lo32 = (int) n;
            int hi32 = (int) (n >>> 32);

            // midDigit = ((unsigned) low32) % 10, per "Hacker's Delight" 10-21
            int midDigit = MOD_10_TABLE[(0x19999999 * lo32 + (lo32 >>> 1) + (lo32 >>> 3)) >>> 28];

            // Adjust midDigit for hi32. (assert hi32 == 1 || hi32 == 2)
            midDigit -= hi32 << 2;  // 1L << 32 == -4 MOD 10
            if (midDigit < 0) {
                midDigit += 10;
            }
            buf[--cursor] = DIGITS[midDigit];

            // Exact division as per Warren 10-20
            int rest = ((int) ((n - midDigit) >>> 1)) * 0xCCCCCCCD;
            cursor = intIntoCharArray(buf, cursor, rest);
        }

        if (negative) {
            buf[--cursor] = '-';
        }
        if (sb != null) {
            sb.append(buf, cursor, bufLen - cursor);
            return null;
        } else {
            return new String(buf, cursor, bufLen - cursor);
        }
    }

    /**
     * Inserts the unsigned decimal integer represented by n into the specified
     * character array starting at position cursor.  Returns the index after
     * the last character inserted (i.e., the value to pass in as cursor the
     * next time this method is called). Note that n is interpreted as a large
     * positive integer (not a negative integer) if its sign bit is set.
     */
    private static int intIntoCharArray(char[] buf, int cursor, int n) {
        // Calculate digits two-at-a-time till remaining digits fit in 16 bits
        while ((n & 0xffff0000) != 0) {
            /*
             * Compute q = n/100 and r = n % 100 as per "Hacker's Delight" 10-8.
             * This computation is slightly different from the corresponding
             * computation in intToString: the shifts before and after
             * multiply can't be combined, as that would yield the wrong result
             * if n's sign bit were set.
             */
            int q = (int) ((0x51EB851FL * (n >>> 2)) >>> 35);
            int r = n - 100*q;
            buf[--cursor] = ONES[r];
            buf[--cursor] = TENS[r];
            n = q;
        }

        // Calculate remaining digits one-at-a-time for performance
        while (n != 0) {
            // Compute q = n / 10 and r = n % 10 as per "Hacker's Delight" 10-8
            int q = (0xCCCD * n) >>> 19;
            int r = n - 10*q;
            buf[--cursor] = DIGITS[r];
            n = q;
        }
        return cursor;
    }

    public static String intToBinaryString(int i) {
        int bufLen = 32;  // Max number of binary digits in an int
        char[] buf = new char[bufLen];
        int cursor = bufLen;

        do {
            buf[--cursor] = DIGITS[i & 1];
        }  while ((i >>>= 1) != 0);

        return new String(buf, cursor, bufLen - cursor);
    }

    public static String longToBinaryString(long v) {
        int i = (int) v;
        if (v >= 0 && i == v) {
            return intToBinaryString(i);
        }

        int bufLen = 64;  // Max number of binary digits in a long
        char[] buf = new char[bufLen];
        int cursor = bufLen;

        do {
            buf[--cursor] = DIGITS[((int) v) & 1];
        }  while ((v >>>= 1) != 0);

        return new String(buf, cursor, bufLen - cursor);
    }

    public static StringBuilder appendByteAsHex(StringBuilder sb, byte b, boolean upperCase) {
        char[] digits = upperCase ? UPPER_CASE_DIGITS : DIGITS;
        sb.append(digits[(b >> 4) & 0xf]);
        sb.append(digits[b & 0xf]);
        return sb;
    }

    public static String byteToHexString(byte b, boolean upperCase) {
        char[] digits = upperCase ? UPPER_CASE_DIGITS : DIGITS;
        char[] buf = new char[2]; // We always want two digits.
        buf[0] = digits[(b >> 4) & 0xf];
        buf[1] = digits[b & 0xf];
        return new String(buf, 0, 2);
    }

    public static String bytesToHexString(byte[] bytes, boolean upperCase) {
        char[] digits = upperCase ? UPPER_CASE_DIGITS : DIGITS;
        char[] buf = new char[bytes.length * 2];
        int c = 0;
        for (byte b : bytes) {
            buf[c++] = digits[(b >> 4) & 0xf];
            buf[c++] = digits[b & 0xf];
        }
        return new String(buf);
    }

    public static String intToHexString(int i, boolean upperCase, int minWidth) {
        int bufLen = 8;  // Max number of hex digits in an int
        char[] buf = new char[bufLen];
        int cursor = bufLen;

        char[] digits = upperCase ? UPPER_CASE_DIGITS : DIGITS;
        do {
            buf[--cursor] = digits[i & 0xf];
        } while ((i >>>= 4) != 0 || (bufLen - cursor < minWidth));

        return new String(buf, cursor, bufLen - cursor);
    }

    public static String longToHexString(long v) {
        int i = (int) v;
        if (v >= 0 && i == v) {
            return intToHexString(i, false, 0);
        }

        int bufLen = 16;  // Max number of hex digits in a long
        char[] buf = new char[bufLen];
        int cursor = bufLen;

        do {
            buf[--cursor] = DIGITS[((int) v) & 0xF];
        } while ((v >>>= 4) != 0);

        return new String(buf, cursor, bufLen - cursor);
    }

    public static String intToOctalString(int i) {
        int bufLen = 11;  // Max number of octal digits in an int
        char[] buf = new char[bufLen];
        int cursor = bufLen;

        do {
            buf[--cursor] = DIGITS[i & 7];
        } while ((i >>>= 3) != 0);

        return new String(buf, cursor, bufLen - cursor);
    }

    public static String longToOctalString(long v) {
        int i = (int) v;
        if (v >= 0 && i == v) {
            return intToOctalString(i);
        }
        int bufLen = 22;  // Max number of octal digits in a long
        char[] buf = new char[bufLen];
        int cursor = bufLen;

        do {
            buf[--cursor] = DIGITS[((int) v) & 7];
        } while ((v >>>= 3) != 0);

        return new String(buf, cursor, bufLen - cursor);
    }

    private static String stringOf(char... args) {
        return new String(args, 0, args.length);
    }
}
