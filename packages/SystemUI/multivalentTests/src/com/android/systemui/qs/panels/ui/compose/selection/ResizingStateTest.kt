/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.systemui.qs.panels.ui.compose.selection

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
class ResizingStateTest : SysuiTestCase() {

    @Test
    fun drag_updatesStateCorrectly() {
        var resized = false
        val underTest =
            ResizingState(TileWidths(base = 0, min = 0, max = 10)) { resized = !resized }

        assertThat(underTest.width).isEqualTo(0)

        underTest.onDrag(2f)
        assertThat(underTest.width).isEqualTo(2)

        underTest.onDrag(1f)
        assertThat(underTest.width).isEqualTo(3)
        assertThat(resized).isTrue()

        underTest.onDrag(-1f)
        assertThat(underTest.width).isEqualTo(2)
        assertThat(resized).isFalse()
    }

    @Test
    fun dragOutOfBounds_isClampedCorrectly() {
        val underTest = ResizingState(TileWidths(base = 0, min = 0, max = 10)) {}

        assertThat(underTest.width).isEqualTo(0)

        underTest.onDrag(100f)
        assertThat(underTest.width).isEqualTo(10)

        underTest.onDrag(-200f)
        assertThat(underTest.width).isEqualTo(0)
    }
}
