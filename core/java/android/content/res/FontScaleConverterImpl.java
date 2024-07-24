/*
 * Copyright (C) 2022 The Android Open Source Project
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

package android.content.res;

import android.annotation.NonNull;
import android.util.MathUtils;

import com.android.internal.annotations.VisibleForTesting;

import java.util.Arrays;

/**
 * A lookup table for non-linear font scaling. Converts font sizes given in "sp" dimensions to a
 * "dp" dimension according to a non-linear curve by interpolating values in a lookup table.
 *
 * {@see FontScaleConverter}
 *
 * @hide
 */
// Needs to be public so the Kotlin test can see it
@VisibleForTesting(visibility = VisibleForTesting.Visibility.PACKAGE)
public class FontScaleConverterImpl implements FontScaleConverter {

    /** @hide */
    @VisibleForTesting
    public final float[] mFromSpValues;
    /** @hide */
    @VisibleForTesting
    public final float[] mToDpValues;

    /**
     * Creates a lookup table for the given conversions.
     *
     * <p>Any "sp" value not in the lookup table will be derived via linear interpolation.
     *
     * <p>The arrays must be sorted ascending and monotonically increasing.
     *
     * @param fromSp array of dimensions in SP
     * @param toDp array of dimensions in DP that correspond to an SP value in fromSp
     *
     * @throws IllegalArgumentException if the array lengths don't match or are empty
     * @hide
     */
    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PACKAGE)
    public FontScaleConverterImpl(@NonNull float[] fromSp, @NonNull float[] toDp) {
        if (fromSp.length != toDp.length || fromSp.length == 0) {
            throw new IllegalArgumentException("Array lengths must match and be nonzero");
        }

        mFromSpValues = fromSp;
        mToDpValues = toDp;
    }

    /**
     * Convert a dimension in "dp" back to "sp" using the lookup table.
     *
     * @hide
     */
    @Override
    public float convertDpToSp(float dp) {
        return lookupAndInterpolate(dp, mToDpValues, mFromSpValues);
    }

    /**
     * Convert a dimension in "sp" to "dp" using the lookup table.
     *
     * @hide
     */
    @Override
    public float convertSpToDp(float sp) {
        return lookupAndInterpolate(sp, mFromSpValues, mToDpValues);
    }

    private static float lookupAndInterpolate(
            float sourceValue,
            float[] sourceValues,
            float[] targetValues
    ) {
        final float sourceValuePositive = Math.abs(sourceValue);
        // TODO(b/247861374): find a match at a higher index?
        final float sign = Math.signum(sourceValue);
        // We search for exact matches only, even if it's just a little off. The interpolation will
        // handle any non-exact matches.
        final int index = Arrays.binarySearch(sourceValues, sourceValuePositive);
        if (index >= 0) {
            // exact match, return the matching dp
            return sign * targetValues[index];
        } else {
            // must be a value in between index and index + 1: interpolate.
            final int lowerIndex = -(index + 1) - 1;

            final float startSp;
            final float endSp;
            final float startDp;
            final float endDp;

            if (lowerIndex >= sourceValues.length - 1) {
                // It's past our lookup table. Determine the last elements' scaling factor and use.
                startSp = sourceValues[sourceValues.length - 1];
                startDp = targetValues[sourceValues.length - 1];

                if (startSp == 0) return 0;

                final float scalingFactor = startDp / startSp;
                return sourceValue * scalingFactor;
            } else if (lowerIndex == -1) {
                // It's smaller than the smallest value in our table. Interpolate from 0.
                startSp = 0;
                startDp = 0;
                endSp = sourceValues[0];
                endDp = targetValues[0];
            } else {
                startSp = sourceValues[lowerIndex];
                endSp = sourceValues[lowerIndex + 1];
                startDp = targetValues[lowerIndex];
                endDp = targetValues[lowerIndex + 1];
            }

            return sign
                    * MathUtils.constrainedMap(startDp, endDp, startSp, endSp, sourceValuePositive);
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null) return false;
        if (!(o instanceof FontScaleConverterImpl)) return false;
        FontScaleConverterImpl that = (FontScaleConverterImpl) o;
        return Arrays.equals(mFromSpValues, that.mFromSpValues)
                && Arrays.equals(mToDpValues, that.mToDpValues);
    }

    @Override
    public int hashCode() {
        int result = Arrays.hashCode(mFromSpValues);
        result = 31 * result + Arrays.hashCode(mToDpValues);
        return result;
    }

    @Override
    public String toString() {
        return "FontScaleConverter{"
                + "fromSpValues="
                + Arrays.toString(mFromSpValues)
                + ", toDpValues="
                + Arrays.toString(mToDpValues)
                + '}';
    }
}
