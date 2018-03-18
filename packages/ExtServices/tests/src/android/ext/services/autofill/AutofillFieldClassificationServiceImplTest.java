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

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import static com.google.common.truth.Truth.assertThat;

import android.view.autofill.AutofillValue;

/**
 * Contains the base tests that does not rely on the specific algorithm implementation.
 */
public class AutofillFieldClassificationServiceImplTest {

    private final AutofillFieldClassificationServiceImpl mService =
            new AutofillFieldClassificationServiceImpl();

    @Test
    public void testOnGetScores_nullActualValues() {
        assertThat(mService.onGetScores(null, null, null, Arrays.asList("whatever"))).isNull();
    }

    @Test
    public void testOnGetScores_emptyActualValues() {
        assertThat(mService.onGetScores(null, null, Collections.emptyList(),
                Arrays.asList("whatever"))).isNull();
    }

    @Test
    public void testOnGetScores_nullUserDataValues() {
        assertThat(mService.onGetScores(null, null,
                Arrays.asList(AutofillValue.forText("whatever")), null)).isNull();
    }

    @Test
    public void testOnGetScores_emptyUserDataValues() {
        assertThat(mService.onGetScores(null, null,
                Arrays.asList(AutofillValue.forText("whatever")), Collections.emptyList()))
                        .isNull();
    }
}
