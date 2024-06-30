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
    fun movingNonExistentTile_listUnchanged() {
        underTest.move(TileSpec.create("other_tile"), TestEditTiles[0].tileSpec)

        assertThat(underTest.tiles).containsExactly(*TestEditTiles.toTypedArray())
    }

    @Test
    fun movingTileToNonExistentTarget_listUnchanged() {
        underTest.move(TestEditTiles[0].tileSpec, TileSpec.create("other_tile"))

        assertThat(underTest.tiles).containsExactly(*TestEditTiles.toTypedArray())
    }

    @Test
    fun movingTileToItself_listUnchanged() {
        underTest.move(TestEditTiles[0].tileSpec, TestEditTiles[0].tileSpec)

        assertThat(underTest.tiles).containsExactly(*TestEditTiles.toTypedArray())
    }

    @Test
    fun movingTileToSameSection_listUpdates() {
        // Move tile at index 0 to index 1. Tile 0 should remain current.
        underTest.move(TestEditTiles[0].tileSpec, TestEditTiles[1].tileSpec)

        // Assert the tiles 0 and 1 have changed places.
        assertThat(underTest.tiles[0]).isEqualTo(TestEditTiles[1])
        assertThat(underTest.tiles[1]).isEqualTo(TestEditTiles[0])

        // Assert the rest of the list is unchanged
        assertThat(underTest.tiles.subList(2, 5))
            .containsExactly(*TestEditTiles.subList(2, 5).toTypedArray())
    }

    @Test
    fun movingTileToDifferentSection_listAndTileUpdates() {
        // Move tile at index 0 to index 3. Tile 0 should no longer be current.
        underTest.move(TestEditTiles[0].tileSpec, TestEditTiles[3].tileSpec)

        // Assert tile 0 is now at index 3 and is no longer current.
        assertThat(underTest.tiles[3]).isEqualTo(TestEditTiles[0].copy(isCurrent = false))

        // Assert previous tiles have shifted places
        assertThat(underTest.tiles[0]).isEqualTo(TestEditTiles[1])
        assertThat(underTest.tiles[1]).isEqualTo(TestEditTiles[2])
        assertThat(underTest.tiles[2]).isEqualTo(TestEditTiles[3])

        // Assert the rest of the list is unchanged
        assertThat(underTest.tiles.subList(4, 5))
            .containsExactly(*TestEditTiles.subList(4, 5).toTypedArray())
    }

    companion object {
        private fun createEditTile(tileSpec: String, isCurrent: Boolean): EditTileViewModel {
            return EditTileViewModel(
                tileSpec = TileSpec.create(tileSpec),
                icon = Icon.Resource(0, null),
                label = Text.Loaded("unused"),
                appName = null,
                isCurrent = isCurrent,
                availableEditActions = emptySet(),
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
