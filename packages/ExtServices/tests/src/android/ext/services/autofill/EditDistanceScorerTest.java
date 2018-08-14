/*
 * Copyright (C) 2018 The Android Open Source Project
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

import static android.ext.services.autofill.EditDistanceScorer.getScore;
import static android.ext.services.autofill.EditDistanceScorer.getScores;
import static android.view.autofill.AutofillValue.forText;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import android.view.autofill.AutofillValue;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

public class EditDistanceScorerTest {

    @Test
    public void testGetScore_nullValue() {
        assertFloat(getScore(null, "D'OH!"), 0);
    }

    @Test
    public void testGetScore_nonTextValue() {
        assertFloat(getScore(AutofillValue.forToggle(true), "D'OH!"), 0);
    }

    @Test
    public void testGetScore_nullUserData() {
        assertFloat(getScore(AutofillValue.forText("D'OH!"), null), 0);
    }

    @Test
    public void testGetScore_fullMatch() {
        assertFloat(getScore(AutofillValue.forText("D'OH!"), "D'OH!"), 1);
        assertFloat(getScore(AutofillValue.forText(""), ""), 1);
    }

    @Test
    public void testGetScore_fullMatchMixedCase() {
        assertFloat(getScore(AutofillValue.forText("D'OH!"), "D'oH!"), 1);
    }

    @Test
    public void testGetScore_mismatchDifferentSizes() {
        assertFloat(getScore(AutofillValue.forText("X"), "Xy"), 0.50F);
        assertFloat(getScore(AutofillValue.forText("Xy"), "X"), 0.50F);
        assertFloat(getScore(AutofillValue.forText("One"), "MoreThanOne"), 0.27F);
        assertFloat(getScore(AutofillValue.forText("MoreThanOne"), "One"), 0.27F);
        assertFloat(getScore(AutofillValue.forText("1600 Amphitheatre Parkway"),
                "1600 Amphitheatre Pkwy"), 0.88F);
        assertFloat(getScore(AutofillValue.forText("1600 Amphitheatre Pkwy"),
                "1600 Amphitheatre Parkway"), 0.88F);
    }

    @Test
    public void testGetScore_partialMatch() {
        assertFloat(getScore(AutofillValue.forText("Dude"), "Dxxx"), 0.25F);
        assertFloat(getScore(AutofillValue.forText("Dude"), "DUxx"), 0.50F);
        assertFloat(getScore(AutofillValue.forText("Dude"), "DUDx"), 0.75F);
        assertFloat(getScore(AutofillValue.forText("Dxxx"), "Dude"), 0.25F);
        assertFloat(getScore(AutofillValue.forText("DUxx"), "Dude"), 0.50F);
        assertFloat(getScore(AutofillValue.forText("DUDx"), "Dude"), 0.75F);
    }

    @Test
    public void testGetScores() {
        final List<AutofillValue> actualValues = Arrays.asList(forText("A"), forText("b"));
        final List<String> userDataValues = Arrays.asList("a", "B", "ab", "c");
        final float[][] expectedScores = new float[][] {
            new float[] { 1F, 0F, 0.5F, 0F },
            new float[] { 0F, 1F, 0.5F, 0F }
        };
        final float[][] actualScores = getScores(actualValues, userDataValues);

        // Unfortunately, Truth does not have an easy way to compare float matrices and show useful
        // messages in case of error, so we need to check.
        assertWithMessage("actual=%s, expected=%s", toString(actualScores),
                toString(expectedScores)).that(actualScores.length).isEqualTo(2);
        assertWithMessage("actual=%s, expected=%s", toString(actualScores),
                toString(expectedScores)).that(actualScores[0].length).isEqualTo(4);
        assertWithMessage("actual=%s, expected=%s", toString(actualScores),
                toString(expectedScores)).that(actualScores[1].length).isEqualTo(4);
        for (int i = 0; i < actualScores.length; i++) {
            final float[] line = actualScores[i];
            for (int j = 0; j < line.length; j++) {
                float cell = line[j];
                assertWithMessage("wrong score at [%s, %s]", i, j).that(cell).isWithin(0.01F)
                        .of(expectedScores[i][j]);
            }
        }
    }

    public static void assertFloat(float actualValue, float expectedValue) {
        assertThat(actualValue).isWithin(0.01F).of(expectedValue);
    }

    public static String toString(float[][] matrix) {
        final StringBuilder string = new StringBuilder("[ ");
        for (int i = 0; i < matrix.length; i++) {
            string.append(Arrays.toString(matrix[i])).append(" ");
        }
        return string.append(" ]").toString();
    }
}
