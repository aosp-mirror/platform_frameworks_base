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

import static android.util.Rational.*;

import android.util.Rational;

import androidx.test.filters.SmallTest;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InvalidObjectException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.lang.reflect.Field;

/**
 * <pre>
 * adb shell am instrument \
 *      -e class 'com.android.mediaframeworktest.unit.RationalTest' \
 *      -w com.android.mediaframeworktest/.MediaFrameworkUnitTestRunner
 * </pre>
 */
public class RationalTest extends junit.framework.TestCase {

    /** (1,1) */
    private static final Rational UNIT = new Rational(1, 1);

    /**
     * Test @hide greatest common divisior functionality that cannot be tested in CTS.
     */
    @SmallTest
    public void testGcd() {
        assertEquals(1, Rational.gcd(1, 2));
        assertEquals(1, Rational.gcd(2, 3));
        assertEquals(78, Rational.gcd(5*78, 7*78));
        assertEquals(1, Rational.gcd(-1, 2));
        assertEquals(1, Rational.gcd(-2, 3));
    }

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
        assertEquals(1, r.getNumerator());
        assertEquals(0, r.getDenominator());

        // Negative infinity.
        r = new Rational(-1, 0);
        assertEquals(-1, r.getNumerator());
        assertEquals(0, r.getDenominator());

        // NaN.
        r = new Rational(0, 0);
        assertEquals(0, r.getNumerator());
        assertEquals(0, r.getDenominator());
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

        // Zero is always equal to itself
        Rational zero2 = new Rational(0, 100);
        assertEquals(ZERO, zero2);
        assertEquals(zero2, ZERO);

        // NaN is always equal to itself
        Rational nan = NaN;
        Rational nan2 = new Rational(0, 0);
        assertTrue(nan.equals(nan));
        assertTrue(nan.equals(nan2));
        assertTrue(nan2.equals(nan));
        assertFalse(nan.equals(r));
        assertFalse(r.equals(nan));

        // Infinities of the same sign are equal.
        Rational posInf = POSITIVE_INFINITY;
        Rational posInf2 = new Rational(2, 0);
        Rational negInf = NEGATIVE_INFINITY;
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

    @SmallTest
    public void testReduction() {
        Rational moreComplicated = new Rational(5 * 78, 7 * 78);
        assertEquals(new Rational(5, 7), moreComplicated);
        assertEquals(5, moreComplicated.getNumerator());
        assertEquals(7, moreComplicated.getDenominator());

        Rational posInf = new Rational(5, 0);
        assertEquals(1, posInf.getNumerator());
        assertEquals(0, posInf.getDenominator());
        assertEquals(POSITIVE_INFINITY, posInf);

        Rational negInf = new Rational(-100, 0);
        assertEquals(-1, negInf.getNumerator());
        assertEquals(0, negInf.getDenominator());
        assertEquals(NEGATIVE_INFINITY, negInf);

        Rational zero = new Rational(0, -100);
        assertEquals(0, zero.getNumerator());
        assertEquals(1, zero.getDenominator());
        assertEquals(ZERO, zero);

        Rational flipSigns = new Rational(1, -1);
        assertEquals(-1, flipSigns.getNumerator());
        assertEquals(1, flipSigns.getDenominator());

        Rational flipAndReduce = new Rational(100, -200);
        assertEquals(-1, flipAndReduce.getNumerator());
        assertEquals(2, flipAndReduce.getDenominator());
    }

    @SmallTest
    public void testCompareTo() {
        // unit is equal to itself
        assertCompareEquals(UNIT, new Rational(1, 1));

        // NaN is greater than anything but NaN
        assertCompareEquals(NaN, new Rational(0, 0));
        assertGreaterThan(NaN, UNIT);
        assertGreaterThan(NaN, POSITIVE_INFINITY);
        assertGreaterThan(NaN, NEGATIVE_INFINITY);
        assertGreaterThan(NaN, ZERO);

        // Positive infinity is greater than any other non-NaN
        assertCompareEquals(POSITIVE_INFINITY, new Rational(1, 0));
        assertGreaterThan(POSITIVE_INFINITY, UNIT);
        assertGreaterThan(POSITIVE_INFINITY, NEGATIVE_INFINITY);
        assertGreaterThan(POSITIVE_INFINITY, ZERO);

        // Negative infinity is smaller than any other non-NaN
        assertCompareEquals(NEGATIVE_INFINITY, new Rational(-1, 0));
        assertLessThan(NEGATIVE_INFINITY, UNIT);
        assertLessThan(NEGATIVE_INFINITY, POSITIVE_INFINITY);
        assertLessThan(NEGATIVE_INFINITY, ZERO);

        // A finite number with the same denominator is trivially comparable
        assertGreaterThan(new Rational(3, 100), new Rational(1, 100));
        assertGreaterThan(new Rational(3, 100), ZERO);

        // Compare finite numbers with different divisors
        assertGreaterThan(new Rational(5, 25), new Rational(1, 10));
        assertGreaterThan(new Rational(5, 25), ZERO);

        // Compare finite numbers with different signs
        assertGreaterThan(new Rational(5, 25), new Rational(-1, 10));
        assertLessThan(new Rational(-5, 25), ZERO);
    }

    @SmallTest
    public void testConvenienceMethods() {
        // isFinite
        assertFinite(ZERO, true);
        assertFinite(NaN, false);
        assertFinite(NEGATIVE_INFINITY, false);
        assertFinite(POSITIVE_INFINITY, false);
        assertFinite(UNIT, true);

        // isInfinite
        assertInfinite(ZERO, false);
        assertInfinite(NaN, false);
        assertInfinite(NEGATIVE_INFINITY, true);
        assertInfinite(POSITIVE_INFINITY, true);
        assertInfinite(UNIT, false);

        // isNaN
        assertNaN(ZERO, false);
        assertNaN(NaN, true);
        assertNaN(NEGATIVE_INFINITY, false);
        assertNaN(POSITIVE_INFINITY, false);
        assertNaN(UNIT, false);

        // isZero
        assertZero(ZERO, true);
        assertZero(NaN, false);
        assertZero(NEGATIVE_INFINITY, false);
        assertZero(POSITIVE_INFINITY, false);
        assertZero(UNIT, false);
    }

    @SmallTest
    public void testValueConversions() {
        // Unit, simple case
        assertValueEquals(UNIT, 1.0f);
        assertValueEquals(UNIT, 1.0);
        assertValueEquals(UNIT, 1L);
        assertValueEquals(UNIT, 1);
        assertValueEquals(UNIT, (short)1);

        // Zero, simple case
        assertValueEquals(ZERO, 0.0f);
        assertValueEquals(ZERO, 0.0);
        assertValueEquals(ZERO, 0L);
        assertValueEquals(ZERO, 0);
        assertValueEquals(ZERO, (short)0);

        // NaN is 0 for integers, not-a-number for floating point
        assertValueEquals(NaN, Float.NaN);
        assertValueEquals(NaN, Double.NaN);
        assertValueEquals(NaN, 0L);
        assertValueEquals(NaN, 0);
        assertValueEquals(NaN, (short)0);

        // Positive infinity, saturates upwards for integers
        assertValueEquals(POSITIVE_INFINITY, Float.POSITIVE_INFINITY);
        assertValueEquals(POSITIVE_INFINITY, Double.POSITIVE_INFINITY);
        assertValueEquals(POSITIVE_INFINITY, Long.MAX_VALUE);
        assertValueEquals(POSITIVE_INFINITY, Integer.MAX_VALUE);
        assertValueEquals(POSITIVE_INFINITY, (short)-1);

        // Negative infinity, saturates downwards for integers
        assertValueEquals(NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY);
        assertValueEquals(NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY);
        assertValueEquals(NEGATIVE_INFINITY, Long.MIN_VALUE);
        assertValueEquals(NEGATIVE_INFINITY, Integer.MIN_VALUE);
        assertValueEquals(NEGATIVE_INFINITY, (short)0);

        // Normal finite values, round down for integers
        final Rational oneQuarter = new Rational(1, 4);
        assertValueEquals(oneQuarter, 1.0f / 4.0f);
        assertValueEquals(oneQuarter, 1.0 / 4.0);
        assertValueEquals(oneQuarter, 0L);
        assertValueEquals(oneQuarter, 0);
        assertValueEquals(oneQuarter, (short)0);

        final Rational nineFifths = new Rational(9, 5);
        assertValueEquals(nineFifths, 9.0f / 5.0f);
        assertValueEquals(nineFifths, 9.0 / 5.0);
        assertValueEquals(nineFifths, 1L);
        assertValueEquals(nineFifths, 1);
        assertValueEquals(nineFifths, (short)1);

        final Rational negativeHundred = new Rational(-1000, 10);
        assertValueEquals(negativeHundred, -100.f / 1.f);
        assertValueEquals(negativeHundred, -100.0 / 1.0);
        assertValueEquals(negativeHundred, -100L);
        assertValueEquals(negativeHundred, -100);
        assertValueEquals(negativeHundred, (short)-100);

        // Short truncates if the result is too large
        assertValueEquals(new Rational(Integer.MAX_VALUE, 1), (short)Integer.MAX_VALUE);
        assertValueEquals(new Rational(0x00FFFFFF, 1), (short)0x00FFFFFF);
        assertValueEquals(new Rational(0x00FF00FF, 1), (short)0x00FF00FF);
    }

    @SmallTest
    public void testSerialize() throws ClassNotFoundException, IOException {
        /*
         * Check correct [de]serialization
         */
        assertEqualsAfterSerializing(ZERO);
        assertEqualsAfterSerializing(NaN);
        assertEqualsAfterSerializing(NEGATIVE_INFINITY);
        assertEqualsAfterSerializing(POSITIVE_INFINITY);
        assertEqualsAfterSerializing(UNIT);
        assertEqualsAfterSerializing(new Rational(100, 200));
        assertEqualsAfterSerializing(new Rational(-100, 200));
        assertEqualsAfterSerializing(new Rational(5, 1));
        assertEqualsAfterSerializing(new Rational(Integer.MAX_VALUE, Integer.MIN_VALUE));

        /*
         * Check bad deserialization fails
         */
        try {
            Rational badZero = createIllegalRational(0, 100); // [0, 100] , should be [0, 1]
            Rational results = serializeRoundTrip(badZero);
            fail("Deserializing " + results + " should not have succeeded");
        } catch (InvalidObjectException e) {
            // OK
        }

        try {
            Rational badPosInfinity = createIllegalRational(100, 0); // [100, 0] , should be [1, 0]
            Rational results = serializeRoundTrip(badPosInfinity);
            fail("Deserializing " + results + " should not have succeeded");
        } catch (InvalidObjectException e) {
            // OK
        }

        try {
            Rational badNegInfinity =
                    createIllegalRational(-100, 0); // [-100, 0] , should be [-1, 0]
            Rational results = serializeRoundTrip(badNegInfinity);
            fail("Deserializing " + results + " should not have succeeded");
        } catch (InvalidObjectException e) {
            // OK
        }

        try {
            Rational badReduced = createIllegalRational(2, 4); // [2,4] , should be [1, 2]
            Rational results = serializeRoundTrip(badReduced);
            fail("Deserializing " + results + " should not have succeeded");
        } catch (InvalidObjectException e) {
            // OK
        }

        try {
            Rational badReducedNeg = createIllegalRational(-2, 4); // [-2, 4] should be [-1, 2]
            Rational results = serializeRoundTrip(badReducedNeg);
            fail("Deserializing " + results + " should not have succeeded");
        } catch (InvalidObjectException e) {
            // OK
        }
    }

    private static void assertValueEquals(Rational object, float expected) {
        assertEquals("Checking floatValue() for " + object + ";",
                expected, object.floatValue());
    }

    private static void assertValueEquals(Rational object, double expected) {
        assertEquals("Checking doubleValue() for " + object + ";",
                expected, object.doubleValue());
    }

    private static void assertValueEquals(Rational object, long expected) {
        assertEquals("Checking longValue() for " + object + ";",
                expected, object.longValue());
    }

    private static void assertValueEquals(Rational object, int expected) {
        assertEquals("Checking intValue() for " + object + ";",
                expected, object.intValue());
    }

    private static void assertValueEquals(Rational object, short expected) {
        assertEquals("Checking shortValue() for " + object + ";",
                expected, object.shortValue());
    }

    private static void assertFinite(Rational object, boolean expected) {
        assertAction("finite", object, expected, object.isFinite());
    }

    private static void assertInfinite(Rational object, boolean expected) {
        assertAction("infinite", object, expected, object.isInfinite());
    }

    private static void assertNaN(Rational object, boolean expected) {
        assertAction("NaN", object, expected, object.isNaN());
    }

    private static void assertZero(Rational object, boolean expected) {
        assertAction("zero", object, expected, object.isZero());
    }

    private static <T> void assertAction(String action, T object, boolean expected,
            boolean actual) {
        String expectedMessage = expected ? action : ("not " + action);
        assertEquals("Expected " + object + " to be " + expectedMessage,
                expected, actual);
    }

    private static <T extends Comparable<? super T>> void assertLessThan(T left, T right) {
        assertTrue("Expected (LR) left " + left + " to be less than right " + right,
                left.compareTo(right) < 0);
        assertTrue("Expected (RL) left " + left + " to be less than right " + right,
                right.compareTo(left) > 0);
    }

    private static <T extends Comparable<? super T>> void assertGreaterThan(T left, T right) {
        assertTrue("Expected (LR) left " + left + " to be greater than right " + right,
                left.compareTo(right) > 0);
        assertTrue("Expected (RL) left " + left + " to be greater than right " + right,
                right.compareTo(left) < 0);
    }

    private static <T extends Comparable<? super T>> void assertCompareEquals(T left, T right) {
        assertTrue("Expected (LR) left " + left + " to be compareEquals to right " + right,
                left.compareTo(right) == 0);
        assertTrue("Expected (RL) left " + left + " to be compareEquals to right " + right,
                right.compareTo(left) == 0);
    }

    private static <T extends Serializable> byte[] serialize(T obj) throws IOException {
        ByteArrayOutputStream byteStream = new ByteArrayOutputStream();
        try (ObjectOutputStream objectStream = new ObjectOutputStream(byteStream)) {
            objectStream.writeObject(obj);
        }
        return byteStream.toByteArray();
    }

    private static <T extends Serializable> T deserialize(byte[] array, Class<T> klass)
            throws IOException, ClassNotFoundException {
        ByteArrayInputStream bais = new ByteArrayInputStream(array);
        ObjectInputStream ois = new ObjectInputStream(bais);
        Object obj = ois.readObject();
        return klass.cast(obj);
    }

    @SuppressWarnings("unchecked")
    private static <T extends Serializable> T serializeRoundTrip(T obj)
            throws IOException, ClassNotFoundException {
        Class<T> klass = (Class<T>) obj.getClass();
        byte[] arr = serialize(obj);
        T serialized = deserialize(arr, klass);
        return serialized;
    }

    private static <T extends Serializable> void assertEqualsAfterSerializing(T obj)
            throws ClassNotFoundException, IOException {
        T serialized = serializeRoundTrip(obj);
        assertEquals("Expected values to be equal after serialization round-trip", obj, serialized);
    }

    private static Rational createIllegalRational(int numerator, int denominator) {
        Rational r = new Rational(numerator, denominator);
        mutateField(r, "mNumerator", numerator);
        mutateField(r, "mDenominator", denominator);
        return r;
    }

    private static <T> void mutateField(T object, String name, int value) {
        try {
            Field f = object.getClass().getDeclaredField(name);
            f.setAccessible(true);
            f.set(object, value);
        } catch (NoSuchFieldException e) {
            throw new AssertionError(e);
        } catch (IllegalAccessException e) {
            throw new AssertionError(e);
        } catch (IllegalArgumentException e) {
            throw new AssertionError(e);
        }
    }
}
