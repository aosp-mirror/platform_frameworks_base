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
package com.android.systemui.volume;

import static com.google.common.truth.Truth.assertThat;

import android.media.MediaMetadata;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import com.android.systemui.SysuiTestCase;

import org.junit.Test;
import org.junit.runner.RunWith;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class UtilTest extends SysuiTestCase {

    @Test
    public void testMediaMetadataToString_null() {
        assertThat(Util.mediaMetadataToString(null)).isNull();
    }

    @Test
    public void testMediaMetadataToString_notNull() {
        assertThat(Util.mediaMetadataToString(new MediaMetadata.Builder().build())).isNotNull();
    }

    @Test
    public void translateToRange_translatesStartToStart() {
        assertThat(
                (int) Util.translateToRange(
                        /* value= */ 0,
                        /* valueRangeStart= */ 0,
                        /* valueRangeEnd= */ 7,
                        /* targetRangeStart= */ 0,
                        /* targetRangeEnd= */700)
        ).isEqualTo(0);
    }

    @Test
    public void translateToRange_translatesValueToValue() {
        assertThat(
                (int) Util.translateToRange(
                        /* value= */ 4,
                        /* valueRangeStart= */ 0,
                        /* valueRangeEnd= */ 7,
                        /* targetRangeStart= */ 0,
                        /* targetRangeEnd= */700)
        ).isEqualTo(400);
    }

    @Test
    public void translateToRange_translatesEndToEnd() {
        assertThat(
                (int) Util.translateToRange(
                        /* value= */ 7,
                        /* valueRangeStart= */ 0,
                        /* valueRangeEnd= */ 7,
                        /* targetRangeStart= */ 0,
                        /* targetRangeEnd= */700)
        ).isEqualTo(700);
    }

    @Test
    public void translateToRange_returnsStartForEmptyRange() {
        assertThat(
                (int) Util.translateToRange(
                        /* value= */ 7,
                        /* valueRangeStart= */ 7,
                        /* valueRangeEnd= */ 7,
                        /* targetRangeStart= */ 700,
                        /* targetRangeEnd= */700)
        ).isEqualTo(700);
    }
}
