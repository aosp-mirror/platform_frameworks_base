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
package com.android.systemui.surfaceeffects.ripple

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
class RippleShaderTest : SysuiTestCase() {

    private lateinit var rippleShader: RippleShader

    @Before
    fun setup() {
        rippleShader = RippleShader()
    }

    @Test
    fun setMaxSize_hasCorrectSizes() {
        val expectedMaxWidth = 300f
        val expectedMaxHeight = 500f

        rippleShader.rippleSize.setMaxSize(expectedMaxWidth, expectedMaxHeight)

        assertThat(rippleShader.rippleSize.sizes.size).isEqualTo(2)
        assertThat(rippleShader.rippleSize.sizes[0]).isEqualTo(rippleShader.rippleSize.initialSize)
        val maxSize = rippleShader.rippleSize.sizes[1]
        assertThat(maxSize.t).isEqualTo(1f)
        assertThat(maxSize.width).isEqualTo(expectedMaxWidth)
        assertThat(maxSize.height).isEqualTo(expectedMaxHeight)
    }

    @Test
    fun setSizeAtProgresses_hasCorrectSizes() {
        val expectedSize0 = RippleShader.SizeAtProgress(t = 0f, width = 100f, height = 100f)
        val expectedSize1 = RippleShader.SizeAtProgress(t = 0.2f, width = 1500f, height = 1200f)
        val expectedSize2 = RippleShader.SizeAtProgress(t = 0.4f, width = 200f, height = 70f)

        rippleShader.rippleSize.setSizeAtProgresses(expectedSize0, expectedSize1, expectedSize2)

        assertThat(rippleShader.rippleSize.sizes.size).isEqualTo(3)
        assertThat(rippleShader.rippleSize.sizes[0]).isEqualTo(expectedSize0)
        assertThat(rippleShader.rippleSize.sizes[1]).isEqualTo(expectedSize1)
        assertThat(rippleShader.rippleSize.sizes[2]).isEqualTo(expectedSize2)
    }

    @Test
    fun setSizeAtProgresses_sizeListIsSortedByT() {
        val expectedSize0 = RippleShader.SizeAtProgress(t = 0f, width = 100f, height = 100f)
        val expectedSize1 = RippleShader.SizeAtProgress(t = 0.2f, width = 1500f, height = 1200f)
        val expectedSize2 = RippleShader.SizeAtProgress(t = 0.4f, width = 200f, height = 70f)
        val expectedSize3 = RippleShader.SizeAtProgress(t = 0.8f, width = 300f, height = 900f)
        val expectedSize4 = RippleShader.SizeAtProgress(t = 1f, width = 500f, height = 300f)

        // Add them in unsorted order
        rippleShader.rippleSize.setSizeAtProgresses(
            expectedSize0,
            expectedSize3,
            expectedSize2,
            expectedSize4,
            expectedSize1
        )

        assertThat(rippleShader.rippleSize.sizes.size).isEqualTo(5)
        assertThat(rippleShader.rippleSize.sizes[0]).isEqualTo(expectedSize0)
        assertThat(rippleShader.rippleSize.sizes[1]).isEqualTo(expectedSize1)
        assertThat(rippleShader.rippleSize.sizes[2]).isEqualTo(expectedSize2)
        assertThat(rippleShader.rippleSize.sizes[3]).isEqualTo(expectedSize3)
        assertThat(rippleShader.rippleSize.sizes[4]).isEqualTo(expectedSize4)
    }

    @Test
    fun update_getsCorrectNextTargetSize() {
        val expectedSize0 = RippleShader.SizeAtProgress(t = 0f, width = 100f, height = 100f)
        val expectedSize1 = RippleShader.SizeAtProgress(t = 0.2f, width = 1500f, height = 1200f)
        val expectedSize2 = RippleShader.SizeAtProgress(t = 0.4f, width = 200f, height = 70f)
        val expectedSize3 = RippleShader.SizeAtProgress(t = 0.8f, width = 300f, height = 900f)
        val expectedSize4 = RippleShader.SizeAtProgress(t = 1f, width = 500f, height = 300f)

        rippleShader.rippleSize.setSizeAtProgresses(
            expectedSize0,
            expectedSize1,
            expectedSize2,
            expectedSize3,
            expectedSize4
        )

        rippleShader.rippleSize.update(0.5f)
        // Progress is between 0.4 and 0.8 (expectedSize3 and 4), so the index should be 3.
        assertThat(rippleShader.rippleSize.currentSizeIndex).isEqualTo(3)
    }

    @Test
    fun update_sizeListIsEmpty_setsInitialSize() {
        assertThat(rippleShader.rippleSize.sizes).isEmpty()

        rippleShader.rippleSize.update(0.3f)

        assertThat(rippleShader.rippleSize.sizes.size).isEqualTo(1)
        assertThat(rippleShader.rippleSize.sizes[0]).isEqualTo(rippleShader.rippleSize.initialSize)
    }
}
