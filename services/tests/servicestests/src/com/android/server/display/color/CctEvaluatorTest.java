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

import static com.google.common.truth.Truth.assertThat;

import androidx.test.runner.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class CctEvaluatorTest {

    @Test
    public void noEntriesInParallelArrays_setsEverythingToOne() {
        final CctEvaluator evaluator = new CctEvaluator(0, 5, new int[]{}, new int[]{});
        assertThat(evaluator.mStepsAtOffsetCcts).isEqualTo(new int[]{1, 1, 1, 1, 1, 1});
        assertThat(evaluator.mSteppedCctsAtOffsetCcts).isEqualTo(
                new int[]{0, 1, 2, 3, 4, 5});
    }

    @Test
    public void unevenNumberOfEntriesInParallelArrays_setsEverythingToOne() {
        final CctEvaluator evaluator = new CctEvaluator(0, 5, new int[]{0}, new int[]{});
        assertThat(evaluator.mStepsAtOffsetCcts).isEqualTo(new int[]{1, 1, 1, 1, 1, 1});
        assertThat(evaluator.mSteppedCctsAtOffsetCcts).isEqualTo(
                new int[]{0, 1, 2, 3, 4, 5});
    }

    @Test
    public void singleEntryInParallelArray_computesCorrectly() {
        final CctEvaluator evaluator = new CctEvaluator(0, 5, new int[]{0}, new int[]{2});
        assertThat(evaluator.mStepsAtOffsetCcts).isEqualTo(new int[]{2, 2, 2, 2, 2, 2});
        assertThat(evaluator.mSteppedCctsAtOffsetCcts).isEqualTo(
                new int[]{0, 0, 2, 2, 4, 4});
    }

    @Test
    public void minimumIsBelowFirstRange_computesCorrectly() {
        final CctEvaluator evaluator = new CctEvaluator(3000, 3005, new int[]{3002},
                new int[]{20});
        assertThat(evaluator.mStepsAtOffsetCcts).isEqualTo(new int[]{20, 20, 20, 20, 20, 20});
        assertThat(evaluator.mSteppedCctsAtOffsetCcts).isEqualTo(
                new int[]{3000, 3000, 3000, 3000, 3000, 3000});
    }

    @Test
    public void minimumIsAboveFirstRange_computesCorrectly() {
        final CctEvaluator evaluator = new CctEvaluator(3000, 3008, new int[]{3002},
                new int[]{20});
        assertThat(evaluator.mStepsAtOffsetCcts).isEqualTo(
                new int[]{20, 20, 20, 20, 20, 20, 20, 20, 20});
        assertThat(evaluator.mSteppedCctsAtOffsetCcts).isEqualTo(
                new int[]{3000, 3000, 3000, 3000, 3000, 3000, 3000, 3000, 3000});
    }

    @Test
    public void multipleStepsStartsAtThreshold_computesCorrectly() {
        final CctEvaluator evaluator = new CctEvaluator(5, 20, new int[]{0, 4, 5, 10, 18},
                new int[]{11, 7, 2, 15, 9});
        assertThat(evaluator.mStepsAtOffsetCcts).isEqualTo(
                new int[]{2, 2, 2, 2, 2, 15, 15, 15, 15, 15, 15, 15, 15, 9, 9, 9});
        assertThat(evaluator.mSteppedCctsAtOffsetCcts).isEqualTo(
                new int[]{5, 5, 7, 7, 9, 9, 9, 9, 9, 9, 9, 9, 9, 18, 18, 18});
    }

    @Test
    public void multipleStepsStartsInBetween_computesCorrectly() {
        final CctEvaluator evaluator = new CctEvaluator(4, 20, new int[]{0, 5, 10, 18},
                new int[]{14, 2, 15, 9});
        assertThat(evaluator.mStepsAtOffsetCcts).isEqualTo(
                new int[]{14, 2, 2, 2, 2, 2, 15, 15, 15, 15, 15, 15, 15, 15, 9, 9, 9});
        assertThat(evaluator.mSteppedCctsAtOffsetCcts).isEqualTo(
                new int[]{4, 4, 6, 6, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 18, 18, 18});
    }
}
