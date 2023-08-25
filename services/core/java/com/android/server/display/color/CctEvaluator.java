/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.server.display.color;

import android.animation.TypeEvaluator;
import android.util.Slog;

import com.android.internal.annotations.VisibleForTesting;

import java.util.Arrays;

/**
 * Interpolates between CCT values by a given step.
 */
class CctEvaluator implements TypeEvaluator<Integer> {

    private static final String TAG = "CctEvaluator";

    /**
     * The minimum input value, which will represent index 0 in the mValues array. Each
     * subsequent input value is offset by this amount.
     */
    private final int mIndexOffset;
    /**
     * Cached step values at each CCT value (offset by the {@link #mIndexOffset} above). For
     * example, if the minimum CCT is 2000K (which is set to mIndexOffset), then the 0th index of
     * this array is equivalent to the step value at 2000K, 1st index corresponds to 2001K, and so
     * on.
     */
    @VisibleForTesting
    final int[] mStepsAtOffsetCcts;
    /**
     * Pre-computed stepped CCTs. These will be accessed frequently; the memory cost of caching them
     * is well-spent.
     */
    @VisibleForTesting
    final int[] mSteppedCctsAtOffsetCcts;

    CctEvaluator(int min, int max, int[] cctRangeMinimums, int[] steps) {
        final int delta = max - min + 1;
        mStepsAtOffsetCcts = new int[delta];
        mSteppedCctsAtOffsetCcts = new int[delta];
        mIndexOffset = min;

        final int parallelArraysLength = cctRangeMinimums.length;
        if (cctRangeMinimums.length != steps.length) {
            Slog.e(TAG,
                    "Parallel arrays cctRangeMinimums and steps are different lengths; setting "
                            + "step of 1");
            setStepOfOne();
        } else if (parallelArraysLength == 0) {
            Slog.e(TAG, "No cctRangeMinimums or steps are set; setting step of 1");
            setStepOfOne();
        } else {
            int parallelArraysIndex = 0;
            int index = 0;
            int lastSteppedCct = Integer.MIN_VALUE;
            while (index < delta) {
                final int cct = index + mIndexOffset;
                int nextParallelArraysIndex = parallelArraysIndex + 1;
                while (nextParallelArraysIndex < parallelArraysLength
                        && cct >= cctRangeMinimums[nextParallelArraysIndex]) {
                    parallelArraysIndex = nextParallelArraysIndex;
                    nextParallelArraysIndex++;
                }
                mStepsAtOffsetCcts[index] = steps[parallelArraysIndex];
                if (lastSteppedCct == Integer.MIN_VALUE
                        || Math.abs(lastSteppedCct - cct) >= steps[parallelArraysIndex]) {
                    lastSteppedCct = cct;
                }
                mSteppedCctsAtOffsetCcts[index] = lastSteppedCct;
                index++;
            }
        }
    }

    @Override
    public Integer evaluate(float fraction, Integer startValue, Integer endValue) {
        final int cct = (int) (startValue + fraction * (endValue - startValue));
        final int index = cct - mIndexOffset;
        if (index < 0 || index >= mSteppedCctsAtOffsetCcts.length) {
            Slog.e(TAG, "steppedCctValueAt: returning same since invalid requested index=" + index);
            return cct;
        }
        return mSteppedCctsAtOffsetCcts[index];
    }

    private void setStepOfOne() {
        Arrays.fill(mStepsAtOffsetCcts, 1);
        for (int i = 0; i < mSteppedCctsAtOffsetCcts.length; i++) {
            mSteppedCctsAtOffsetCcts[i] = mIndexOffset + i;
        }
    }
}
