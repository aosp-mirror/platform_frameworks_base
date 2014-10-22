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

/**
 * Immutable class to store a 4-element vector of floats indexable by a bayer RAW 2x2 pixel block.
 */
public final class RggbChannelVector {
    /**
     * The number of color channels in this vector.
     */
    public static final int COUNT = 4;

    /** Red color channel in a bayer Raw pattern. */
    public static final int RED = 0;

    /** Green color channel in a bayer Raw pattern used by the even rows. */
    public static final int GREEN_EVEN = 1;

    /** Green color channel in a bayer Raw pattern used by the odd rows. */
    public static final int GREEN_ODD = 2;

    /** Blue color channel in a bayer Raw pattern. */
    public static final int BLUE = 3;

    /**
     * Create a new {@link RggbChannelVector} from an RGGB 2x2 pixel.
     *
     * <p>All pixel values are considered normalized within {@code [0.0f, 1.0f]}
     * (i.e. {@code 1.0f} could be linearized to {@code 255} if converting to a
     * non-floating point pixel representation).</p>
     *
     * <p>All arguments must be finite; NaN and infinity is not allowed.</p>
     *
     * @param red red pixel
     * @param greenEven green pixel (even row)
     * @param greenOdd green pixel (odd row)
     * @param blue blue pixel
     *
     * @throws IllegalArgumentException if any of the arguments were not finite
     */
    public RggbChannelVector(final float red, final float greenEven, final float greenOdd,
            final float blue) {
        mRed = checkArgumentFinite(red, "red");
        mGreenEven = checkArgumentFinite(greenEven, "greenEven");
        mGreenOdd = checkArgumentFinite(greenOdd, "greenOdd");
        mBlue = checkArgumentFinite(blue, "blue");
    }

    /**
     * Get the red component.
     *
     * @return a floating point value (guaranteed to be finite)
     */
    public final float getRed() {
        return mRed;
    }

    /**
     * Get the green (even rows) component.
     *
     * @return a floating point value (guaranteed to be finite)
     */
    public float getGreenEven() {
        return mGreenEven;
    }

    /**
     * Get the green (odd rows) component.
     *
     * @return a floating point value (guaranteed to be finite)
     */
    public float getGreenOdd() {
        return mGreenOdd;
    }

    /**
     * Get the blue component.
     *
     * @return a floating point value (guaranteed to be finite)
     */
    public float getBlue() {
        return mBlue;
    }

    /**
     * Get the component by the color channel index.
     *
     * <p>{@code colorChannel} must be one of {@link #RED}, {@link #GREEN_EVEN}, {@link #GREEN_ODD},
     * {@link #BLUE}.</p>
     *
     * @param colorChannel greater or equal to {@code 0} and less than {@link #COUNT}
     * @return a floating point value (guaranteed to be finite)
     *
     * @throws IllegalArgumentException if {@code colorChannel} was out of range
     */
    public float getComponent(final int colorChannel) {
        if (colorChannel < 0 || colorChannel >= COUNT) {
            throw new IllegalArgumentException("Color channel out of range");
        }

        switch (colorChannel) {
            case RED:
                return mRed;
            case GREEN_EVEN:
                return mGreenEven;
            case GREEN_ODD:
                return mGreenOdd;
            case BLUE:
                return mBlue;
            default:
                throw new AssertionError("Unhandled case " + colorChannel);
        }
    }

    /**
     * Copy the vector into the destination in the order {@code [R, Geven, Godd, B]}.
     *
     * @param destination
     *          an array big enough to hold at least {@value #COUNT} elements after the
     *          {@code offset}
     * @param offset
     *          a non-negative offset into the array
     *
     * @throws NullPointerException
     *          If {@code destination} was {@code null}
     * @throws ArrayIndexOutOfBoundsException
     *          If there's not enough room to write the elements at the specified destination and
     *          offset.
     */
    public void copyTo(final float[] destination, final int offset) {
        checkNotNull(destination, "destination must not be null");
        if (destination.length - offset < COUNT) {
            throw new ArrayIndexOutOfBoundsException("destination too small to fit elements");
        }

        destination[offset + RED] = mRed;
        destination[offset + GREEN_EVEN] = mGreenEven;
        destination[offset + GREEN_ODD] = mGreenOdd;
        destination[offset + BLUE] = mBlue;
    }

    /**
     * Check if this {@link RggbChannelVector} is equal to another {@link RggbChannelVector}.
     *
     * <p>Two vectors are only equal if and only if each of the respective elements is equal.</p>
     *
     * @return {@code true} if the objects were equal, {@code false} otherwise
     */
    @Override
    public boolean equals(final Object obj) {
        if (obj == null) {
            return false;
        } else if (this == obj) {
            return true;
        } else if (obj instanceof RggbChannelVector) {
            final RggbChannelVector other = (RggbChannelVector) obj;
            return mRed == other.mRed &&
                    mGreenEven == other.mGreenEven &&
                    mGreenOdd == other.mGreenOdd &&
                    mBlue == other.mBlue;
        }
        return false;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int hashCode() {
        return Float.floatToIntBits(mRed) ^
                Float.floatToIntBits(mGreenEven) ^
                Float.floatToIntBits(mGreenOdd) ^
                Float.floatToIntBits(mBlue);
    }

    /**
     * Return the RggbChannelVector as a string representation.
     *
     * <p> {@code "RggbChannelVector{R:%f, G_even:%f, G_odd:%f, B:%f}"}, where each
     * {@code %f} respectively represents one of the the four color channels. </p>
     *
     * @return string representation of {@link RggbChannelVector}
     */
    @Override
    public String toString() {
        return String.format("RggbChannelVector%s", toShortString());
    }

    /**
     * Return the RggbChannelVector as a string in compact form.
     *
     * <p> {@code "{R:%f, G_even:%f, G_odd:%f, B:%f}"}, where each {@code %f}
     * respectively represents one of the the four color channels. </p>
     *
     * @return compact string representation of {@link RggbChannelVector}
     */
    private String toShortString() {
        return String.format("{R:%f, G_even:%f, G_odd:%f, B:%f}",
                mRed, mGreenEven, mGreenOdd, mBlue);
    }

    private final float mRed;
    private final float mGreenEven;
    private final float mGreenOdd;
    private final float mBlue;
}
