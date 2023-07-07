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
 * "dp" dimension according to a non-linear curve.
 *
 * <p>This is meant to improve readability at larger font scales: larger fonts will scale up more
 * slowly than smaller fonts, so we don't get ridiculously huge fonts that don't fit on the screen.
 *
 * <p>The thinking here is that large fonts are already big enough to read, but we still want to
 * scale them slightly to preserve the visual hierarchy when compared to smaller fonts.
 *
 * @hide
 */
public class FontScaleConverter {

    @VisibleForTesting
    final float[] mFromSpValues;
    @VisibleForTesting
    final float[] mToDpValues;

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
    public FontScaleConverter(@NonNull float[] fromSp, @NonNull float[] toDp) {
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
    public float convertDpToSp(float dp) {
        return lookupAndInterpolate(dp, mToDpValues, mFromSpValues);
    }

    /**
     * Convert a dimension in "sp" to "dp" using the lookup table.
     *
     * @hide
     */
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
        if (!(o instanceof FontScaleConverter)) return false;
        FontScaleConverter that = (FontScaleConverter) o;
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
