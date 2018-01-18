/*
 * Copyright (C) 2017 The Android Open Source Project
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

package android.ext.services.autofill;

import static com.google.common.truth.Truth.assertThat;

import android.support.test.runner.AndroidJUnit4;
import android.view.autofill.AutofillValue;

import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class EditDistanceScorerTest {

    private final EditDistanceScorer mScorer = EditDistanceScorer.getInstance();

    @Test
    public void testGetScore_nullValue() {
        assertFloat(mScorer.getScore(null, "D'OH!"), 0);
    }

    @Test
    public void testGetScore_nonTextValue() {
        assertFloat(mScorer.getScore(AutofillValue.forToggle(true), "D'OH!"), 0);
    }

    @Test
    public void testGetScore_nullUserData() {
        assertFloat(mScorer.getScore(AutofillValue.forText("D'OH!"), null), 0);
    }

    @Test
    public void testGetScore_fullMatch() {
        assertFloat(mScorer.getScore(AutofillValue.forText("D'OH!"), "D'OH!"), 1);
    }

    @Test
    public void testGetScore_fullMatchMixedCase() {
        assertFloat(mScorer.getScore(AutofillValue.forText("D'OH!"), "D'oH!"), 1);
    }

    // TODO(b/70291841): might need to change it once it supports different sizes
    @Test
    public void testGetScore_mismatchDifferentSizes() {
        assertFloat(mScorer.getScore(AutofillValue.forText("One"), "MoreThanOne"), 0);
        assertFloat(mScorer.getScore(AutofillValue.forText("MoreThanOne"), "One"), 0);
    }

    @Test
    public void testGetScore_partialMatch() {
        assertFloat(mScorer.getScore(AutofillValue.forText("Dude"), "Dxxx"), 0.25F);
        assertFloat(mScorer.getScore(AutofillValue.forText("Dude"), "DUxx"), 0.50F);
        assertFloat(mScorer.getScore(AutofillValue.forText("Dude"), "DUDx"), 0.75F);
        assertFloat(mScorer.getScore(AutofillValue.forText("Dxxx"), "Dude"), 0.25F);
        assertFloat(mScorer.getScore(AutofillValue.forText("DUxx"), "Dude"), 0.50F);
        assertFloat(mScorer.getScore(AutofillValue.forText("DUDx"), "Dude"), 0.75F);
    }

    public static void assertFloat(float actualValue, float expectedValue) {
        assertThat(actualValue).isWithin(1.0e-10f).of(expectedValue);
    }
}
