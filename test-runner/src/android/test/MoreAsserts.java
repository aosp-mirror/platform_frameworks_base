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

package android.test;

import com.google.android.collect.Lists;
import junit.framework.Assert;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.ArrayList;
import java.util.regex.MatchResult;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Contains additional assertion methods not found in JUnit.
 */
public final class MoreAsserts {

    private MoreAsserts() { }

    /**
     * Asserts that the class  {@code expected} is assignable from the object
     * {@code actual}. This verifies {@code expected} is a parent class or a
     * interface that {@code actual} implements.
     */
    public static void assertAssignableFrom(Class<?> expected, Object actual) {
        assertAssignableFrom(expected, actual.getClass());
    }

    /**
     * Asserts that class {@code expected} is assignable from the class
     * {@code actual}. This verifies {@code expected} is a parent class or a
     * interface that {@code actual} implements.
     */
    public static void assertAssignableFrom(Class<?> expected, Class<?> actual) {
        Assert.assertTrue(
                "Expected " + expected.getCanonicalName() +
                        " to be assignable from actual class " + actual.getCanonicalName(),
                expected.isAssignableFrom(actual));
    }

    /**
     * Asserts that {@code actual} is not equal {@code unexpected}, according
     * to both {@code ==} and {@link Object#equals}.
     */
    public static void assertNotEqual(
            String message, Object unexpected, Object actual) {
        if (equal(unexpected, actual)) {
            failEqual(message, unexpected);
        }
    }

    /**
     * Variant of {@link #assertNotEqual(String,Object,Object)} using a
     * generic message.
     */
    public static void assertNotEqual(Object unexpected, Object actual) {
        assertNotEqual(null, unexpected, actual);
    }

    /**
     * Asserts that array {@code actual} is the same size and every element equals
     * those in array {@code expected}. On failure, message indicates specific
     * element mismatch.
     */
    public static void assertEquals(
            String message, byte[] expected, byte[] actual) {
        if (expected.length != actual.length) {
            failWrongLength(message, expected.length, actual.length);
        }
        for (int i = 0; i < expected.length; i++) {
            if (expected[i] != actual[i]) {
                failWrongElement(message, i, expected[i], actual[i]);
            }
        }
    }

    /**
     * Asserts that array {@code actual} is the same size and every element equals
     * those in array {@code expected}. On failure, message indicates specific
     * element mismatch.
     */
    public static void assertEquals(byte[] expected, byte[] actual) {
        assertEquals(null, expected, actual);
    }

    /**
     * Asserts that array {@code actual} is the same size and every element equals
     * those in array {@code expected}. On failure, message indicates first
     * specific element mismatch.
     */
    public static void assertEquals(
            String message, int[] expected, int[] actual) {
        if (expected.length != actual.length) {
            failWrongLength(message, expected.length, actual.length);
        }
        for (int i = 0; i < expected.length; i++) {
            if (expected[i] != actual[i]) {
                failWrongElement(message, i, expected[i], actual[i]);
            }
        }
    }

    /**
     * Asserts that array {@code actual} is the same size and every element equals
     * those in array {@code expected}. On failure, message indicates first
     * specific element mismatch.
     */
    public static void assertEquals(int[] expected, int[] actual) {
        assertEquals(null, expected, actual);
    }

    /**
     * Asserts that array {@code actual} is the same size and every element equals
     * those in array {@code expected}. On failure, message indicates first
     * specific element mismatch.
     */
    public static void assertEquals(
            String message, double[] expected, double[] actual) {
        if (expected.length != actual.length) {
            failWrongLength(message, expected.length, actual.length);
        }
        for (int i = 0; i < expected.length; i++) {
            if (expected[i] != actual[i]) {
                failWrongElement(message, i, expected[i], actual[i]);
            }
        }
    }

    /**
     * Asserts that array {@code actual} is the same size and every element equals
     * those in array {@code expected}. On failure, message indicates first
     * specific element mismatch.
     */
    public static void assertEquals(double[] expected, double[] actual) {
        assertEquals(null, expected, actual);
    }

    /**
     * Asserts that array {@code actual} is the same size and every element
     * is the same as those in array {@code expected}. Note that this uses
     * {@code equals()} instead of {@code ==} to compare the objects.
     * {@code null} will be considered equal to {code null} (unlike SQL).
     * On failure, message indicates first specific element mismatch.
     */
    public static void assertEquals(
            String message, Object[] expected, Object[] actual) {
        if (expected.length != actual.length) {
            failWrongLength(message, expected.length, actual.length);
        }
        for (int i = 0; i < expected.length; i++) {
            Object exp = expected[i];
            Object act = actual[i];
            // The following borrowed from java.util.equals(Object[], Object[]).
            if (!((exp==null) ? act==null : exp.equals(act))) {
                failWrongElement(message, i, exp, act);
            }
        }
    }

    /**
     * Asserts that array {@code actual} is the same size and every element
     * is the same as those in array {@code expected}. Note that this uses
     * {@code ==} instead of {@code equals()} to compare the objects.
     * On failure, message indicates first specific element mismatch.
     */
    public static void assertEquals(Object[] expected, Object[] actual) {
        assertEquals(null, expected, actual);
    }

    /** Asserts that two sets contain the same elements. */
    public static void assertEquals(
            String message, Set<? extends Object> expected, Set<? extends Object> actual) {
        Set<Object> onlyInExpected = new HashSet<Object>(expected);
        onlyInExpected.removeAll(actual);
        Set<Object> onlyInActual = new HashSet<Object>(actual);
        onlyInActual.removeAll(expected);
        if (onlyInExpected.size() != 0 || onlyInActual.size() != 0) {
            Set<Object> intersection = new HashSet<Object>(expected);
            intersection.retainAll(actual);
            failWithMessage(
                    message,
                    "Sets do not match.\nOnly in expected: " + onlyInExpected
                    + "\nOnly in actual: " + onlyInActual
                    + "\nIntersection: " + intersection);
        }
    }

    /** Asserts that two sets contain the same elements. */
    public static void assertEquals(Set<? extends Object> expected, Set<? extends Object> actual) {
        assertEquals(null, expected, actual);
    }

    /**
     * Asserts that {@code expectedRegex} exactly matches {@code actual} and
     * fails with {@code message} if it does not.  The MatchResult is returned
     * in case the test needs access to any captured groups.  Note that you can
     * also use this for a literal string, by wrapping your expected string in
     * {@link Pattern#quote}.
     */
    public static MatchResult assertMatchesRegex(
            String message, String expectedRegex, String actual) {
        if (actual == null) {
            failNotMatches(message, expectedRegex, actual);
        }
        Matcher matcher = getMatcher(expectedRegex, actual);
        if (!matcher.matches()) {
            failNotMatches(message, expectedRegex, actual);
        }
        return matcher;
    }

    /**
     * Variant of {@link #assertMatchesRegex(String,String,String)} using a
     * generic message.
     */
    public static MatchResult assertMatchesRegex(
            String expectedRegex, String actual) {
        return assertMatchesRegex(null, expectedRegex, actual);
    }

    /**
     * Asserts that {@code expectedRegex} matches any substring of {@code actual}
     * and fails with {@code message} if it does not.  The Matcher is returned in
     * case the test needs access to any captured groups.  Note that you can also
     * use this for a literal string, by wrapping your expected string in
     * {@link Pattern#quote}.
     */
    public static MatchResult assertContainsRegex(
            String message, String expectedRegex, String actual) {
        if (actual == null) {
            failNotContains(message, expectedRegex, actual);
        }
        Matcher matcher = getMatcher(expectedRegex, actual);
        if (!matcher.find()) {
            failNotContains(message, expectedRegex, actual);
        }
        return matcher;
    }

    /**
     * Variant of {@link #assertContainsRegex(String,String,String)} using a
     * generic message.
     */
    public static MatchResult assertContainsRegex(
            String expectedRegex, String actual) {
        return assertContainsRegex(null, expectedRegex, actual);
    }

    /**
     * Asserts that {@code expectedRegex} does not exactly match {@code actual},
     * and fails with {@code message} if it does. Note that you can also use
     * this for a literal string, by wrapping your expected string in
     * {@link Pattern#quote}.
     */
    public static void assertNotMatchesRegex(
            String message, String expectedRegex, String actual) {
        Matcher matcher = getMatcher(expectedRegex, actual);
        if (matcher.matches()) {
            failMatch(message, expectedRegex, actual);
        }
    }

    /**
     * Variant of {@link #assertNotMatchesRegex(String,String,String)} using a
     * generic message.
     */
    public static void assertNotMatchesRegex(
            String expectedRegex, String actual) {
        assertNotMatchesRegex(null, expectedRegex, actual);
    }

    /**
     * Asserts that {@code expectedRegex} does not match any substring of
     * {@code actual}, and fails with {@code message} if it does.  Note that you
     * can also use this for a literal string, by wrapping your expected string
     * in {@link Pattern#quote}.
     */
    public static void assertNotContainsRegex(
            String message, String expectedRegex, String actual) {
        Matcher matcher = getMatcher(expectedRegex, actual);
        if (matcher.find()) {
            failContains(message, expectedRegex, actual);
        }
    }

    /**
     * Variant of {@link #assertNotContainsRegex(String,String,String)} using a
     * generic message.
     */
    public static void assertNotContainsRegex(
            String expectedRegex, String actual) {
        assertNotContainsRegex(null, expectedRegex, actual);
    }

    /**
     * Asserts that {@code actual} contains precisely the elements
     * {@code expected}, and in the same order.
     */
    public static void assertContentsInOrder(
            String message, Iterable<?> actual, Object... expected) {
        ArrayList actualList = new ArrayList();
        for (Object o : actual) {
            actualList.add(o);
        }
        Assert.assertEquals(message, Arrays.asList(expected), actualList);
    }

    /**
     * Variant of assertContentsInOrder(String, Iterable<?>, Object...)
     * using a generic message.
     */
    public static void assertContentsInOrder(
            Iterable<?> actual, Object... expected) {
        assertContentsInOrder((String) null, actual, expected);
    }

    /**
     * Asserts that {@code actual} contains precisely the elements
     * {@code expected}, but in any order.
     */
    public static void assertContentsInAnyOrder(String message, Iterable<?> actual,
            Object... expected) {
        HashMap<Object, Object> expectedMap = new HashMap<Object, Object>(expected.length);
        for (Object expectedObj : expected) {
            expectedMap.put(expectedObj, expectedObj);
        }

        for (Object actualObj : actual) {
            if (expectedMap.remove(actualObj) == null) {
                failWithMessage(message, "Extra object in actual: (" + actualObj.toString() + ")");
            }
        }
        
        if (expectedMap.size() > 0) {
            failWithMessage(message, "Extra objects in expected.");
        }
    }

    /**
     * Variant of assertContentsInAnyOrder(String, Iterable<?>, Object...)
     * using a generic message.
     */
    public static void assertContentsInAnyOrder(Iterable<?> actual, Object... expected) {
        assertContentsInAnyOrder((String)null, actual, expected);
    }

    /**
     * Asserts that {@code iterable} is empty.
     */
    public static void assertEmpty(String message, Iterable<?> iterable) {
        if (iterable.iterator().hasNext()) {
            failNotEmpty(message, iterable.toString());
        }
    }

    /**
     * Variant of {@link #assertEmpty(String, Iterable)} using a
     * generic message.
     */
    public static void assertEmpty(Iterable<?> iterable) {
        assertEmpty(null, iterable);
    }

    /**
     * Asserts that {@code map} is empty.
     */
    public static void assertEmpty(String message, Map<?,?> map) {
        if (!map.isEmpty()) {
            failNotEmpty(message, map.toString());
        }
    }

    /**
     * Variant of {@link #assertEmpty(String, Map)} using a generic
     * message.
     */
    public  static void assertEmpty(Map<?,?> map) {
        assertEmpty(null, map);
    }

    /**
     * Asserts that {@code iterable} is not empty.
     */
    public static void assertNotEmpty(String message, Iterable<?> iterable) {
        if (!iterable.iterator().hasNext()) {
            failEmpty(message);
        }
    }

    /**
     * Variant of assertNotEmpty(String, Iterable<?>)
     * using a generic message.
     */
    public static void assertNotEmpty(Iterable<?> iterable) {
        assertNotEmpty(null, iterable);
    }

    /**
     * Asserts that {@code map} is not empty.
     */
    public static void assertNotEmpty(String message, Map<?,?> map) {
        if (map.isEmpty()) {
            failEmpty(message);
        }
    }

    /**
     * Variant of {@link #assertNotEmpty(String, Map)} using a generic
     * message.
     */
    public static void assertNotEmpty(Map<?,?> map) {
        assertNotEmpty(null, map);
    }

    /**
     * Utility for testing equals() and hashCode() results at once.
     * Tests that lhs.equals(rhs) matches expectedResult, as well as
     * rhs.equals(lhs).  Also tests that hashCode() return values are
     * equal if expectedResult is true.  (hashCode() is not tested if
     * expectedResult is false, as unequal objects can have equal hashCodes.)
     *
     * @param lhs An Object for which equals() and hashCode() are to be tested.
     * @param rhs As lhs.
     * @param expectedResult True if the objects should compare equal,
     *   false if not.
     */
    public static void checkEqualsAndHashCodeMethods(
            String message, Object lhs, Object rhs, boolean expectedResult) {

        if ((lhs == null) && (rhs == null)) {
            Assert.assertTrue(
                    "Your check is dubious...why would you expect null != null?",
                    expectedResult);
            return;
        }

        if ((lhs == null) || (rhs == null)) {
            Assert.assertFalse(
                    "Your check is dubious...why would you expect an object "
                            + "to be equal to null?", expectedResult);
        }

        if (lhs != null) {
            Assert.assertEquals(message, expectedResult, lhs.equals(rhs));
        }
        if (rhs != null) {
            Assert.assertEquals(message, expectedResult, rhs.equals(lhs));
        }

        if (expectedResult) {
            String hashMessage =
                    "hashCode() values for equal objects should be the same";
            if (message != null) {
                hashMessage += ": " + message;
            }
            Assert.assertTrue(hashMessage, lhs.hashCode() == rhs.hashCode());
        }
    }

    /**
     * Variant of
     * checkEqualsAndHashCodeMethods(String,Object,Object,boolean...)}
     * using a generic message.
     */
    public static void checkEqualsAndHashCodeMethods(Object lhs, Object rhs,
            boolean expectedResult) {
        checkEqualsAndHashCodeMethods((String) null, lhs, rhs, expectedResult);
    }

    private static Matcher getMatcher(String expectedRegex, String actual) {
        Pattern pattern = Pattern.compile(expectedRegex);
        return pattern.matcher(actual);
    }

    private static void failEqual(String message, Object unexpected) {
        failWithMessage(message, "expected not to be:<" + unexpected + ">");
    }

    private static void failWrongLength(
            String message, int expected, int actual) {
        failWithMessage(message, "expected array length:<" + expected
                + "> but was:<" + actual + '>');
    }

    private static void failWrongElement(
            String message, int index, Object expected, Object actual) {
        failWithMessage(message, "expected array element[" + index + "]:<"
                + expected + "> but was:<" + actual + '>');
    }

    private static void failNotMatches(
            String message, String expectedRegex, String actual) {
        String actualDesc = (actual == null) ? "null" : ('<' + actual + '>');
        failWithMessage(message, "expected to match regex:<" + expectedRegex
                + "> but was:" + actualDesc);
    }

    private static void failNotContains(
            String message, String expectedRegex, String actual) {
        String actualDesc = (actual == null) ? "null" : ('<' + actual + '>');
        failWithMessage(message, "expected to contain regex:<" + expectedRegex
                + "> but was:" + actualDesc);
    }

    private static void failMatch(
            String message, String expectedRegex, String actual) {
        failWithMessage(message, "expected not to match regex:<" + expectedRegex
                + "> but was:<" + actual + '>');
    }

    private static void failContains(
            String message, String expectedRegex, String actual) {
        failWithMessage(message, "expected not to contain regex:<" + expectedRegex
                + "> but was:<" + actual + '>');
    }

    private static void failNotEmpty(
            String message, String actual) {
        failWithMessage(message, "expected to be empty, but contained: <"
                + actual + ">");
    }

    private static void failEmpty(String message) {
        failWithMessage(message, "expected not to be empty, but was");
    }

    private static void failWithMessage(String userMessage, String ourMessage) {
        Assert.fail((userMessage == null)
                ? ourMessage
                : userMessage + ' ' + ourMessage);
    }

    private static boolean equal(Object a, Object b) {
        return a == b || (a != null && a.equals(b));
    }

}
