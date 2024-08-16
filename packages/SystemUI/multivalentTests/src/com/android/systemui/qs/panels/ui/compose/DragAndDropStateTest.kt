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

package com.android.systemui.qs.panels.ui.compose

import androidx.compose.runtime.mutableStateOf
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.common.shared.model.Icon
import com.android.systemui.common.shared.model.Text
import com.android.systemui.qs.panels.shared.model.SizedTile
import com.android.systemui.qs.panels.shared.model.SizedTileImpl
import com.android.systemui.qs.panels.ui.viewmodel.EditTileViewModel
import com.android.systemui.qs.pipeline.shared.TileSpec
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
class DragAndDropStateTest : SysuiTestCase() {
    private val listState = EditTileListState(TestEditTiles)
    private val underTest = DragAndDropState(mutableStateOf(null), listState)

    @Test
    fun isMoving_returnsCorrectValue() {
        // Asserts no tiles is moving
        TestEditTiles.forEach { assertThat(underTest.isMoving(it.tile.tileSpec)).isFalse() }

        // Start the drag movement
        underTest.onStarted(TestEditTiles[0])

        // Assert that the correct tile is marked as moving
        TestEditTiles.forEach {
            assertThat(underTest.isMoving(it.tile.tileSpec))
                .isEqualTo(TestEditTiles[0].tile.tileSpec == it.tile.tileSpec)
        }
    }

    @Test
    fun onMoved_updatesList() {
        // Start the drag movement
        underTest.onStarted(TestEditTiles[0])

        // Move the tile to the end of the list
        underTest.onMoved(listState.tiles[5].tile.tileSpec)
        assertThat(underTest.currentPosition()).isEqualTo(5)

        // Move the tile to the middle of the list
        underTest.onMoved(listState.tiles[2].tile.tileSpec)
        assertThat(underTest.currentPosition()).isEqualTo(2)
    }

    @Test
    fun onDrop_resetsMovingTile() {
        // Start the drag movement
        underTest.onStarted(TestEditTiles[0])

        // Move the tile to the end of the list
        underTest.onMoved(listState.tiles[5].tile.tileSpec)

        // Drop the tile
        underTest.onDrop()

        // Asserts no tiles is moving
        TestEditTiles.forEach { assertThat(underTest.isMoving(it.tile.tileSpec)).isFalse() }
    }

    @Test
    fun onMoveOutOfBounds_removeMovingTileFromCurrentList() {
        // Start the drag movement
        underTest.onStarted(TestEditTiles[0])

        // Move the tile outside of the list
        underTest.movedOutOfBounds()

        // Asserts the moving tile is not current
        assertThat(
                listState.tiles.firstOrNull { it.tile.tileSpec == TestEditTiles[0].tile.tileSpec }
            )
            .isNull()
    }

    companion object {
        private fun createEditTile(tileSpec: String): SizedTile<EditTileViewModel> {
            return SizedTileImpl(
                EditTileViewModel(
                    tileSpec = TileSpec.create(tileSpec),
                    icon = Icon.Resource(0, null),
                    label = Text.Loaded("unused"),
                    appName = null,
                    isCurrent = true,
                    availableEditActions = emptySet(),
                ),
                1,
            )
        }

        private val TestEditTiles =
            listOf(
                createEditTile("tileA"),
                createEditTile("tileB"),
                createEditTile("tileC"),
                createEditTile("tileD"),
                createEditTile("tileE"),
                createEditTile("tileF"),
            )
    }
}
