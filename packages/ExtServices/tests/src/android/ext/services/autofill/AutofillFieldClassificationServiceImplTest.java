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

import static android.service.autofill.AutofillFieldClassificationService.REQUIRED_ALGORITHM_EXACT_MATCH;
import static android.view.autofill.AutofillValue.forText;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import android.os.Bundle;
import android.view.autofill.AutofillValue;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;

/**
 * Contains the base tests that does not rely on the specific algorithm implementation.
 */
public class AutofillFieldClassificationServiceImplTest {

    private final AutofillFieldClassificationServiceImpl mService =
            new AutofillFieldClassificationServiceImpl();

    @Test
    public void testOnCalculateScores_nullActualValues() {
        assertThat(mService.onCalculateScores(null, null, null, null, null, null, null)).isNull();
    }

    @Test
    public void testOnCalculateScores_emptyActualValues() {
        assertThat(mService.onCalculateScores(Collections.emptyList(), Arrays.asList("whatever"),
                null, null, null, null, null)).isNull();
    }

    @Test
    public void testOnCalculateScores_nullUserDataValues() {
        assertThat(mService.onCalculateScores(Arrays.asList(AutofillValue.forText("whatever")),
                null, null, null, null, null, null)).isNull();
    }

    @Test
    public void testOnCalculateScores_emptyUserDataValues() {
        assertThat(mService.onCalculateScores(Arrays.asList(AutofillValue.forText("whatever")),
                Collections.emptyList(), null, null, null, null, null))
                .isNull();
    }

    @Test
    public void testCalculateScores() {
        final List<AutofillValue> actualValues = Arrays.asList(forText("A"), forText("b"),
                forText("dude"));
        final List<String> userDataValues = Arrays.asList("a", "b", "B", "ab", "c", "dude",
                "sweet_dude", "dude_sweet");
        final List<String> categoryIds = Arrays.asList("cat", "cat", "cat", "cat", "cat", "last4",
                "last4", "last4");
        final HashMap<String, String> algorithms = new HashMap<>(1);
        algorithms.put("last4", REQUIRED_ALGORITHM_EXACT_MATCH);

        final Bundle last4Bundle = new Bundle();
        last4Bundle.putInt("suffix", 4);

        final HashMap<String, Bundle> args = new HashMap<>(1);
        args.put("last4", last4Bundle);

        final float[][] expectedScores = new float[][] {
                new float[] { 1F, 0F, 0F, 0.5F, 0F, 0F, 0F, 0F },
                new float[] { 0F, 1F, 1F, 0.5F, 0F, 0F, 0F, 0F },
                new float[] { 0F, 0F, 0F, 0F  , 0F, 1F, 1F, 0F }
        };
        final float[][] actualScores = mService.onCalculateScores(actualValues, userDataValues,
                categoryIds, null, null, algorithms, args);

        // Unfortunately, Truth does not have an easy way to compare float matrices and show useful
        // messages in case of error, so we need to check.
        assertWithMessage("actual=%s, expected=%s", toString(actualScores),
                toString(expectedScores)).that(actualScores.length).isEqualTo(3);
        for (int i = 0; i < 3; i++) {
            assertWithMessage("actual=%s, expected=%s", toString(actualScores),
                    toString(expectedScores)).that(actualScores[i].length).isEqualTo(8);
        }

        for (int i = 0; i < actualScores.length; i++) {
            final float[] line = actualScores[i];
            for (int j = 0; j < line.length; j++) {
                float cell = line[j];
                assertWithMessage("wrong score at [%s, %s]", i, j).that(cell).isWithin(0.01F)
                        .of(expectedScores[i][j]);
            }
        }
    }

    public static String toString(float[][] matrix) {
        final StringBuilder string = new StringBuilder("[ ");
        for (int i = 0; i < matrix.length; i++) {
            string.append(Arrays.toString(matrix[i])).append(" ");
        }
        return string.append(" ]").toString();
    }
}
