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
class EditTileListStateTest : SysuiTestCase() {
    val underTest = EditTileListState(TestEditTiles)

    @Test
    fun movingNonExistentTile_tileAdded() {
        val newTile = createEditTile("other_tile", false)
        underTest.move(newTile, TestEditTiles[0].tile.tileSpec)

        assertThat(underTest.tiles[0]).isEqualTo(newTile)
        assertThat(underTest.tiles.subList(1, underTest.tiles.size))
            .containsExactly(*TestEditTiles.toTypedArray())
    }

    @Test
    fun movingTileToNonExistentTarget_listUnchanged() {
        underTest.move(TestEditTiles[0], TileSpec.create("other_tile"))

        assertThat(underTest.tiles).containsExactly(*TestEditTiles.toTypedArray())
    }

    @Test
    fun movingTileToItself_listUnchanged() {
        underTest.move(TestEditTiles[0], TestEditTiles[0].tile.tileSpec)

        assertThat(underTest.tiles).containsExactly(*TestEditTiles.toTypedArray())
    }

    @Test
    fun movingTileToSameSection_listUpdates() {
        // Move tile at index 0 to index 1. Tile 0 should remain current.
        underTest.move(TestEditTiles[0], TestEditTiles[1].tile.tileSpec)

        // Assert the tiles 0 and 1 have changed places.
        assertThat(underTest.tiles[0]).isEqualTo(TestEditTiles[1])
        assertThat(underTest.tiles[1]).isEqualTo(TestEditTiles[0])

        // Assert the rest of the list is unchanged
        assertThat(underTest.tiles.subList(2, 5))
            .containsExactly(*TestEditTiles.subList(2, 5).toTypedArray())
    }

    fun removingTile_listUpdates() {
        // Remove tile at index 0
        underTest.remove(TestEditTiles[0].tile.tileSpec)

        // Assert the tile was removed
        assertThat(underTest.tiles).containsExactly(*TestEditTiles.subList(1, 6).toTypedArray())
    }

    companion object {
        private fun createEditTile(
            tileSpec: String,
            isCurrent: Boolean
        ): SizedTile<EditTileViewModel> {
            return SizedTileImpl(
                EditTileViewModel(
                    tileSpec = TileSpec.create(tileSpec),
                    icon = Icon.Resource(0, null),
                    label = Text.Loaded("unused"),
                    appName = null,
                    isCurrent = isCurrent,
                    availableEditActions = emptySet(),
                ),
                1,
            )
        }

        private val TestEditTiles =
            listOf(
                createEditTile("tileA", true),
                createEditTile("tileB", true),
                createEditTile("tileC", true),
                createEditTile("tileD", false),
                createEditTile("tileE", false),
                createEditTile("tileF", false),
            )
    }
}
