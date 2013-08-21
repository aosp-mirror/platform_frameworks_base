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

package com.android.mediaframeworktest.unit;

import android.test.suitebuilder.annotation.SmallTest;
import android.hardware.camera2.Rational;

/**
 * <pre>
 * adb shell am instrument \
 *      -e class 'com.android.mediaframeworktest.unit.RationalTest' \
 *      -w com.android.mediaframeworktest/.MediaFrameworkUnitTestRunner
 * </pre>
 */
public class RationalTest extends junit.framework.TestCase {
    @SmallTest
    public void testConstructor() {

        // Simple case
        Rational r = new Rational(1, 2);
        assertEquals(1, r.getNumerator());
        assertEquals(2, r.getDenominator());

        // Denominator negative
        r = new Rational(-1, 2);
        assertEquals(-1, r.getNumerator());
        assertEquals(2, r.getDenominator());

        // Numerator negative
        r = new Rational(1, -2);
        assertEquals(-1, r.getNumerator());
        assertEquals(2, r.getDenominator());

        // Both negative
        r = new Rational(-1, -2);
        assertEquals(1, r.getNumerator());
        assertEquals(2, r.getDenominator());

        // Infinity.
        r = new Rational(1, 0);
        assertEquals(0, r.getNumerator());
        assertEquals(0, r.getDenominator());

        // Negative infinity.
        r = new Rational(-1, 0);
        assertEquals(0, r.getNumerator());
        assertEquals(0, r.getDenominator());

        // NaN.
        r = new Rational(0, 0);
        assertEquals(0, r.getNumerator());
        assertEquals(0, r.getDenominator());
    }

    @SmallTest
    public void testGcd() {
        Rational r = new Rational(1, 2);
        assertEquals(1, r.gcd());

        Rational twoThirds = new Rational(2, 3);
        assertEquals(1, twoThirds.gcd());

        Rational moreComplicated2 = new Rational(5*78, 7*78);
        assertEquals(78, moreComplicated2.gcd());

        Rational oneHalf = new Rational(-1, 2);
        assertEquals(1, oneHalf.gcd());

        twoThirds = new Rational(-2, 3);
        assertEquals(1, twoThirds.gcd());
    }

    @SmallTest
    public void testEquals() {
        Rational r = new Rational(1, 2);
        assertEquals(1, r.getNumerator());
        assertEquals(2, r.getDenominator());

        assertEquals(r, r);
        assertFalse(r.equals(null));
        assertFalse(r.equals(new Object()));

        Rational twoThirds = new Rational(2, 3);
        assertFalse(r.equals(twoThirds));
        assertFalse(twoThirds.equals(r));

        Rational fourSixths = new Rational(4, 6);
        assertEquals(twoThirds, fourSixths);
        assertEquals(fourSixths, twoThirds);

        Rational moreComplicated = new Rational(5*6*7*8*9, 1*2*3*4*5);
        Rational moreComplicated2 = new Rational(5*6*7*8*9*78, 1*2*3*4*5*78);
        assertEquals(moreComplicated, moreComplicated2);
        assertEquals(moreComplicated2, moreComplicated);

        // Ensure negatives are fine
        twoThirds = new Rational(-2, 3);
        fourSixths = new Rational(-4, 6);
        assertEquals(twoThirds, fourSixths);
        assertEquals(fourSixths, twoThirds);

        moreComplicated = new Rational(-5*6*7*8*9, 1*2*3*4*5);
        moreComplicated2 = new Rational(-5*6*7*8*9*78, 1*2*3*4*5*78);
        assertEquals(moreComplicated, moreComplicated2);
        assertEquals(moreComplicated2, moreComplicated);

        Rational nan = new Rational(0, 0);
        Rational nan2 = new Rational(0, 0);
        assertTrue(nan.equals(nan));
        assertTrue(nan.equals(nan2));
        assertTrue(nan2.equals(nan));
        assertFalse(nan.equals(r));
        assertFalse(r.equals(nan));

        // Infinities of the same sign are equal.
        Rational posInf = new Rational(1, 0);
        Rational posInf2 = new Rational(2, 0);
        Rational negInf = new Rational(-1, 0);
        Rational negInf2 = new Rational(-2, 0);
        assertEquals(posInf, posInf);
        assertEquals(negInf, negInf);
        assertEquals(posInf, posInf2);
        assertEquals(negInf, negInf2);

        // Infinities aren't equal to anything else.
        assertFalse(posInf.equals(negInf));
        assertFalse(negInf.equals(posInf));
        assertFalse(negInf.equals(r));
        assertFalse(posInf.equals(r));
        assertFalse(r.equals(negInf));
        assertFalse(r.equals(posInf));
        assertFalse(posInf.equals(nan));
        assertFalse(negInf.equals(nan));
        assertFalse(nan.equals(posInf));
        assertFalse(nan.equals(negInf));
    }
}
