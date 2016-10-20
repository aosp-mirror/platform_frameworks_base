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

package android.util;

/**
 * <p>Half is a utility class to manipulate half-precision 16-bit
 * <a href="https://en.wikipedia.org/wiki/Half-precision_floating-point_format">IEEE 754</a>
 * floating point data types (also called fp16 or binary16). A half-precision
 * float is stored in a short data type. A half-precision float can be
 * created from or converted to single-precision floats.</p>
 *
 * <p>The IEEE 754 standard specifies an fp16 as having the following format:</p>
 * <ul>
 * <li>Sign bit: 1 bit</li>
 * <li>Exponent width: 5 bits</li>
 * <li>Mantissa: 10 bits</li>
 * </ul>
 *
 * <p>The format is laid out thusly:</p>
 * <pre>
 * 1   11111   1111111111
 * ^   --^--   -----^----
 * sign  |          |_______ mantissa
 *       |
 *       -- exponent
 * </pre>
 *
 * @hide
 */
public final class Half {
    /**
     * The number of bits used to represent a half-precision float value.
     */
    public static final int SIZE = 16;

    /**
     * Epsilon is the difference between 1.0 and the next value representable
     * by a half-precision floating-point.
     */
    public static final short EPSILON            = (short) 0x1400;
    /**
     * Smallest negative value a half-precision float may have.
     */
    public static final short LOWEST_VALUE       = (short) 0xfbff;
    /**
     * Maximum exponent a finite half-precision float may have.
     */
    public static final short MAX_EXPONENT       = 15;
    /**
     * Maximum positive finite value a half-precision float may have.
     */
    public static final short MAX_VALUE          = (short) 0x7bff;
    /**
     * Minimum exponent a normalized half-precision float may have.
     */
    public static final short MIN_EXPONENT       = -14;
    /**
     * Smallest positive normal value a half-precision float may have.
     */
    public static final short MIN_NORMAL         = (short) 0x0400;
    /**
     * Smallest positive non-zero value a half-precision float may have.
     */
    public static final short MIN_VALUE          = (short) 0x0001;
    /**
     * A Not-a-Number representation of a half-precision float.
     */
    public static final short NaN                = (short) 0x7e00;
    /**
     * Negative infinity of type half-precision float.
     */
    public static final short NEGATIVE_INFINITY  = (short) 0xfc00;
    /**
     * Negative 0 of type half-precision float.
     */
    public static final short NEGATIVE_ZERO      = (short) 0x8000;
    /**
     * Positive infinity of type half-precision float.
     */
    public static final short POSITIVE_INFINITY  = (short) 0x7c00;
    /**
     * Positive 0 of type half-precision float.
     */
    public static final short POSITIVE_ZERO      = (short) 0x0000;

    private static final int FP16_SIGN_SHIFT     = 15;
    private static final int FP16_EXPONENT_SHIFT = 10;
    private static final int FP16_EXPONENT_MASK  = 0x1f;
    private static final int FP16_MANTISSA_MASK  = 0x3ff;
    private static final int FP16_EXPONENT_BIAS  = 15;

    private static final int FP32_SIGN_SHIFT     = 31;
    private static final int FP32_EXPONENT_SHIFT = 23;
    private static final int FP32_EXPONENT_MASK  = 0xff;
    private static final int FP32_MANTISSA_MASK  = 0x7fffff;
    private static final int FP32_EXPONENT_BIAS  = 127;

    private static final int   FP32_DENORMAL_MAGIC = 126 << 23;
    private static final float FP32_DENORMAL_FLOAT =
            Float.intBitsToFloat(FP32_DENORMAL_MAGIC);

    private Half() {
    }

    /**
     * Returns the sign of the specified half-precision float.
     *
     * @param h A half-precision float value
     * @return 1 if the value is positive, -1 if the value is negative
     */
    public static int getSign(short h) {
        return (h >>> FP16_SIGN_SHIFT) == 0 ? 1 : -1;
    }

    /**
     * Returns the unbiased exponent used in the representation of
     * the specified  half-precision float value. if the value is NaN
     * or infinite, this* method returns {@link #MAX_EXPONENT} + 1.
     * If the argument is* 0 or denormal, this method returns
     * {@link #MIN_EXPONENT} - 1.
     *
     * @param h A half-precision float value
     * @return The unbiased exponent of the specified value
     */
    public static int getExponent(short h) {
        return ((h >>> FP16_EXPONENT_SHIFT) & FP16_EXPONENT_MASK) - FP16_EXPONENT_BIAS;
    }

    /**
     * Returns the mantissa, or significand, used in the representation
     * of the specified half-precision float value.
     *
     * @param h A half-precision float value
     * @return The mantissa, or significand, of the specified vlaue
     */
    public static int getMantissa(short h) {
        return h & FP16_MANTISSA_MASK;
    }

    /**
     * Returns true if the specified half-precision float value represents
     * infinity, false otherwise.
     *
     * @param h A half-precision float value
     * @return true if the value is positive infinity or negative infinity,
     *         false otherwise
     */
    public static boolean isInfinite(short h) {
        int e = (h >>> FP16_EXPONENT_SHIFT) & FP16_EXPONENT_MASK;
        int m = (h                        ) & FP16_MANTISSA_MASK;
        return e == 0x1f && m == 0;
    }

    /**
     * Returns true if the specified half-precision float value represents
     * a Not-a-Number, false otherwise.
     *
     * @param h A half-precision float value
     * @return true if the value is a NaN, false otherwise
     */
    public static boolean isNaN(short h) {
        int e = (h >>> FP16_EXPONENT_SHIFT) & FP16_EXPONENT_MASK;
        int m = (h                        ) & FP16_MANTISSA_MASK;
        return e == 0x1f && m != 0;
    }

    /**
     * <p>Converts the specified half-precision float value into a
     * single-precision float value with the following special cases:</p>
     * <ul>
     * <li>If the input is {@link #NaN}, the returned* value is {@link Float#NaN}</li>
     * <li>If the input is {@link #POSITIVE_INFINITY} or
     * {@link #NEGATIVE_INFINITY}, the returned value is respectively
     * {@link Float#POSITIVE_INFINITY} or {@link Float#NEGATIVE_INFINITY}</li>
     * <li>If the input is 0 (positive or negative), the returned value is +/-0.0f</li>
     * <li>Otherwise, the returned value is a normalized single-precision float value</li>
     * </ul>
     *
     * @param h The half-precision float value to convert to single-precision
     * @return A normalized single-precision float value
     */
    public static float toFloat(short h) {
        int bits = h & 0xffff;
        int s = (bits >>> FP16_SIGN_SHIFT    );
        int e = (bits >>> FP16_EXPONENT_SHIFT) & FP16_EXPONENT_MASK;
        int m = (bits                        ) & FP16_MANTISSA_MASK;

        int outE = 0;
        int outM = 0;

        if (e == 0) { // Denormal or 0
            if (m != 0) {
                // Convert denorm fp16 into normalized fp32
                float o = Float.intBitsToFloat(FP32_DENORMAL_MAGIC + m);
                o -= FP32_DENORMAL_FLOAT;
                return s == 0 ? o : -o;
            }
        } else {
            outM = m << 13;
            if (e == 0x1f) { // Infinite or NaN
                outE = 0xff;
            } else {
                outE = e - FP16_EXPONENT_BIAS + FP32_EXPONENT_BIAS;
            }
        }

        int out = (s << FP32_SIGN_SHIFT) | (outE << FP32_EXPONENT_SHIFT) | outM;
        return Float.intBitsToFloat(out);
    }

    /**
     * <p>Converts the specified single-precision float value into a
     * half-precision float value with the following special cases:</p>
     * <ul>
     * <li>If the input is NaN (see {@link Float#isNaN(float)}), the returned
     * value is {@link #NaN}</li>
     * <li>If the input is {@link Float#POSITIVE_INFINITY} or
     * {@link Float#NEGATIVE_INFINITY}, the returned value is respectively
     * {@link #POSITIVE_INFINITY} or {@link #NEGATIVE_INFINITY}</li>
     * <li>If the input is 0 (positive or negative), the returned value is
     * {@link #POSITIVE_ZERO} or {@link #NEGATIVE_ZERO}</li>
     * <li>If the input is a less than {@link #MIN_VALUE}, the returned value
     * is flushed to {@link #POSITIVE_ZERO} or {@link #NEGATIVE_ZERO}</li>
     * <li>If the input is a less than {@link #MIN_NORMAL}, the returned value
     * is a denorm half-precision float</li>
     * <li>Otherwise, the returned value is rounded to the nearest
     * representable half-precision float value</li>
     * </ul>
     *
     * @param f The single-precision float value to convert to half-precision
     * @return A half-precision float value
     */
    @SuppressWarnings("StatementWithEmptyBody")
    public static short valueOf(float f) {
        int bits = Float.floatToRawIntBits(f);
        int s = (bits >>> FP32_SIGN_SHIFT    );
        int e = (bits >>> FP32_EXPONENT_SHIFT) & FP32_EXPONENT_MASK;
        int m = (bits                        ) & FP32_MANTISSA_MASK;

        int outE = 0;
        int outM = 0;

        if (e == 0xff) { // Infinite or NaN
            outE = 0x1f;
            outM = m != 0 ? 0x200 : 0;
        } else {
            e = e - FP32_EXPONENT_BIAS + FP16_EXPONENT_BIAS;
            if (e >= 0x1f) { // Overflow
                outE = 0x31;
            } else if (e <= 0) { // Underflow
                if (e < -10) {
                    // The absolute fp32 value is less than MIN_VALUE, flush to +/-0
                } else {
                    // The fp32 value is a normalized float less than MIN_NORMAL,
                    // we convert to a denorm fp16
                    m = (m | 0x800000) >> (1 - e);
                    if ((m & 0x1000) != 0) m += 0x2000;
                    outM = m >> 13;
                }
            } else {
                outE = e;
                outM = m >> 13;
                if ((m & 0x1000) != 0) {
                    // Round to nearest "0.5" up
                    int out = (outE << FP16_EXPONENT_SHIFT) | outM;
                    out++;
                    out |= (s << FP16_SIGN_SHIFT);
                    return (short) out;
                }
            }
        }

        int out = (s << FP16_SIGN_SHIFT) | (outE << FP16_EXPONENT_SHIFT) | outM;
        return (short) out;
    }

    /**
     * Returns a string representation of the specified half-precision
     * float value. Calling this method is equivalent to calling
     * <code>Float.toString(toFloat(h))</code>. See {@link Float#toString(float)}
     * for more information on the format of the string representation.
     *
     * @param h A half-precision float value
     * @return A string representation of the specified value
     */
    public static String toString(short h) {
        return Float.toString(toFloat(h));
    }

    /**
     * <p>Returns a hexadecimal string representation of the specified half-precision
     * float value. If the value is a NaN, the result is <code>"NaN"</code>,
     * otherwise the result follows this format:</p>
     * <ul>
     * <li>If the sign is positive, no sign character appears in the result</li>
     * <li>If the sign is negative, the first character is <code>'-'</code></li>
     * <li>If the value is inifinity, the string is <code>"Infinity"</code></li>
     * <li>If the value is 0, the string is <code>"0x0.0p0"</code></li>
     * <li>If the value has a normalized representation, the exponent and
     * mantissa are represented in the string in two fields. The mantissa starts
     * with <code>"0x1."</code> followed by its lowercase hexadecimal
     * representation. Trailing zeroes are removed unless all digits are 0, then
     * a single zero is used. The mantissa representation is followed by the
     * exponent, represented by <code>"p"</code>, itself followed by a decimal
     * string of the unbiased exponent</li>
     * <li>If the value has a denormal representation, the mantissa starts
     * with <code>"0x0."</code> followed by its lowercase hexadecimal
     * representation. Trailing zeroes are removed unless all digits are 0, then
     * a single zero is used. The mantissa representation is followed by the
     * exponent, represented by <code>"p-14"</code></li>
     * </ul>
     *
     * @param h A half-precision float value
     * @return A hexadecimal string representation of the specified value
     */
    public static String toHexString(short h) {
        StringBuilder o = new StringBuilder();

        int bits = h & 0xffff;
        int s = (bits >>> FP16_SIGN_SHIFT    );
        int e = (bits >>> FP16_EXPONENT_SHIFT) & FP16_EXPONENT_MASK;
        int m = (bits                        ) & FP16_MANTISSA_MASK;

        if (e == 0x1f) { // Infinite or NaN
            if (m == 0) {
                if (s == 1) o.append('-');
                o.append("Infinity");
            } else {
                o.append("NaN");
            }
        } else {
            if (s == 1) o.append('-');
            if (e == 0) {
                if (m == 0) {
                    o.append("0x0.0p0");
                } else {
                    o.append("0x0.");
                    String mantissa = Integer.toHexString(m);
                    o.append(mantissa.replaceFirst("0{2,}$", ""));
                    o.append("p-14");
                }
            } else {
                o.append("0x1.");
                String mantissa = Integer.toHexString(m);
                o.append(mantissa.replaceFirst("0{2,}$", ""));
                o.append('p');
                o.append(Integer.toString(e - FP16_EXPONENT_BIAS));
            }
        }

        return o.toString();
    }
}
