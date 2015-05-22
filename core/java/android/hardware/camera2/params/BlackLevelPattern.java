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

import java.util.Arrays;

import static com.android.internal.util.Preconditions.checkNotNull;

/**
 * Immutable class to store a 4-element vector of integers corresponding to a 2x2 pattern
 * of color channel offsets used for the black level offsets of each color channel.
 */
public final class BlackLevelPattern {

    /**
     * The number of offsets in this vector.
     */
    public static final int COUNT = 4;

    /**
     * Create a new {@link BlackLevelPattern} from a given offset array.
     *
     * <p>The given offset array must contain offsets for each color channel in
     * a 2x2 pattern corresponding to the color filter arrangement.  Offsets are
     * given in row-column scan order.</p>
     *
     * @param offsets an array containing a 2x2 pattern of offsets.
     *
     * @throws IllegalArgumentException if the given array has an incorrect length.
     * @throws NullPointerException if the given array is null.
     * @hide
     */
    public BlackLevelPattern(int[] offsets) {
        if (offsets == null) {
            throw new NullPointerException("Null offsets array passed to constructor");
        }
        if (offsets.length < COUNT) {
            throw new IllegalArgumentException("Invalid offsets array length");
        }
        mCfaOffsets = Arrays.copyOf(offsets, COUNT);
    }

    /**
     * Return the color channel offset for a given index into the array of raw pixel values.
     *
     * @param column the column index in the the raw pixel array.
     * @param row the row index in the raw pixel array.
     * @return a color channel offset.
     *
     * @throws IllegalArgumentException if a column or row given is negative.
     */
    public int getOffsetForIndex(int column, int row) {
        if (row < 0 || column < 0) {
            throw new IllegalArgumentException("column, row arguments must be positive");
        }
        return mCfaOffsets[((row & 1) << 1) | (column & 1)];
    }

    /**
     * Copy the ColorChannel offsets into the destination vector.
     *
     * <p>Offsets are given in row-column scan order for a given 2x2 color pattern.</p>
     *
     * @param destination an array big enough to hold at least {@value #COUNT} elements after the
     *          {@code offset}
     * @param offset a non-negative offset into the array
     *
     * @throws IllegalArgumentException if the offset is invalid.
     * @throws ArrayIndexOutOfBoundsException if the destination vector is too small.
     * @throws NullPointerException if the destination is null.
     */
    public void copyTo(int[] destination, int offset) {
        checkNotNull(destination, "destination must not be null");
        if (offset < 0) {
            throw new IllegalArgumentException("Null offset passed to copyTo");
        }
        if (destination.length - offset < COUNT) {
            throw new ArrayIndexOutOfBoundsException("destination too small to fit elements");
        }
        for (int i = 0; i < COUNT; ++i) {
            destination[offset + i] = mCfaOffsets[i];
        }
    }

    /**
     * Check if this {@link BlackLevelPattern} is equal to another {@link BlackLevelPattern}.
     *
     * <p>Two vectors are only equal if and only if each of the respective elements is equal.</p>
     *
     * @return {@code true} if the objects were equal, {@code false} otherwise
     */
    @Override
    public boolean equals(Object obj) {
        if (obj == null) {
            return false;
        } else if (this == obj) {
            return true;
        } else if (obj instanceof BlackLevelPattern) {
            final BlackLevelPattern other = (BlackLevelPattern) obj;
            return Arrays.equals(other.mCfaOffsets, mCfaOffsets);
        }
        return false;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int hashCode() {
        return Arrays.hashCode(mCfaOffsets);
    }

    /**
     * Return this {@link BlackLevelPattern} as a string representation.
     *
     * <p> {@code "BlackLevelPattern([%d, %d], [%d, %d])"}, where each {@code %d} represents one
     * black level offset of a color channel. The values are in the same order as channels listed
     * for the CFA layout key (see
     * {@link android.hardware.camera2.CameraCharacteristics#SENSOR_INFO_COLOR_FILTER_ARRANGEMENT}).
     * </p>
     *
     * @return string representation of {@link BlackLevelPattern}
     *
     * @see android.hardware.camera2.CameraCharacteristics#SENSOR_INFO_COLOR_FILTER_ARRANGEMENT
     */
    @Override
    public String toString() {
        return String.format("BlackLevelPattern([%d, %d], [%d, %d])", mCfaOffsets[0],
                mCfaOffsets[1], mCfaOffsets[2], mCfaOffsets[3]);
    }

    private final int[] mCfaOffsets;
}
