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

import static android.ext.services.autofill.ExactMatch.calculateScore;

import static com.google.common.truth.Truth.assertThat;

import static org.testng.Assert.assertThrows;

import android.os.Bundle;
import android.view.autofill.AutofillValue;

import org.junit.Test;

public class ExactMatchTest {

    private Bundle last4Bundle() {
        final Bundle bundle = new Bundle();
        bundle.putInt("suffix", 4);
        return bundle;
    }

    @Test
    public void testCalculateScore_nullValue() {
        assertFloat(calculateScore(null, "TEST", null), 0);
    }

    @Test
    public void testCalculateScore_nonTextValue() {
        assertFloat(calculateScore(AutofillValue.forToggle(true), "TEST", null), 0);
    }

    @Test
    public void testCalculateScore_nullUserData() {
        assertFloat(calculateScore(AutofillValue.forText("TEST"), null, null), 0);
    }

    @Test
    public void testCalculateScore_succeedMatchMixedCases_last4() {
        final Bundle last4 = last4Bundle();
        assertFloat(calculateScore(AutofillValue.forText("TEST"), "1234 test", last4), 1);
        assertFloat(calculateScore(AutofillValue.forText("test"), "1234 TEST", last4), 1);
    }

    @Test
    public void testCalculateScore_mismatchDifferentSizes_last4() {
        final Bundle last4 = last4Bundle();
        assertFloat(calculateScore(AutofillValue.forText("TEST"), "TEST1", last4), 0);
        assertFloat(calculateScore(AutofillValue.forText(""), "TEST", last4), 0);
        assertFloat(calculateScore(AutofillValue.forText("TEST"), "", last4), 0);
    }

    @Test
    public void testCalculateScore_match() {
        final Bundle last4 = last4Bundle();
        assertFloat(calculateScore(AutofillValue.forText("1234 1234 1234 1234"),
                "xxxx xxxx xxxx 1234", last4), 1);
        assertFloat(calculateScore(AutofillValue.forText("TEST"), "TEST", null), 1);
        assertFloat(calculateScore(AutofillValue.forText("TEST 1234"), "1234", last4), 1);
        assertFloat(calculateScore(AutofillValue.forText("TEST"), "test", null), 1);
    }

    @Test
    public void testCalculateScore_badBundle() {
        final Bundle bundle = new Bundle();
        bundle.putInt("suffix", -2);
        assertThrows(IllegalArgumentException.class, () -> calculateScore(
                AutofillValue.forText("TEST"), "TEST", bundle));

        final Bundle largeBundle = new Bundle();
        largeBundle.putInt("suffix", 10);
        assertFloat(calculateScore(AutofillValue.forText("TEST"), "TEST", largeBundle), 1);

        final Bundle stringBundle = new Bundle();
        stringBundle.putString("suffix", "value");
        assertThrows(IllegalArgumentException.class, () -> calculateScore(
                AutofillValue.forText("TEST"), "TEST", stringBundle));

    }

    public static void assertFloat(float actualValue, float expectedValue) {
        assertThat(actualValue).isWithin(0.01F).of(expectedValue);
    }
}
