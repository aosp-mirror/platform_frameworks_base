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
package android.hardware.camera2;

/**
 * The rational data type used by CameraMetadata keys. Contains a pair of ints representing the
 * numerator and denominator of a Rational number. This type is immutable.
 */
public final class Rational {
    private final int mNumerator;
    private final int mDenominator;

    /**
     * <p>Create a Rational with a given numerator and denominator.</p>
     *
     * <p>The signs of the numerator and the denominator may be flipped such that the denominator
     * is always positive.</p>
     *
     * <p>A rational value with a 0-denominator may be constructed, but will have similar semantics
     * as float NaN and INF values. The int getter functions return 0 in this case.</p>
     *
     * @param numerator the numerator of the rational
     * @param denominator the denominator of the rational
     */
    public Rational(int numerator, int denominator) {

        if (denominator < 0) {
            numerator = -numerator;
            denominator = -denominator;
        }

        mNumerator = numerator;
        mDenominator = denominator;
    }

    /**
     * Gets the numerator of the rational.
     */
    public int getNumerator() {
        if (mDenominator == 0) {
            return 0;
        }
        return mNumerator;
    }

    /**
     * Gets the denominator of the rational
     */
    public int getDenominator() {
        return mDenominator;
    }

    private boolean isNaN() {
        return mDenominator == 0 && mNumerator == 0;
    }

    private boolean isInf() {
        return mDenominator == 0 && mNumerator > 0;
    }

    private boolean isNegInf() {
        return mDenominator == 0 && mNumerator < 0;
    }

    /**
     * <p>Compare this Rational to another object and see if they are equal.</p>
     *
     * <p>A Rational object can only be equal to another Rational object (comparing against any other
     * type will return false).</p>
     *
     * <p>A Rational object is considered equal to another Rational object if and only if one of
     * the following holds</p>:
     * <ul><li>Both are NaN</li>
     *     <li>Both are infinities of the same sign</li>
     *     <li>Both have the same numerator and denominator in their reduced form</li>
     * </ul>
     *
     * <p>A reduced form of a Rational is calculated by dividing both the numerator and the
     * denominator by their greatest common divisor.</p>
     *
     * <pre>
     *      (new Rational(1, 2)).equals(new Rational(1, 2)) == true   // trivially true
     *      (new Rational(2, 3)).equals(new Rational(1, 2)) == false  // trivially false
     *      (new Rational(1, 2)).equals(new Rational(2, 4)) == true   // true after reduction
     *      (new Rational(0, 0)).equals(new Rational(0, 0)) == true   // NaN.equals(NaN)
     *      (new Rational(1, 0)).equals(new Rational(5, 0)) == true   // both are +infinity
     *      (new Rational(1, 0)).equals(new Rational(-1, 0)) == false // +infinity != -infinity
     * </pre>
     *
     * @param obj a reference to another object
     *
     * @return A boolean that determines whether or not the two Rational objects are equal.
     */
    @Override
    public boolean equals(Object obj) {
        if (obj == null) {
            return false;
        } else if (obj instanceof Rational) {
            Rational other = (Rational) obj;
            if (mDenominator == 0 || other.mDenominator == 0) {
                if (isNaN() && other.isNaN()) {
                    return true;
                } else if (isInf() && other.isInf() || isNegInf() && other.isNegInf()) {
                    return true;
                } else {
                    return false;
                }
            } else if (mNumerator == other.mNumerator && mDenominator == other.mDenominator) {
                return true;
            } else {
                int thisGcd = gcd();
                int otherGcd = other.gcd();

                int thisNumerator = mNumerator / thisGcd;
                int thisDenominator = mDenominator / thisGcd;

                int otherNumerator = other.mNumerator / otherGcd;
                int otherDenominator = other.mDenominator / otherGcd;

                return (thisNumerator == otherNumerator && thisDenominator == otherDenominator);
            }
        }
        return false;
    }

    @Override
    public String toString() {
        if (isNaN()) {
            return "NaN";
        } else if (isInf()) {
            return "Infinity";
        } else if (isNegInf()) {
            return "-Infinity";
        } else {
            return mNumerator + "/" + mDenominator;
        }
    }

    /**
     * <p>Convert to a floating point representation.</p>
     *
     * @return The floating point representation of this rational number.
     * @hide
     */
    public float toFloat() {
        return (float) mNumerator / (float) mDenominator;
    }

    @Override
    public int hashCode() {
        final long INT_MASK = 0xffffffffL;

        long asLong = INT_MASK & mNumerator;
        asLong <<= 32;

        asLong |= (INT_MASK & mDenominator);

        return ((Long)asLong).hashCode();
    }

    /**
     * Calculates the greatest common divisor using Euclid's algorithm.
     *
     * @return An int value representing the gcd. Always positive.
     * @hide
     */
    public int gcd() {
        /**
         * Non-recursive implementation of Euclid's algorithm:
         *
         *  gcd(a, 0) := a
         *  gcd(a, b) := gcd(b, a mod b)
         *
         */

        int a = mNumerator;
        int b = mDenominator;

        while (b != 0) {
            int oldB = b;

            b = a % b;
            a = oldB;
        }

        return Math.abs(a);
    }
}
