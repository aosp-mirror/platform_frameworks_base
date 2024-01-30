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

package com.android.internal.jank;

import static com.android.internal.jank.DisplayRefreshRate.REFRESH_RATE_120_HZ;
import static com.android.internal.jank.DisplayRefreshRate.REFRESH_RATE_240_HZ;
import static com.android.internal.jank.DisplayRefreshRate.REFRESH_RATE_30_HZ;
import static com.android.internal.jank.DisplayRefreshRate.REFRESH_RATE_60_HZ;
import static com.android.internal.jank.DisplayRefreshRate.REFRESH_RATE_90_HZ;
import static com.android.internal.jank.DisplayRefreshRate.getRefreshRate;

import static com.google.common.truth.Truth.assertThat;

import androidx.test.filters.SmallTest;

import org.junit.Test;

@SmallTest
public class DisplayRefreshRateTest {
    @Test
    public void testRefreshRateMapping() {
        assertThat(getRefreshRate((long) 1e9 / 30)).isEqualTo(REFRESH_RATE_30_HZ);
        assertThat(getRefreshRate((long) 1e9 / 60)).isEqualTo(REFRESH_RATE_60_HZ);
        assertThat(getRefreshRate((long) 1e9 / 90)).isEqualTo(REFRESH_RATE_90_HZ);
        assertThat(getRefreshRate((long) 1e9 / 120)).isEqualTo(REFRESH_RATE_120_HZ);
        assertThat(getRefreshRate((long) 1e9 / 240)).isEqualTo(REFRESH_RATE_240_HZ);
    }
}
