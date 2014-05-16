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

package android.hardware.camera2.params;

import static com.android.internal.util.Preconditions.*;

import android.graphics.PointF;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.utils.HashCodeHelpers;

import java.util.Arrays;

/**
 * Immutable class for describing a {@code 2 x M x 3} tonemap curve of floats.
 *
 * <p>This defines red, green, and blue curves that the {@link CameraDevice} will
 * use as the tonemapping/contrast/gamma curve when {@link CaptureRequest#TONEMAP_MODE} is
 * set to {@link CameraMetadata#TONEMAP_MODE_CONTRAST_CURVE}.</p>
 *
 * <p>The total number of points {@code (Pin, Pout)} for each color channel can be no more than
 * {@link CameraCharacteristics#TONEMAP_MAX_CURVE_POINTS}.</p>
 *
 * <p>The coordinate system for each point is within the inclusive range
 * [{@value #LEVEL_BLACK}, {@value #LEVEL_WHITE}].</p>
 *
 * @see CaptureRequest#TONEMAP_CURVE_BLUE
 * @see CaptureRequest#TONEMAP_CURVE_GREEN
 * @see CaptureRequest#TONEMAP_CURVE_RED
 * @see CameraMetadata#TONEMAP_MODE_CONTRAST_CURVE
 * @see CameraCharacteristics#TONEMAP_MAX_CURVE_POINTS
 */
public final class TonemapCurve {
    /**
     * Lower bound tonemap value corresponding to pure black for a single color channel.
     */
    public static final float LEVEL_BLACK = 0.0f;

    /**
     * Upper bound tonemap value corresponding to a pure white for a single color channel.
     */
    public static final float LEVEL_WHITE = 1.0f;

    /**
     * Number of elements in a {@code (Pin, Pout)} point;
     */
    public static final int POINT_SIZE = 2;

    /**
     * Index of the red color channel curve.
     */
    public static final int CHANNEL_RED = 0;
    /**
     * Index of the green color channel curve.
     */
    public static final int CHANNEL_GREEN = 1;
    /**
     * Index of the blue color channel curve.
     */
    public static final int CHANNEL_BLUE = 2;

    /**
     * Create a new immutable TonemapCurve instance.
     *
     * <p>Values are stored as a contiguous {@code (Pin, Pout}) point.</p>
     *
     * <p>All parameters may have independent length but should have at most
     * {@link CameraCharacteristics#TONEMAP_MAX_CURVE_POINTS} * {@value #POINT_SIZE} elements.</p>
     *
     * <p>All sub-elements must be in the inclusive range of
     * [{@value #LEVEL_BLACK}, {@value #LEVEL_WHITE}].</p>
     *
     * <p>This constructor copies the array contents and does not retain ownership of the array.</p>
     *
     * @param elements An array of elements whose length is {@code CHANNEL_COUNT * rows * columns}
     *
     * @throws IllegalArgumentException
     *            if the {@code elements} array length is invalid,
     *            if any of the subelems are not finite
     * @throws NullPointerException
     *            if any of the parameters is {@code null}
     *
     * @hide
     */
    public TonemapCurve(float[] red, float[] green, float[] blue) {
        // TODO: maxCurvePoints check?

        checkNotNull(red, "red must not be null");
        checkNotNull(green, "green must not be null");
        checkNotNull(blue, "blue must not be null");

        checkArgumentArrayLengthDivisibleBy(red, POINT_SIZE, "red");
        checkArgumentArrayLengthDivisibleBy(green, POINT_SIZE, "green");
        checkArgumentArrayLengthDivisibleBy(blue, POINT_SIZE, "blue");

        checkArrayElementsInRange(red, LEVEL_BLACK, LEVEL_WHITE, "red");
        checkArrayElementsInRange(green, LEVEL_BLACK, LEVEL_WHITE, "green");
        checkArrayElementsInRange(blue, LEVEL_BLACK, LEVEL_WHITE, "blue");

        mRed = Arrays.copyOf(red, red.length);
        mGreen = Arrays.copyOf(green, green.length);
        mBlue = Arrays.copyOf(blue, blue.length);
    }

    private static void checkArgumentArrayLengthDivisibleBy(float[] array,
            int divisible, String arrayName) {
        if (array.length % divisible != 0) {
            throw new IllegalArgumentException(arrayName + " size must be divisible by "
                    + divisible);
        }
    }

    private static int checkArgumentColorChannel(int colorChannel) {
        switch (colorChannel) {
            case CHANNEL_RED:
            case CHANNEL_GREEN:
            case CHANNEL_BLUE:
                break;
            default:
                throw new IllegalArgumentException("colorChannel out of range");
        }

        return colorChannel;
    }

    /**
     * Get the number of points stored in this tonemap curve for the specified color channel.
     *
     * @param colorChannel one of {@link #CHANNEL_RED}, {@link #CHANNEL_GREEN}, {@link #CHANNEL_BLUE}
     * @return number of points stored in this tonemap for that color's curve (>= 0)
     *
     * @throws IllegalArgumentException if {@code colorChannel} was out of range
     */
    public int getPointCount(int colorChannel) {
        checkArgumentColorChannel(colorChannel);

        return getCurve(colorChannel).length / POINT_SIZE;
    }

    /**
     * Get the point for a color channel at a specified index.
     *
     * <p>The index must be at least 0 but no greater than {@link #getPointCount(int)} for
     * that {@code colorChannel}.</p>
     *
     * <p>All returned coordinates in the point are between the range of
     * [{@value #LEVEL_BLACK}, {@value #LEVEL_WHITE}].</p>
     *
     * @param colorChannel {@link #CHANNEL_RED}, {@link #CHANNEL_GREEN}, or {@link #CHANNEL_BLUE}
     * @param index at least 0 but no greater than {@code getPointCount(colorChannel)}
     * @return the {@code (Pin, Pout)} pair mapping the tone for that index
     *
     * @throws IllegalArgumentException if {@code colorChannel} or {@code index} was out of range
     *
     * @see #LEVEL_BLACK
     * @see #LEVEL_WHITE
     */
    public PointF getPoint(int colorChannel, int index) {
        checkArgumentColorChannel(colorChannel);
        if (index < 0 || index >= getPointCount(colorChannel)) {
            throw new IllegalArgumentException("index out of range");
        }

        final float[] curve = getCurve(colorChannel);

        final float pIn = curve[index * POINT_SIZE + OFFSET_POINT_IN];
        final float pOut = curve[index * POINT_SIZE + OFFSET_POINT_OUT];

        return new PointF(pIn, pOut);
    }

    /**
     * Copy the color curve for a single color channel from this tonemap curve into the destination.
     *
     * <p>
     * <!--The output is encoded the same as in the constructor -->
     * Values are stored as packed {@code (Pin, Pout}) points, and there are a total of
     * {@link #getPointCount} points for that respective channel.</p>
     *
     * <p>All returned coordinates are between the range of
     * [{@value #LEVEL_BLACK}, {@value #LEVEL_WHITE}].</p>
     *
     * @param destination
     *          an array big enough to hold at least {@link #getPointCount} {@code *}
     *          {@link #POINT_SIZE} elements after the {@code offset}
     * @param offset
     *          a non-negative offset into the array
     * @throws NullPointerException
     *          If {@code destination} was {@code null}
     * @throws IllegalArgumentException
     *          If offset was negative
     * @throws ArrayIndexOutOfBoundsException
     *          If there's not enough room to write the elements at the specified destination and
     *          offset.
     *
     * @see CaptureRequest#TONEMAP_CURVE_BLUE
     * @see CaptureRequest#TONEMAP_CURVE_RED
     * @see CaptureRequest#TONEMAP_CURVE_GREEN
     * @see #LEVEL_BLACK
     * @see #LEVEL_WHITE
     */
    public void copyColorCurve(int colorChannel, float[] destination,
            int offset) {
        checkArgumentNonnegative(offset, "offset must not be negative");
        checkNotNull(destination, "destination must not be null");

        if (destination.length + offset < getPointCount(colorChannel) * POINT_SIZE) {
            throw new ArrayIndexOutOfBoundsException("destination too small to fit elements");
        }

        float[] curve = getCurve(colorChannel);
        System.arraycopy(curve, /*srcPos*/0, destination, offset, curve.length);
    }

    /**
     * Check if this TonemapCurve is equal to another TonemapCurve.
     *
     * <p>Two matrices are equal if and only if all of their elements are
     * {@link Object#equals equal}.</p>
     *
     * @return {@code true} if the objects were equal, {@code false} otherwise
     */
    @Override
    public boolean equals(Object obj) {
        if (obj == null) {
            return false;
        }
        if (this == obj) {
            return true;
        }
        if (obj instanceof TonemapCurve) {
            final TonemapCurve other = (TonemapCurve) obj;
            return Arrays.equals(mRed, other.mRed) &&
                    Arrays.equals(mGreen, other.mGreen) &&
                    Arrays.equals(mBlue, other.mBlue);
        }
        return false;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int hashCode() {
        if (mHashCalculated) {
            // Avoid re-calculating hash. Data is immutable so this is both legal and faster.
            return mHashCode;
        }

        mHashCode = HashCodeHelpers.hashCode(mRed, mGreen, mBlue);
        mHashCalculated = true;

        return mHashCode;
    }

    private float[] getCurve(int colorChannel) {
        switch (colorChannel) {
            case CHANNEL_RED:
                return mRed;
            case CHANNEL_GREEN:
                return mGreen;
            case CHANNEL_BLUE:
                return mBlue;
            default:
                throw new AssertionError("colorChannel out of range");
        }
    }

    private final static int OFFSET_POINT_IN = 0;
    private final static int OFFSET_POINT_OUT = 1;

    private final float[] mRed;
    private final float[] mGreen;
    private final float[] mBlue;

    private int mHashCode;
    private boolean mHashCalculated = false;
};
