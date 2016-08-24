/*
 * Copyright 2016 The Android Open Source Project
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

package com.android.mediaframeworktest.helpers;

import org.hamcrest.CoreMatchers;
import org.hamcrest.Matcher;
import org.junit.rules.ErrorCollector;

import android.graphics.Rect;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureRequest.Builder;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.params.MeteringRectangle;
import android.media.Image;
import android.util.Log;
import android.util.Size;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * A camera test ErrorCollector class to gather the test failures during a test,
 * instead of failing the test immediately for each failure.
 */
/**
 * (non-Javadoc)
 * @see android.hardware.camera2.cts.helpers.CameraErrorCollector
 */
public class CameraErrorCollector extends ErrorCollector {

    private static final String TAG = "CameraErrorCollector";
    private static final boolean LOG_ERRORS = Log.isLoggable(TAG, Log.ERROR);

    private String mCameraMsg = "";

    @Override
    public void verify() throws Throwable {
        // Do not remove if using JUnit 3 test runners. super.verify() is protected.
        super.verify();
    }

    /**
     * Adds an unconditional error to the table.
     *
     * <p>Execution continues, but test will fail at the end.</p>
     *
     * @param message A string containing the failure reason.
     */
    public void addMessage(String message) {
        addErrorSuper(new Throwable(mCameraMsg + message));
    }

    /**
     * Adds a Throwable to the table. <p>Execution continues, but the test will fail at the end.</p>
     */
    @Override
    public void addError(Throwable error) {
        addErrorSuper(new Throwable(mCameraMsg + error.getMessage(), error));
    }

    private void addErrorSuper(Throwable error) {
        if (LOG_ERRORS) Log.e(TAG, error.getMessage());
        super.addError(error);
    }

    /**
     * Adds a failure to the table if {@code matcher} does not match {@code value}.
     * Execution continues, but the test will fail at the end if the match fails.
     * The camera id is included into the failure log.
     */
    @Override
    public <T> void checkThat(final T value, final Matcher<T> matcher) {
        super.checkThat(mCameraMsg, value, matcher);
    }

    /**
     * Adds a failure with the given {@code reason} to the table if
     * {@code matcher} does not match {@code value}. Execution continues, but
     * the test will fail at the end if the match fails. The camera id is
     * included into the failure log.
     */
    @Override
    public <T> void checkThat(final String reason, final T value, final Matcher<T> matcher) {
        super.checkThat(mCameraMsg + reason, value, matcher);
    }

    /**
     * Set the camera id to this error collector object for logging purpose.
     *
     * @param id The camera id to be set.
     */
    public void setCameraId(String id) {
        if (id != null) {
            mCameraMsg = "Test failed for camera " + id + ": ";
        } else {
            mCameraMsg = "";
        }
    }

    /**
     * Adds a failure to the table if {@code condition} is not {@code true}.
     * <p>
     * Execution continues, but the test will fail at the end if the condition
     * failed.
     * </p>
     *
     * @param msg Message to be logged when check fails.
     * @param condition Log the failure if it is not true.
     */
    public boolean expectTrue(String msg, boolean condition) {
        if (!condition) {
            addMessage(msg);
        }

        return condition;
    }

    /**
     * Check if the two values are equal.
     *
     * @param msg Message to be logged when check fails.
     * @param expected Expected value to be checked against.
     * @param actual Actual value to be checked.
     * @return {@code true} if the two values are equal, {@code false} otherwise.
     *
     * @throws IllegalArgumentException if {@code expected} was {@code null}
     */
    public <T> boolean expectEquals(String msg, T expected, T actual) {
        if (expected == null) {
            throw new IllegalArgumentException("expected value shouldn't be null");
        }

        if (!Objects.equals(expected, actual)) {
            addMessage(String.format("%s (expected = %s, actual = %s) ", msg, expected,
                    actual));
            return false;
        }

        return true;
    }

    /**
     * Check if the two values are not equal.
     *
     * @param msg Message to be logged when check fails.
     * @param expected Expected value to be checked against.
     * @param actual Actual value to be checked.
     * @return {@code true} if the two values are not equal, {@code false} otherwise.
     */
    public <T> boolean expectNotEquals(String msg, T expected, T actual) {
        if (Objects.equals(expected, actual)) {
            addMessage(String.format("%s (expected = %s, actual = %s) ", msg, expected,
                    actual));
            return false;
        }

        return true;
    }

    /**
     * Check if the two arrays of values are deeply equal.
     *
     * @param msg Message to be logged when check fails.
     * @param expected Expected array of values to be checked against.
     * @param actual Actual array of values to be checked.
     * @return {@code true} if the two arrays of values are deeply equal, {@code false} otherwise.
     *
     * @throws IllegalArgumentException if {@code expected} was {@code null}
     */
    public <T> boolean expectEquals(String msg, T[] expected, T[] actual) {
        if (expected == null) {
            throw new IllegalArgumentException("expected value shouldn't be null");
        }

        if (!Arrays.deepEquals(expected, actual)) {
            addMessage(String.format("%s (expected = %s, actual = %s) ", msg,
                    Arrays.deepToString(expected), Arrays.deepToString(actual)));
            return false;
        }

        return true;
    }

    /**
     * Check if the two arrays of values are not deeply equal.
     *
     * @param msg Message to be logged when check fails.
     * @param expected Expected array of values to be checked against.
     * @param actual Actual array of values to be checked.
     * @return {@code true} if the two arrays of values are not deeply equal, {@code false}
     *          otherwise.
     *
     * @throws IllegalArgumentException if {@code expected} was {@code null}
     */
    public <T> boolean expectNotEquals(String msg, T[] expected, T[] actual) {
        if (expected == null) {
            throw new IllegalArgumentException("expected value shouldn't be null");
        }

        if (Arrays.deepEquals(expected, actual)) {
            addMessage(String.format("%s (expected = %s, actual = %s) ", msg,
                    Arrays.deepToString(expected), Arrays.deepToString(actual)));
            return false;
        }

        return true;
    }

    /**
     * Check that the {@code actual} value is greater than the {@code expected} value.
     *
     * @param msg Message to be logged when check fails.
     * @param expected The expected value to check that the actual value is larger than.
     * @param actual Actual value to check.
     * @return {@code true} if {@code actual} is greater than {@code expected}.
     */
    public <T extends Comparable<? super T>> boolean expectGreater(String msg, T expected,
            T actual) {
        return expectTrue(String.format("%s: (expected = %s was not greater than actual = %s) ",
                msg, expected, actual), actual.compareTo(expected) > 0);
    }

    /**
     * Check that the {@code actual} value is greater than or equal to the {@code expected} value.
     *
     * @param msg Message to be logged when check fails.
     * @param expected The expected value to check that the actual value is larger than or equal to.
     * @param actual Actual value to check.
     * @return {@code true} if {@code actual} is greater than or equal to {@code expected}.
     */
    public <T extends Comparable<? super T>> boolean expectGreaterOrEqual(String msg, T expected,
                                                                       T actual) {
        return expectTrue(String.format("%s: (expected = %s was not greater than actual = %s) ",
                msg, expected, actual), actual.compareTo(expected) >= 0);
    }

    /**
     * Check that the {@code actual} value is less than the {@code expected} value.
     *
     * @param msg Message to be logged when check fails.
     * @param expected The expected value to check that the actual value is less than.
     * @param actual Actual value to check.
     * @return {@code true} if {@code actual} is less than {@code expected}.
     */
    public <T extends Comparable<? super T>> boolean expectLess(String msg, T expected,
            T actual) {
        return expectTrue(String.format("%s: (expected = %s was not greater than actual = %s) ",
                msg, expected, actual), actual.compareTo(expected) < 0);
    }

    /**
     * Check that the {@code actual} value is less than or equal to the {@code expected} value.
     *
     * @param msg Message to be logged when check fails.
     * @param expected The expected value to check that the actual value is less than or equal to.
     * @param actual Actual value to check.
     * @return {@code true} if {@code actual} is less than or equal to {@code expected}.
     */
    public <T extends Comparable<? super T>> boolean expectLessOrEqual(String msg, T expected,
            T actual) {
        return expectTrue(String.format("%s: (expected = %s was not greater than actual = %s) ",
                msg, expected, actual), actual.compareTo(expected) <= 0);
    }

    /**
     * Check if the two float values are equal with given error tolerance.
     *
     * @param msg Message to be logged when check fails.
     * @param expected Expected value to be checked against.
     * @param actual Actual value to be checked.
     * @param tolerance The error margin for the equality check.
     * @return {@code true} if the two values are equal, {@code false} otherwise.
     */
    public <T> boolean expectEquals(String msg, float expected, float actual, float tolerance) {
        if (expected == actual) {
            return true;
        }

        if (!(Math.abs(expected - actual) <= tolerance)) {
            addMessage(String.format("%s (expected = %s, actual = %s, tolerance = %s) ", msg,
                    expected, actual, tolerance));
            return false;
        }

        return true;
    }

    /**
     * Check if the two double values are equal with given error tolerance.
     *
     * @param msg Message to be logged when check fails.
     * @param expected Expected value to be checked against.
     * @param actual Actual value to be checked.
     * @param tolerance The error margin for the equality check
     * @return {@code true} if the two values are equal, {@code false} otherwise.
     */
    public <T> boolean expectEquals(String msg, double expected, double actual, double tolerance) {
        if (expected == actual) {
            return true;
        }

        if (!(Math.abs(expected - actual) <= tolerance)) {
            addMessage(String.format("%s (expected = %s, actual = %s, tolerance = %s) ", msg,
                    expected, actual, tolerance));
            return false;
        }

        return true;
    }

    /**
     * Check that all values in the list are greater than or equal to the min value.
     *
     * @param msg Message to be logged when check fails
     * @param list The list of values to be checked
     * @param min The smallest allowed value
     */
    public <T extends Comparable<? super T>> void expectValuesGreaterOrEqual(String msg,
            List<T> list, T min) {
        for (T value : list) {
            expectTrue(msg + String.format(", array value " + value.toString() +
                                    " is less than %s",
                            min.toString()), value.compareTo(min) >= 0);
        }
    }

    /**
     * Check that all values in the array are greater than or equal to the min value.
     *
     * @param msg Message to be logged when check fails
     * @param array The array of values to be checked
     * @param min The smallest allowed value
     */
    public <T extends Comparable<? super T>> void expectValuesGreaterOrEqual(String msg,
                                                                             T[] array, T min) {
        expectValuesGreaterOrEqual(msg, Arrays.asList(array), min);
    }

    /**
     * Expect the list of values are in the range.
     *
     * @param msg Message to be logged
     * @param list The list of values to be checked
     * @param min The min value of the range
     * @param max The max value of the range
     */
    public <T extends Comparable<? super T>> void expectValuesInRange(String msg, List<T> list,
            T min, T max) {
        for (T value : list) {
            expectTrue(msg + String.format(", array value " + value.toString() +
                    " is out of range [%s, %s]",
                    min.toString(), max.toString()),
                    value.compareTo(max)<= 0 && value.compareTo(min) >= 0);
        }
    }

    /**
     * Expect the array of values are in the range.
     *
     * @param msg Message to be logged
     * @param array The array of values to be checked
     * @param min The min value of the range
     * @param max The max value of the range
     */
    public <T extends Comparable<? super T>> void expectValuesInRange(String msg, T[] array,
            T min, T max) {
        expectValuesInRange(msg, Arrays.asList(array), min, max);
    }

    /**
     * Expect the array of values are in the range.
     *
     * @param msg Message to be logged
     * @param array The array of values to be checked
     * @param min The min value of the range
     * @param max The max value of the range
     */
    public void expectValuesInRange(String msg, int[] array, int min, int max) {
        ArrayList<Integer> l = new ArrayList<>(array.length);
        for (int i : array) {
            l.add(i);
        }
        expectValuesInRange(msg, l, min, max);
    }

    /**
     * Expect the value is in the range.
     *
     * @param msg Message to be logged
     * @param value The value to be checked
     * @param min The min value of the range
     * @param max The max value of the range
     *
     * @return {@code true} if the value was in range, {@code false} otherwise
     */
    public <T extends Comparable<? super T>> boolean expectInRange(String msg, T value,
            T min, T max) {
        return expectTrue(msg + String.format(", value " + value.toString()
                + " is out of range [%s, %s]",
                min.toString(), max.toString()),
                value.compareTo(max)<= 0 && value.compareTo(min) >= 0);
    }


    /**
     * Check that two metering region arrays are similar enough by ensuring that each of their width,
     * height, and all corners are within {@code errorPercent} of each other.
     *
     * <p>Note that the length of the arrays must be the same, and each weight must be the same
     * as well. We assume the order is also equivalent.</p>
     *
     * <p>At most 1 error per each dissimilar metering region is collected.</p>
     *
     * @param msg Message to be logged
     * @param expected The reference 'expected' values to be used to check against
     * @param actual The actual values that were received
     * @param errorPercent Within how many percent the components should be
     *
     * @return {@code true} if all expects passed, {@code false} otherwise
     */
    public boolean expectMeteringRegionsAreSimilar(String msg,
            MeteringRectangle[] expected, MeteringRectangle[] actual,
            float errorPercent) {
        String expectedActualMsg = String.format("expected (%s), actual (%s)",
                Arrays.deepToString(expected), Arrays.deepToString(actual));

        String differentSizesMsg = String.format(
                "%s: rect lists are different sizes; %s",
                msg, expectedActualMsg);

        String differentWeightsMsg = String.format(
                "%s: rect weights are different; %s",
                msg, expectedActualMsg);

        if (!expectTrue(differentSizesMsg, actual != null)) {
            return false;
        }

        if (!expectEquals(differentSizesMsg, expected.length, actual.length)) return false;

        boolean succ = true;
        for (int i = 0; i < expected.length; ++i) {
            if (i < actual.length) {
                // Avoid printing multiple errors for the same rectangle
                if (!expectRectsAreSimilar(
                        msg, expected[i].getRect(), actual[i].getRect(), errorPercent)) {
                    succ = false;
                    continue;
                }
                if (!expectEquals(differentWeightsMsg,
                        expected[i].getMeteringWeight(), actual[i].getMeteringWeight())) {
                    succ = false;
                    continue;
                }
            }
        }

        return succ;
    }

    /**
     * Check that two rectangles are similar enough by ensuring that their width, height,
     * and all corners are within {@code errorPercent} of each other.
     *
     * <p>Only the first error is collected, to avoid spamming several error messages when
     * the rectangle is hugely dissimilar.</p>
     *
     * @param msg Message to be logged
     * @param expected The reference 'expected' value to be used to check against
     * @param actual The actual value that was received
     * @param errorPercent Within how many percent the components should be
     *
     * @return {@code true} if all expects passed, {@code false} otherwise
     */
    public boolean expectRectsAreSimilar(String msg, Rect expected, Rect actual,
            float errorPercent) {
        String formattedMsg = String.format("%s: rects are not similar enough; expected (%s), " +
                "actual (%s), error percent (%s), reason: ",
                msg, expected, actual, errorPercent);

        if (!expectSimilarValues(
                formattedMsg, "too wide", "too narrow", actual.width(), expected.width(),
                errorPercent)) return false;

        if (!expectSimilarValues(
                formattedMsg, "too tall", "too short", actual.height(), expected.height(),
                errorPercent)) return false;

        if (!expectSimilarValues(
                formattedMsg, "left pt too right", "left pt too left", actual.left, expected.left,
                errorPercent)) return false;

        if (!expectSimilarValues(
                formattedMsg, "right pt too right", "right pt too left",
                actual.right, expected.right, errorPercent)) return false;

        if (!expectSimilarValues(
                formattedMsg, "top pt too low", "top pt too high", actual.top, expected.top,
                errorPercent)) return false;

        if (!expectSimilarValues(
                formattedMsg, "bottom pt too low", "bottom pt too high", actual.top, expected.top,
                errorPercent)) return false;

        return true;
    }

    /**
     * Check that two sizes are similar enough by ensuring that their width and height
     * are within {@code errorPercent} of each other.
     *
     * <p>Only the first error is collected, to avoid spamming several error messages when
     * the rectangle is hugely dissimilar.</p>
     *
     * @param msg Message to be logged
     * @param expected The reference 'expected' value to be used to check against
     * @param actual The actual value that was received
     * @param errorPercent Within how many percent the components should be
     *
     * @return {@code true} if all expects passed, {@code false} otherwise
     */
    public boolean expectSizesAreSimilar(String msg, Size expected, Size actual,
            float errorPercent) {
        String formattedMsg = String.format("%s: rects are not similar enough; expected (%s), " +
                "actual (%s), error percent (%s), reason: ",
                msg, expected, actual, errorPercent);

        if (!expectSimilarValues(
                formattedMsg, "too wide", "too narrow", actual.getWidth(), expected.getWidth(),
                errorPercent)) return false;

        if (!expectSimilarValues(
                formattedMsg, "too tall", "too short", actual.getHeight(), expected.getHeight(),
                errorPercent)) return false;

        return true;
    }

    /**
     * Check that the rectangle is centered within a certain tolerance of {@code errorPercent},
     * with respect to the {@code bounds} bounding rectangle.
     *
     * @param msg Message to be logged
     * @param expectedBounds The width/height of the bounding rectangle
     * @param actual The actual value that was received
     * @param errorPercent Within how many percent the centering should be
     */
    public void expectRectCentered(String msg, Size expectedBounds, Rect actual,
            float errorPercent) {
        String formattedMsg = String.format("%s: rect should be centered; expected bounds (%s), " +
                "actual (%s), error percent (%s), reason: ",
                msg, expectedBounds, actual, errorPercent);

        int centerBoundX = expectedBounds.getWidth() / 2;
        int centerBoundY = expectedBounds.getHeight() / 2;

        expectSimilarValues(
                formattedMsg, "too low", "too high", actual.centerY(), centerBoundY,
                errorPercent);

        expectSimilarValues(
                formattedMsg, "too right", "too left", actual.centerX(), centerBoundX,
                errorPercent);
    }

    private boolean expectSimilarValues(
            String formattedMsg, String tooSmall, String tooLarge, int actualValue,
            int expectedValue, float errorPercent) {
        boolean succ = true;
        succ = expectTrue(formattedMsg + tooLarge,
                actualValue <= (expectedValue * (1.0f + errorPercent))) && succ;
        succ = expectTrue(formattedMsg + tooSmall,
                actualValue >= (expectedValue * (1.0f - errorPercent))) && succ;

        return succ;
    }

    public void expectNotNull(String msg, Object obj) {
        checkThat(msg, obj, CoreMatchers.notNullValue());
    }

    public void expectNull(String msg, Object obj) {
        if (obj != null) {
            addMessage(msg);
        }
    }

    /**
     * Check if the values in the array are monotonically increasing (decreasing) and not all
     * equal.
     *
     * @param array The array of values to be checked
     * @param ascendingOrder The monotonicity ordering to be checked with
     */
    public <T extends Comparable<? super T>>  void checkArrayMonotonicityAndNotAllEqual(T[] array,
            boolean ascendingOrder) {
        String orderMsg = ascendingOrder ? ("increasing order") : ("decreasing order");
        for (int i = 0; i < array.length - 1; i++) {
            int compareResult = array[i + 1].compareTo(array[i]);
            boolean condition = compareResult >= 0;
            if (!ascendingOrder) {
                condition = compareResult <= 0;
            }

            expectTrue(String.format("Adjacent values (%s and %s) %s monotonicity is broken",
                    array[i].toString(), array[i + 1].toString(), orderMsg), condition);
        }

        expectTrue("All values of this array are equal: " + array[0].toString(),
                array[0].compareTo(array[array.length - 1]) != 0);
    }

    /**
     * Check if the key value is not null and return the value.
     *
     * @param characteristics The {@link CameraCharacteristics} to get the key from.
     * @param key The {@link CameraCharacteristics} key to be checked.
     *
     * @return The value of the key.
     */
    public <T> T expectKeyValueNotNull(CameraCharacteristics characteristics,
            CameraCharacteristics.Key<T> key) {

        T value = characteristics.get(key);
        if (value == null) {
            addMessage("Key " + key.getName() + " shouldn't be null");
        }

        return value;
    }

    /**
     * Check if the key value is not null and return the value.
     *
     * @param request The {@link CaptureRequest} to get the key from.
     * @param key The {@link CaptureRequest} key to be checked.
     *
     * @return The value of the key.
     */
    public <T> T expectKeyValueNotNull(CaptureRequest request,
                                       CaptureRequest.Key<T> key) {

        T value = request.get(key);
        if (value == null) {
            addMessage("Key " + key.getName() + " shouldn't be null");
        }

        return value;
    }

    /**
     * Check if the key value is not null and return the value.
     *
     * @param request The {@link CaptureRequest#Builder} to get the key from.
     * @param key The {@link CaptureRequest} key to be checked.
     * @return The value of the key.
     */
    public <T> T expectKeyValueNotNull(Builder request, CaptureRequest.Key<T> key) {

        T value = request.get(key);
        if (value == null) {
            addMessage("Key " + key.getName() + " shouldn't be null");
        }

        return value;
    }

    /**
     * Check if the key value is not null and return the value.
     *
     * @param result The {@link CaptureResult} to get the key from.
     * @param key The {@link CaptureResult} key to be checked.
     * @return The value of the key.
     */
    public <T> T expectKeyValueNotNull(CaptureResult result, CaptureResult.Key<T> key) {
        return expectKeyValueNotNull("", result, key);
    }

    /**
     * Check if the key value is not null and return the value.
     *
     * @param msg The message to be logged.
     * @param result The {@link CaptureResult} to get the key from.
     * @param key The {@link CaptureResult} key to be checked.
     * @return The value of the key.
     */
    public <T> T expectKeyValueNotNull(String msg, CaptureResult result, CaptureResult.Key<T> key) {

        T value = result.get(key);
        if (value == null) {
            addMessage(msg + " Key " + key.getName() + " shouldn't be null");
        }

        return value;
    }

    /**
     * Check if the key is non-null and the value is not equal to target.
     *
     * @param request The The {@link CaptureRequest#Builder} to get the key from.
     * @param key The {@link CaptureRequest} key to be checked.
     * @param expected The expected value of the CaptureRequest key.
     */
    public <T> void expectKeyValueNotEquals(
            Builder request, CaptureRequest.Key<T> key, T expected) {
        if (request == null || key == null || expected == null) {
            throw new IllegalArgumentException("request, key and expected shouldn't be null");
        }

        T value;
        if ((value = expectKeyValueNotNull(request, key)) == null) {
            return;
        }

        String reason = "Key " + key.getName() + " shouldn't have value " + value.toString();
        checkThat(reason, value, CoreMatchers.not(expected));
    }

    /**
     * Check if the key is non-null and the value is not equal to target.
     *
     * @param result The {@link CaptureResult} to get the key from.
     * @param key The {@link CaptureResult} key to be checked.
     * @param expected The expected value of the CaptureResult key.
     */
    public <T> void expectKeyValueNotEquals(
            CaptureResult result, CaptureResult.Key<T> key, T expected) {
        if (result == null || key == null || expected == null) {
            throw new IllegalArgumentException("result, key and expected shouldn't be null");
        }

        T value;
        if ((value = expectKeyValueNotNull(result, key)) == null) {
            return;
        }

        String reason = "Key " + key.getName() + " shouldn't have value " + value.toString();
        checkThat(reason, value, CoreMatchers.not(expected));
    }

    /**
     * Check if the value is non-null and the value is equal to target.
     *
     * @param result The  {@link CaptureResult} to lookup the value in.
     * @param key The {@link CaptureResult} key to be checked.
     * @param expected The expected value of the {@link CaptureResult} key.
     */
    public <T> void expectKeyValueEquals(CaptureResult result, CaptureResult.Key<T> key,
            T expected) {
        if (result == null || key == null || expected == null) {
            throw new IllegalArgumentException("request, key and expected shouldn't be null");
        }

        T value;
        if ((value = expectKeyValueNotNull(result, key)) == null) {
            return;
        }

        String reason = "Key " + key.getName() + " value " + value.toString()
                + " doesn't match the expected value " + expected.toString();
        checkThat(reason, value, CoreMatchers.equalTo(expected));
    }

    /**
     * Check if the key is non-null and the value is equal to target.
     *
     * <p>Only check non-null if the target is null.</p>
     *
     * @param request The The {@link CaptureRequest#Builder} to get the key from.
     * @param key The {@link CaptureRequest} key to be checked.
     * @param expected The expected value of the CaptureRequest key.
     */
    public <T> void expectKeyValueEquals(Builder request, CaptureRequest.Key<T> key, T expected) {
        if (request == null || key == null || expected == null) {
            throw new IllegalArgumentException("request, key and expected shouldn't be null");
        }

        T value;
        if ((value = expectKeyValueNotNull(request, key)) == null) {
            return;
        }

        String reason = "Key " + key.getName() + " value " + value.toString()
                + " doesn't match the expected value " + expected.toString();
        checkThat(reason, value, CoreMatchers.equalTo(expected));
    }

    /**
     * Check if the key is non-null, and the key value is greater than the expected value.
     *
     * @param result {@link CaptureResult} to check.
     * @param key The {@link CaptureResult} key to be checked.
     * @param expected The expected to be compared to the value for the given key.
     */
    public <T extends Comparable<? super T>> void expectKeyValueGreaterOrEqual(
            CaptureResult result, CaptureResult.Key<T> key, T expected) {
        T value;
        if ((value = expectKeyValueNotNull(result, key)) == null) {
            return;
        }

        expectGreaterOrEqual(key.getName(), expected, value);
    }

    /**
     * Check if the key is non-null, and the key value is greater than the expected value.
     *
     * @param characteristics {@link CameraCharacteristics} to check.
     * @param key The {@link CameraCharacteristics} key to be checked.
     * @param expected The expected to be compared to the value for the given key.
     */
    public <T extends Comparable<? super T>> void expectKeyValueGreaterThan(
            CameraCharacteristics characteristics, CameraCharacteristics.Key<T> key, T expected) {
        T value;
        if ((value = expectKeyValueNotNull(characteristics, key)) == null) {
            return;
        }

        expectGreater(key.getName(), expected, value);
    }

    /**
     * Check if the key is non-null, and the key value is in the expected range.
     *
     * @param characteristics {@link CameraCharacteristics} to check.
     * @param key The {@link CameraCharacteristics} key to be checked.
     * @param min The min value of the range
     * @param max The max value of the range
     */
    public <T extends Comparable<? super T>> void expectKeyValueInRange(
            CameraCharacteristics characteristics, CameraCharacteristics.Key<T> key, T min, T max) {
        T value;
        if ((value = expectKeyValueNotNull(characteristics, key)) == null) {
            return;
        }
        expectInRange(key.getName(), value, min, max);
    }

    /**
     * Check if the key is non-null, and the key value is one of the expected values.
     *
     * @param characteristics {@link CameraCharacteristics} to check.
     * @param key The {@link CameraCharacteristics} key to be checked.
     * @param expected The expected values for the given key.
     */
    public <T> void expectKeyValueIsIn(CameraCharacteristics characteristics,
                                       CameraCharacteristics.Key<T> key, T... expected) {
        T value = expectKeyValueNotNull(characteristics, key);
        if (value == null) {
            return;
        }
        String reason = "Key " + key.getName() + " value " + value
                + " isn't one of the expected values " + Arrays.deepToString(expected);
        expectContains(reason, expected, value);
    }

    /**
     * Check if the key is non-null, and the key value is one of the expected values.
     *
     * @param request The The {@link CaptureRequest#Builder} to get the key from.
     * @param key The {@link CaptureRequest} key to be checked.
     * @param expected The expected values of the CaptureRequest key.
     */
    public <T> void expectKeyValueIsIn(Builder request, CaptureRequest.Key<T> key, T... expected) {
        T value = expectKeyValueNotNull(request, key);
        if (value == null) {
            return;
        }
        String reason = "Key " + key.getName() + " value " + value
                + " isn't one of the expected values " + Arrays.deepToString(expected);
        expectContains(reason, expected, value);
    }

    /**
     * Check if the key is non-null, and the key value contains the expected element.
     *
     * @param characteristics {@link CameraCharacteristics} to check.
     * @param key The {@link CameraCharacteristics} key to be checked.
     * @param expected The expected element to be contained in the value for the given key.
     */
    public <T> void expectKeyValueContains(CameraCharacteristics characteristics,
                                           CameraCharacteristics.Key<T[]> key, T expected) {
        T[] value;
        if ((value = expectKeyValueNotNull(characteristics, key)) == null) {
            return;
        }
        String reason = "Key " + key.getName() + " value " + value
                + " doesn't contain the expected value " + expected;
        expectContains(reason, value, expected);
    }

    /**
     * Check if the key is non-null, and the key value contains the expected element.
     *
     * @param characteristics {@link CameraCharacteristics} to check.
     * @param key The {@link CameraCharacteristics} key to be checked.
     * @param expected The expected element to be contained in the value for the given key.
     */
    public void expectKeyValueContains(CameraCharacteristics characteristics,
                                           CameraCharacteristics.Key<int[]> key, int expected) {
        int[] value;
        if ((value = expectKeyValueNotNull(characteristics, key)) == null) {
            return;
        }
        String reason = "Key " + key.getName() + " value " + value
                + " doesn't contain the expected value " + expected;
        expectContains(reason, value, expected);
    }

    /**
     * Check if the key is non-null, and the key value contains the expected element.
     *
     * @param characteristics {@link CameraCharacteristics} to check.
     * @param key The {@link CameraCharacteristics} key to be checked.
     * @param expected The expected element to be contained in the value for the given key.
     */
    public void expectKeyValueContains(CameraCharacteristics characteristics,
                                       CameraCharacteristics.Key<boolean[]> key, boolean expected) {
        boolean[] value;
        if ((value = expectKeyValueNotNull(characteristics, key)) == null) {
            return;
        }
        String reason = "Key " + key.getName() + " value " + value
                + " doesn't contain the expected value " + expected;
        expectContains(reason, value, expected);
    }

    /**
     * Check if the {@code values} array contains the expected element.
     *
     * @param reason reason to print for failure.
     * @param values array to check for membership in.
     * @param expected the value to check.
     */
    public <T> void expectContains(String reason, T[] values, T expected) {
        if (values == null) {
            throw new NullPointerException();
        }
        checkThat(reason, expected, InMatcher.in(values));
    }

    public <T> void expectContains(T[] values, T expected) {
        String reason = "Expected value " + expected
                + " is not contained in the given values " + values;
        expectContains(reason, values, expected);
    }

    /**
     * Specialize {@link InMatcher} class for integer primitive array.
     */
    private static class IntInMatcher extends InMatcher<Integer> {
        public IntInMatcher(int[] values) {
            Preconditions.checkNotNull("values", values);
            mValues = new ArrayList<>(values.length);
            for (int i : values) {
                mValues.add(i);
            }
        }
    }

    /**
     * Check if the {@code values} array contains the expected element.
     *
     * <p>Specialized for primitive int arrays</p>
     *
     * @param reason reason to print for failure.
     * @param values array to check for membership in.
     * @param expected the value to check.
     */
    public void expectContains(String reason, int[] values, int expected) {
        if (values == null) {
            throw new NullPointerException();
        }

        checkThat(reason, expected, new IntInMatcher(values));
    }

    public void expectContains(int[] values, int expected) {
        String reason = "Expected value " + expected
                + " is not contained in the given values " + values;
        expectContains(reason, values, expected);
    }

    /**
     * Specialize {@link BooleanInMatcher} class for boolean primitive array.
     */
    private static class BooleanInMatcher extends InMatcher<Boolean> {
        public BooleanInMatcher(boolean[] values) {
            Preconditions.checkNotNull("values", values);
            mValues = new ArrayList<>(values.length);
            for (boolean i : values) {
                mValues.add(i);
            }
        }
    }

    /**
     * Check if the {@code values} array contains the expected element.
     *
     * <p>Specialized for primitive boolean arrays</p>
     *
     * @param reason reason to print for failure.
     * @param values array to check for membership in.
     * @param expected the value to check.
     */
    public void expectContains(String reason, boolean[] values, boolean expected) {
        if (values == null) {
            throw new NullPointerException();
        }

        checkThat(reason, expected, new BooleanInMatcher(values));
    }

    /**
     * Check if the {@code values} array contains the expected element.
     *
     * <p>Specialized for primitive boolean arrays</p>
     *
     * @param values array to check for membership in.
     * @param expected the value to check.
     */
    public void expectContains(boolean[] values, boolean expected) {
        String reason = "Expected value " + expected
                + " is not contained in the given values " + values;
        expectContains(reason, values, expected);
    }

    /**
     * Check if the element inside of the list are unique.
     *
     * @param msg The message to be logged
     * @param list The list of values to be checked
     */
    public <T> void expectValuesUnique(String msg, List<T> list) {
        Set<T> sizeSet = new HashSet<T>(list);
        expectTrue(msg + " each element must be distinct", sizeSet.size() == list.size());
    }

    public void expectImageProperties(String msg, Image image, int format, Size size,
            long timestampNs) {
        expectEquals(msg + "Image format is wrong.", image.getFormat(), format);
        expectEquals(msg + "Image width is wrong.", image.getWidth(), size.getWidth());
        expectEquals(msg + "Image height is wrong.", image.getHeight(), size.getHeight());
        expectEquals(msg + "Image timestamp is wrong.", image.getTimestamp(), timestampNs);
    }

}
