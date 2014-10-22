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

import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.utils.HashCodeHelpers;
import android.util.Rational;

import java.util.Arrays;

/**
 * Immutable class for describing a 3x3 matrix of {@link Rational} values in row-major order.
 *
 * <p>This matrix maps a transform from one color space to another. For the particular color space
 * source and target, see the appropriate camera metadata documentation for the key that provides
 * this value.</p>
 *
 * @see CameraMetadata
 */
public final class ColorSpaceTransform {

    /** The number of rows in this matrix. */
    private static final int ROWS = 3;

    /** The number of columns in this matrix. */
    private static final int COLUMNS = 3;

    /** The number of total Rational elements in this matrix. */
    private static final int COUNT = ROWS * COLUMNS;

    /** Number of int elements in a rational. */
    private static final int RATIONAL_SIZE = 2;

    /** Numerator offset inside a rational (pair). */
    private static final int OFFSET_NUMERATOR = 0;

    /** Denominator offset inside a rational (pair). */
    private static final int OFFSET_DENOMINATOR = 1;

    /** Number of int elements in this matrix. */
    private static final int COUNT_INT = ROWS * COLUMNS * RATIONAL_SIZE;

    /**
     * Create a new immutable {@link ColorSpaceTransform} instance from a {@link Rational} array.
     *
     * <p>The elements must be stored in a row-major order.</p>
     *
     * @param elements An array of {@code 9} elements
     *
     * @throws IllegalArgumentException
     *            if the count of {@code elements} is not {@code 9}
     * @throws NullPointerException
     *            if {@code elements} or any sub-element is {@code null}
     */
    public ColorSpaceTransform(Rational[] elements) {

        checkNotNull(elements, "elements must not be null");
        if (elements.length != COUNT) {
            throw new IllegalArgumentException("elements must be " + COUNT + " length");
        }

        mElements = new int[COUNT_INT];

        for (int i = 0; i < elements.length; ++i) {
            checkNotNull(elements, "element[" + i + "] must not be null");
            mElements[i * RATIONAL_SIZE + OFFSET_NUMERATOR] = elements[i].getNumerator();
            mElements[i * RATIONAL_SIZE + OFFSET_DENOMINATOR] = elements[i].getDenominator();
        }
    }

    /**
     * Create a new immutable {@link ColorSpaceTransform} instance from an {@code int} array.
     *
     * <p>The elements must be stored in a row-major order. Each rational is stored
     * contiguously as a {@code (numerator, denominator)} pair.</p>
     *
     * <p>In particular:<pre>{@code
     * int[] elements = new int[
     *     N11, D11, N12, D12, N13, D13,
     *     N21, D21, N22, D22, N23, D23,
     *     N31, D31, N32, D32, N33, D33
     * ];
     *
     * new ColorSpaceTransform(elements)}</pre>
     *
     * where {@code Nij} and {@code Dij} is the numerator and denominator for row {@code i} and
     * column {@code j}.</p>
     *
     * @param elements An array of {@code 18} elements
     *
     * @throws IllegalArgumentException
     *            if the count of {@code elements} is not {@code 18}
     * @throws NullPointerException
     *            if {@code elements} is {@code null}
     */
    public ColorSpaceTransform(int[] elements) {
        checkNotNull(elements, "elements must not be null");
        if (elements.length != COUNT_INT) {
            throw new IllegalArgumentException("elements must be " + COUNT_INT + " length");
        }

        for (int i = 0; i < elements.length; ++i) {
            checkNotNull(elements, "element " + i + " must not be null");
        }

        mElements = Arrays.copyOf(elements, elements.length);
    }

    /**
     * Get an element of this matrix by its row and column.
     *
     * <p>The rows must be within the range [0, 3),
     * and the column must be within the range [0, 3).</p>
     *
     * @return element (non-{@code null})
     *
     * @throws IllegalArgumentException if column or row was out of range
     */
    public Rational getElement(int column, int row) {
        if (column < 0 || column >= COLUMNS) {
            throw new IllegalArgumentException("column out of range");
        } else if (row < 0 || row >= ROWS) {
            throw new IllegalArgumentException("row out of range");
        }

        int numerator = mElements[(row * COLUMNS + column) * RATIONAL_SIZE + OFFSET_NUMERATOR];
        int denominator = mElements[(row * COLUMNS + column) * RATIONAL_SIZE + OFFSET_DENOMINATOR];

        return new Rational(numerator, denominator);
    }

    /**
     * Copy the {@link Rational} elements in row-major order from this matrix into the destination.
     *
     * @param destination
     *          an array big enough to hold at least {@code 9} elements after the
     *          {@code offset}
     * @param offset
     *          a non-negative offset into the array
     * @throws NullPointerException
     *          If {@code destination} was {@code null}
     * @throws ArrayIndexOutOfBoundsException
     *          If there's not enough room to write the elements at the specified destination and
     *          offset.
     */
    public void copyElements(Rational[] destination, int offset) {
        checkArgumentNonnegative(offset, "offset must not be negative");
        checkNotNull(destination, "destination must not be null");
        if (destination.length - offset < COUNT) {
            throw new ArrayIndexOutOfBoundsException("destination too small to fit elements");
        }

        for (int i = 0, j = 0; i < COUNT; ++i, j += RATIONAL_SIZE) {
            int numerator = mElements[j + OFFSET_NUMERATOR];
            int denominator = mElements[j + OFFSET_DENOMINATOR];

            destination[i + offset] = new Rational(numerator, denominator);
        }
    }

    /**
     * Copy the {@link Rational} elements in row-major order from this matrix into the destination.
     *
     * <p>Each element is stored as a contiguous rational packed as a
     * {@code (numerator, denominator)} pair of ints, identical to the
     * {@link ColorSpaceTransform#ColorSpaceTransform(int[]) constructor}.</p>
     *
     * @param destination
     *          an array big enough to hold at least {@code 18} elements after the
     *          {@code offset}
     * @param offset
     *          a non-negative offset into the array
     * @throws NullPointerException
     *          If {@code destination} was {@code null}
     * @throws ArrayIndexOutOfBoundsException
     *          If there's not enough room to write the elements at the specified destination and
     *          offset.
     *
     * @see ColorSpaceTransform#ColorSpaceTransform(int[])
     */
    public void copyElements(int[] destination, int offset) {
        checkArgumentNonnegative(offset, "offset must not be negative");
        checkNotNull(destination, "destination must not be null");
        if (destination.length - offset < COUNT_INT) {
            throw new ArrayIndexOutOfBoundsException("destination too small to fit elements");
        }

        // Manual copy faster than System#arraycopy for very small loops
        for (int i = 0; i < COUNT_INT; ++i) {
            destination[i + offset] = mElements[i];
        }
    }

    /**
     * Check if this {@link ColorSpaceTransform} is equal to another {@link ColorSpaceTransform}.
     *
     * <p>Two color space transforms are equal if and only if all of their elements are
     * {@link Object#equals equal}.</p>
     *
     * @return {@code true} if the objects were equal, {@code false} otherwise
     */
    @Override
    public boolean equals(final Object obj) {
        if (obj == null) {
            return false;
        }
        if (this == obj) {
            return true;
        }
        if (obj instanceof ColorSpaceTransform) {
            final ColorSpaceTransform other = (ColorSpaceTransform) obj;
            for (int i = 0, j = 0; i < COUNT; ++i, j += RATIONAL_SIZE) {
                int numerator = mElements[j + OFFSET_NUMERATOR];
                int denominator = mElements[j + OFFSET_DENOMINATOR];
                int numeratorOther = other.mElements[j + OFFSET_NUMERATOR];
                int denominatorOther = other.mElements[j + OFFSET_DENOMINATOR];
                Rational r = new Rational(numerator, denominator);
                Rational rOther = new Rational(numeratorOther, denominatorOther);
                if (!r.equals(rOther)) {
                    return false;
                }
            }
            return true;
        }
        return false;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int hashCode() {
        return HashCodeHelpers.hashCode(mElements);
    }

    /**
     * Return the color space transform as a string representation.
     *
     *  <p> Example:
     * {@code "ColorSpaceTransform([1/1, 0/1, 0/1], [0/1, 1/1, 0/1], [0/1, 0/1, 1/1])"} is an
     * identity transform. Elements are printed in row major order. </p>
     *
     * @return string representation of color space transform
     */
    @Override
    public String toString() {
        return String.format("ColorSpaceTransform%s", toShortString());
    }

    /**
     * Return the color space transform as a compact string representation.
     *
     *  <p> Example:
     * {@code "([1/1, 0/1, 0/1], [0/1, 1/1, 0/1], [0/1, 0/1, 1/1])"} is an identity transform.
     * Elements are printed in row major order. </p>
     *
     * @return compact string representation of color space transform
     */
    private String toShortString() {
        StringBuilder sb = new StringBuilder("(");
        for (int row = 0, i = 0; row < ROWS; row++) {
            sb.append("[");
            for (int col = 0; col < COLUMNS; col++, i += RATIONAL_SIZE) {
                int numerator = mElements[i + OFFSET_NUMERATOR];
                int denominator = mElements[i + OFFSET_DENOMINATOR];
                sb.append(numerator);
                sb.append("/");
                sb.append(denominator);
                if (col < COLUMNS - 1) {
                    sb.append(", ");
                }
            }
            sb.append("]");
            if (row < ROWS - 1) {
                sb.append(", ");
            }
        }
        sb.append(")");
        return sb.toString();
    }

    private final int[] mElements;
}
