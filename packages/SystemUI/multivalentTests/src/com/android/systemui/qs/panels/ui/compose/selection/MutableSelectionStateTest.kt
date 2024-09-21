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
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
class MutableSelectionStateTest : SysuiTestCase() {
    private val underTest = MutableSelectionState()

    @Test
    fun selectTile_isCorrectlySelected() {
        assertThat(underTest.isSelected(TEST_SPEC)).isFalse()

        underTest.select(TEST_SPEC)
        assertThat(underTest.isSelected(TEST_SPEC)).isTrue()

        underTest.unSelect()
        assertThat(underTest.isSelected(TEST_SPEC)).isFalse()

        val newSpec = TileSpec.create("newSpec")
        underTest.select(TEST_SPEC)
        underTest.select(newSpec)
        assertThat(underTest.isSelected(TEST_SPEC)).isFalse()
        assertThat(underTest.isSelected(newSpec)).isTrue()
    }

    @Test
    fun startResize_createsResizingState() {
        assertThat(underTest.resizingState).isNull()

        // Resizing starts but no tile is selected
        underTest.onResizingDragStart(TileWidths(0, 0, 1)) {}
        assertThat(underTest.resizingState).isNull()

        // Resizing starts with a selected tile
        underTest.select(TEST_SPEC)
        underTest.onResizingDragStart(TileWidths(0, 0, 1)) {}

        assertThat(underTest.resizingState).isNotNull()
    }

    @Test
    fun endResize_clearsResizingState() {
        val spec = TileSpec.create("testSpec")

        // Resizing starts with a selected tile
        underTest.select(spec)
        underTest.onResizingDragStart(TileWidths(base = 0, min = 0, max = 10)) {}
        assertThat(underTest.resizingState).isNotNull()

        underTest.onResizingDragEnd()
        assertThat(underTest.resizingState).isNull()
    }

    @Test
    fun unselect_clearsResizingState() {
        // Resizing starts with a selected tile
        underTest.select(TEST_SPEC)
        underTest.onResizingDragStart(TileWidths(base = 0, min = 0, max = 10)) {}
        assertThat(underTest.resizingState).isNotNull()

        underTest.unSelect()
        assertThat(underTest.resizingState).isNull()
    }

    @Test
    fun onResizingDrag_updatesResizingState() {
        // Resizing starts with a selected tile
        underTest.select(TEST_SPEC)
        underTest.onResizingDragStart(TileWidths(base = 0, min = 0, max = 10)) {}
        assertThat(underTest.resizingState).isNotNull()

        underTest.onResizingDrag(5f)
        assertThat(underTest.resizingState?.width).isEqualTo(5)

        underTest.onResizingDrag(2f)
        assertThat(underTest.resizingState?.width).isEqualTo(7)

        underTest.onResizingDrag(-6f)
        assertThat(underTest.resizingState?.width).isEqualTo(1)
    }

    @Test
    fun onResizingDrag_receivesResizeCallback() {
        var resized = false
        val onResize: () -> Unit = { resized = !resized }

        // Resizing starts with a selected tile
        underTest.select(TEST_SPEC)
        underTest.onResizingDragStart(TileWidths(base = 0, min = 0, max = 10), onResize)
        assertThat(underTest.resizingState).isNotNull()

        // Drag under the threshold
        underTest.onResizingDrag(1f)
        assertThat(resized).isFalse()

        // Drag over the threshold
        underTest.onResizingDrag(5f)
        assertThat(resized).isTrue()

        // Drag back under the threshold
        underTest.onResizingDrag(-5f)
        assertThat(resized).isFalse()
    }

    companion object {
        private val TEST_SPEC = TileSpec.create("testSpec")
    }
}
