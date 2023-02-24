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

package com.android.systemui.dreams.complication;

import static com.android.systemui.dreams.complication.Complication.COMPLICATION_TYPE_AIR_QUALITY;
import static com.android.systemui.dreams.complication.Complication.COMPLICATION_TYPE_CAST_INFO;
import static com.android.systemui.dreams.complication.Complication.COMPLICATION_TYPE_DATE;
import static com.android.systemui.dreams.complication.Complication.COMPLICATION_TYPE_HOME_CONTROLS;
import static com.android.systemui.dreams.complication.Complication.COMPLICATION_TYPE_SMARTSPACE;
import static com.android.systemui.dreams.complication.Complication.COMPLICATION_TYPE_TIME;
import static com.android.systemui.dreams.complication.Complication.COMPLICATION_TYPE_WEATHER;
import static com.android.systemui.dreams.complication.ComplicationUtils.convertComplicationType;
import static com.android.systemui.dreams.complication.ComplicationUtils.convertComplicationTypes;


import static com.google.common.truth.Truth.assertThat;

import android.testing.AndroidTestingRunner;

import androidx.test.filters.SmallTest;

import com.android.settingslib.dream.DreamBackend;
import com.android.systemui.SysuiTestCase;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

@SmallTest
@RunWith(AndroidTestingRunner.class)
public class ComplicationUtilsTest extends SysuiTestCase {
    @Test
    public void testConvertComplicationType() {
        assertThat(convertComplicationType(DreamBackend.COMPLICATION_TYPE_TIME))
                .isEqualTo(COMPLICATION_TYPE_TIME);
        assertThat(convertComplicationType(DreamBackend.COMPLICATION_TYPE_DATE))
                .isEqualTo(COMPLICATION_TYPE_DATE);
        assertThat(convertComplicationType(DreamBackend.COMPLICATION_TYPE_WEATHER))
                .isEqualTo(COMPLICATION_TYPE_WEATHER);
        assertThat(convertComplicationType(DreamBackend.COMPLICATION_TYPE_AIR_QUALITY))
                .isEqualTo(COMPLICATION_TYPE_AIR_QUALITY);
        assertThat(convertComplicationType(DreamBackend.COMPLICATION_TYPE_CAST_INFO))
                .isEqualTo(COMPLICATION_TYPE_CAST_INFO);
        assertThat(convertComplicationType(DreamBackend.COMPLICATION_TYPE_HOME_CONTROLS))
                .isEqualTo(COMPLICATION_TYPE_HOME_CONTROLS);
        assertThat(convertComplicationType(DreamBackend.COMPLICATION_TYPE_SMARTSPACE))
                .isEqualTo(COMPLICATION_TYPE_SMARTSPACE);
    }

    @Test
    public void testConvertComplicationTypesEmpty() {
        final Set<Integer> input = new HashSet<>();
        final int expected = Complication.COMPLICATION_TYPE_NONE;

        assertThat(convertComplicationTypes(input)).isEqualTo(expected);
    }

    @Test
    public void testConvertComplicationTypesSingleValue() {
        final Set<Integer> input = new HashSet<>(
                Collections.singleton(DreamBackend.COMPLICATION_TYPE_WEATHER));
        final int expected = Complication.COMPLICATION_TYPE_WEATHER;

        assertThat(convertComplicationTypes(input)).isEqualTo(expected);
    }

    @Test
    public void testConvertComplicationTypesSingleValueMultipleValues() {
        final Set<Integer> input = new HashSet<>(
                Arrays.asList(DreamBackend.COMPLICATION_TYPE_TIME,
                        DreamBackend.COMPLICATION_TYPE_DATE,
                        DreamBackend.COMPLICATION_TYPE_WEATHER,
                        DreamBackend.COMPLICATION_TYPE_AIR_QUALITY,
                        DreamBackend.COMPLICATION_TYPE_CAST_INFO));
        final int expected =
                Complication.COMPLICATION_TYPE_TIME | Complication.COMPLICATION_TYPE_DATE
                        | Complication.COMPLICATION_TYPE_WEATHER | COMPLICATION_TYPE_AIR_QUALITY
                        | COMPLICATION_TYPE_CAST_INFO;

        assertThat(convertComplicationTypes(input)).isEqualTo(expected);
    }
}
