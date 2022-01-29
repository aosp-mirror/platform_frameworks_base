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
import static com.android.systemui.dreams.complication.Complication.COMPLICATION_TYPE_TIME;
import static com.android.systemui.dreams.complication.Complication.COMPLICATION_TYPE_WEATHER;
import static com.android.systemui.dreams.complication.ComplicationUtils.convertComplicationType;


import static com.google.common.truth.Truth.assertThat;

import android.testing.AndroidTestingRunner;

import androidx.test.filters.SmallTest;

import com.android.settingslib.dream.DreamBackend;
import com.android.systemui.SysuiTestCase;

import org.junit.Test;
import org.junit.runner.RunWith;

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
    }
}
