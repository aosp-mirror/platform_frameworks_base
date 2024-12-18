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

package com.android.systemui.media.controls.util

import android.util.Pair as APair
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
class MediaDataUtilsTest : SysuiTestCase() {

    @Test
    fun testScaleFactor_zeroInput_returnsZero() {
        val input = APair(0, 0)
        val target = APair(100, 100)

        val scale = MediaDataUtils.getScaleFactor(input, target)
        assertThat(scale).isEqualTo(0f)
    }

    @Test
    fun testScaleFactor_tooWide_scaleDown() {
        val input = APair(400, 200)
        val target = APair(100, 100)

        val scale = MediaDataUtils.getScaleFactor(input, target)
        assertThat(scale).isEqualTo(0.5f)
    }

    @Test
    fun testScaleFactor_tooTall_scaleDown() {
        val input = APair(200, 400)
        val target = APair(100, 100)

        val scale = MediaDataUtils.getScaleFactor(input, target)
        assertThat(scale).isEqualTo(0.5f)
    }

    @Test
    fun testScaleFactor_lessWide_scaleUp() {
        val input = APair(50, 100)
        val target = APair(100, 100)

        val scale = MediaDataUtils.getScaleFactor(input, target)
        assertThat(scale).isEqualTo(2f)
    }

    @Test
    fun testScaleFactor_lessTall_scaleUp() {
        val input = APair(100, 50)
        val target = APair(100, 100)

        val scale = MediaDataUtils.getScaleFactor(input, target)
        assertThat(scale).isEqualTo(2f)
    }
}
