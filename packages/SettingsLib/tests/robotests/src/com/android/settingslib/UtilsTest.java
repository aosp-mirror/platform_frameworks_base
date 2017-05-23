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
package com.android.settingslib;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.annotation.Config;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.content.res.Resources;

@RunWith(SettingLibRobolectricTestRunner.class)
@Config(manifest = TestConfig.MANIFEST_PATH, sdk = TestConfig.SDK_VERSION)
public class UtilsTest {
    private static final double[] TEST_PERCENTAGES = {0, 0.4, 0.5, 0.6, 49, 49.3, 49.8, 50, 100};
    private static final String PERCENTAGE_0 = "0%";
    private static final String PERCENTAGE_1 = "1%";
    private static final String PERCENTAGE_49 = "49%";
    private static final String PERCENTAGE_50 = "50%";
    private static final String PERCENTAGE_100 = "100%";

    @Test
    public void testFormatPercentage_RoundTrue_RoundUpIfPossible() {
        final String[] expectedPercentages = {PERCENTAGE_0, PERCENTAGE_0, PERCENTAGE_1,
                PERCENTAGE_1, PERCENTAGE_49, PERCENTAGE_49, PERCENTAGE_50, PERCENTAGE_50,
                PERCENTAGE_100};

        for (int i = 0, size = TEST_PERCENTAGES.length; i < size; i++) {
            final String percentage = Utils.formatPercentage(TEST_PERCENTAGES[i], true);
            assertThat(percentage).isEqualTo(expectedPercentages[i]);
        }
    }

    @Test
    public void testFormatPercentage_RoundFalse_NoRound() {
        final String[] expectedPercentages = {PERCENTAGE_0, PERCENTAGE_0, PERCENTAGE_0,
                PERCENTAGE_0, PERCENTAGE_49, PERCENTAGE_49, PERCENTAGE_49, PERCENTAGE_50,
                PERCENTAGE_100};

        for (int i = 0, size = TEST_PERCENTAGES.length; i < size; i++) {
            final String percentage = Utils.formatPercentage(TEST_PERCENTAGES[i], false);
            assertThat(percentage).isEqualTo(expectedPercentages[i]);
        }
    }

    @Test
    public void testStorageManagerDaysToRetainUsesResources() {
        Resources resources = mock(Resources.class);
        when(resources.getInteger(
                        eq(
                                com.android
                                        .internal
                                        .R
                                        .integer
                                        .config_storageManagerDaystoRetainDefault)))
                .thenReturn(60);
        assertThat(Utils.getDefaultStorageManagerDaysToRetain(resources)).isEqualTo(60);
    }
}
