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
import com.android.systemui.qs.pipeline.shared.TileSpec
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
class ResizingStateTest : SysuiTestCase() {

    private val underTest =
        ResizingState(TileSpec.create("a"), startsAsIcon = true).apply { updateAnchors(10f, 20f) }

    @Test
    fun newResizingState_setInitialValueCorrectly() {
        assertThat(underTest.anchoredDraggableState.currentValue).isEqualTo(QSDragAnchor.Icon)
    }

    @Test
    fun updateAnchors_setBoundsCorrectly() {
        assertThat(underTest.bounds).isEqualTo(10f to 20f)
    }

    @Test
    fun dragOverThreshold_resizesToLarge() = runTest {
        underTest.anchoredDraggableState.anchoredDrag { dragTo(16f) }

        assertThat(underTest.temporaryResizeOperation.spec).isEqualTo(TileSpec.create("a"))
        assertThat(underTest.temporaryResizeOperation.toIcon).isFalse()
    }

    @Test
    fun dragUnderThreshold_staysIcon() = runTest {
        underTest.anchoredDraggableState.anchoredDrag { dragTo(12f) }

        assertThat(underTest.temporaryResizeOperation.spec).isEqualTo(TileSpec.create("a"))
        assertThat(underTest.temporaryResizeOperation.toIcon).isTrue()
    }
}
