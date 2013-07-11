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
package android.hardware.photography;

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
     * <p>
     * The signs of the numerator and the denominator may be flipped such that the denominator
     * is always 0.
     * </p>
     *
     * @param numerator the numerator of the rational
     * @param denominator the denominator of the rational
     *
     * @throws IllegalArgumentException if the denominator is 0
     */
    public Rational(int numerator, int denominator) {

        if (denominator == 0) {
            throw new IllegalArgumentException("Argument 'denominator' is 0");
        }

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
        return mNumerator;
    }

    /**
     * Gets the denominator of the rational
     */
    public int getDenominator() {
        return mDenominator;
    }

    /**
     * <p>Compare this Rational to another object and see if they are equal.</p>
     *
     * <p>A Rational object can only be equal to another Rational object (comparing against any other
     * type will return false).</p>
     *
     * <p>A Rational object is considered equal to another Rational object if and only if their
     * reduced forms have the same numerator and denominator.</p>
     *
     * <p>A reduced form of a Rational is calculated by dividing both the numerator and the
     * denominator by their greatest common divisor.</p>
     *
     * <pre>
     *      (new Rational(1, 2)).equals(new Rational(1, 2)) == true  // trivially true
     *      (new Rational(2, 3)).equals(new Rational(1, 2)) == false // trivially false
     *      (new Rational(1, 2)).equals(new Rational(2, 4)) == true  // true after reduction
     * </pre>
     *
     * @param obj a reference to another object
     *
     * @return boolean that determines whether or not the two Rational objects are equal.
     */
    @Override
    public boolean equals(Object obj) {
        if (obj == null) {
            return false;
        }
        if (this == obj) {
            return true;
        }
        if (obj instanceof Rational) {
            Rational other = (Rational) obj;
            if(mNumerator == other.mNumerator && mDenominator == other.mDenominator) {
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
        return mNumerator + "/" + mDenominator;
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
     * @return int value representing the gcd. Always positive.
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
