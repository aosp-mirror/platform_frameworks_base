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

package com.android.mediaframeworktest.unit;

import android.test.suitebuilder.annotation.SmallTest;
import android.util.Range;
import android.util.Rational;

/**
 * <pre>
 * adb shell am instrument \
 *      -e class 'com.android.mediaframeworktest.unit.RangeTest' \
 *      -w com.android.mediaframeworktest/.MediaFrameworkUnitTestRunner
 * </pre>
 */
public class RangeTest extends junit.framework.TestCase {

    @SmallTest
    public void testConstructor() {
        // Trivial, same range
        Range<Integer> intRange = new Range<Integer>(1, 1);

        assertLower(intRange, 1);
        assertUpper(intRange, 1);

        // Different values in range
        Range<Integer> intRange2 = new Range<Integer>(100, 200);
        assertLower(intRange2, 100);
        assertUpper(intRange2, 200);

        Range<Float> floatRange = new Range<Float>(Float.NEGATIVE_INFINITY,
                Float.POSITIVE_INFINITY);
        assertLower(floatRange, Float.NEGATIVE_INFINITY);
        assertUpper(floatRange, Float.POSITIVE_INFINITY);
    }

    @SmallTest
    public void testIllegalValues() {
        // Test NPEs
        try {
            new Range<Integer>(null, null);
            fail("Expected exception to be thrown for (null, null)");
        } catch (NullPointerException e) {
            // OK: both args are null
        }

        try {
            new Range<Integer>(null, 0);
            fail("Expected exception to be thrown for (null, 0)");
        } catch (NullPointerException e) {
            // OK: left arg is null
        }

        try {
            new Range<Integer>(0, null);
            fail("Expected exception to be thrown for (0, null)");
        } catch (NullPointerException e) {
            // OK: right arg is null
        }

        // Test IAEs

        try {
            new Range<Integer>(50, -50);
            fail("Expected exception to be thrown for (50, -50)");
        } catch (IllegalArgumentException e) {
            // OK: 50 > -50 so it fails
        }

        try {
            new Range<Float>(0.0f, Float.NEGATIVE_INFINITY);
            fail("Expected exception to be thrown for (0.0f, -Infinity)");
        } catch (IllegalArgumentException e) {
            // OK: 0.0f is > NEGATIVE_INFINITY, so it fails
        }
    }

    @SmallTest
    public void testEquals() {
        Range<Float> oneHalf = Range.create(1.0f, 2.0f);
        Range<Float> oneHalf2 = new Range<Float>(1.0f, 2.0f);
        assertEquals(oneHalf, oneHalf2);
        assertHashCodeEquals(oneHalf, oneHalf2);

        Range<Float> twoThirds = new Range<Float>(2.0f, 3.0f);
        Range<Float> twoThirds2 = Range.create(2.0f, 3.0f);
        assertEquals(twoThirds, twoThirds2);
        assertHashCodeEquals(twoThirds, twoThirds2);

        Range<Rational> negativeOneTenthPositiveOneTenth =
                new Range<Rational>(new Rational(-1, 10), new Rational(1, 10));
        Range<Rational> negativeOneTenthPositiveOneTenth2 =
                Range.create(new Rational(-1, 10), new Rational(1, 10));
        assertEquals(negativeOneTenthPositiveOneTenth, negativeOneTenthPositiveOneTenth2);
        assertHashCodeEquals(negativeOneTenthPositiveOneTenth, negativeOneTenthPositiveOneTenth2);
    }

    @SmallTest
    public void testInRange() {
        Range<Integer> hundredOneTwo = Range.create(100, 200);

        assertInRange(hundredOneTwo, 100);
        assertInRange(hundredOneTwo, 200);
        assertInRange(hundredOneTwo, 150);
        assertOutOfRange(hundredOneTwo, 99);
        assertOutOfRange(hundredOneTwo, 201);
        assertOutOfRange(hundredOneTwo, 100000);

        Range<Float> infinities = Range.create(Float.NEGATIVE_INFINITY, Float.POSITIVE_INFINITY);

        assertInRange(infinities, Float.NEGATIVE_INFINITY);
        assertInRange(infinities, Float.POSITIVE_INFINITY);
        assertInRange(infinities, 0.0f);
        assertOutOfRange(infinities, Float.NaN);

        Range<Rational> negativeOneTenthPositiveOneTenth =
                new Range<Rational>(new Rational(-1, 10), new Rational(1, 10));
        assertInRange(negativeOneTenthPositiveOneTenth, new Rational(-1, 10));
        assertInRange(negativeOneTenthPositiveOneTenth, new Rational(1, 10));
        assertInRange(negativeOneTenthPositiveOneTenth, Rational.ZERO);
        assertOutOfRange(negativeOneTenthPositiveOneTenth, new Rational(-100, 1));
        assertOutOfRange(negativeOneTenthPositiveOneTenth, new Rational(100, 1));
    }

    private static <T extends Comparable<? super T>> void assertInRange(Range<T> object, T needle) {
        assertAction("in-range", object, needle, true, object.contains(needle));
    }

    private static <T extends Comparable<? super T>> void assertOutOfRange(Range<T> object,
            T needle) {
        assertAction("out-of-range", object, needle, false, object.contains(needle));
    }

    private static <T extends Comparable<? super T>> void assertUpper(Range<T> object, T expected) {
        assertAction("upper", object, expected, object.getUpper());
    }

    private static <T extends Comparable<? super T>> void assertLower(Range<T> object, T expected) {
        assertAction("lower", object, expected, object.getLower());
    }

    private static <T, T2> void assertAction(String action, T object, T2 expected,
            T2 actual) {
        assertEquals("Expected " + object + " " + action + " to be ",
                expected, actual);
    }

    private static <T, T2> void assertAction(String action, T object, T2 needle, boolean expected,
            boolean actual) {
        String expectedMessage = expected ? action : ("not " + action);
        assertEquals("Expected " + needle + " to be " + expectedMessage + " of " + object,
                expected, actual);
    }

    private static <T extends Comparable<? super T>> void assertHashCodeEquals(
            Range<T> left, Range<T> right) {
        assertEquals("Left hash code for " + left +
                " expected to be equal to right hash code for " + right,
                left.hashCode(), right.hashCode());
    }
}
