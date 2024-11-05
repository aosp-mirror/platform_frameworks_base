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

import androidx.compose.ui.text.AnnotatedString
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.common.shared.model.Icon
import com.android.systemui.qs.panels.shared.model.SizedTile
import com.android.systemui.qs.panels.shared.model.SizedTileImpl
import com.android.systemui.qs.panels.ui.model.GridCell
import com.android.systemui.qs.panels.ui.model.TileGridCell
import com.android.systemui.qs.panels.ui.viewmodel.EditTileViewModel
import com.android.systemui.qs.pipeline.shared.TileSpec
import com.android.systemui.qs.shared.model.TileCategory
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
class EditTileListStateTest : SysuiTestCase() {
    private val underTest = EditTileListState(TestEditTiles, 4)

    @Test
    fun startDrag_listHasSpacers() {
        underTest.onStarted(TestEditTiles[0])

        // [ a ] [ b ] [ c ] [ X ]
        // [ Large D ] [ e ] [ X ]
        assertThat(underTest.tiles.toStrings())
            .isEqualTo(listOf("a", "b", "c", "spacer", "d", "e", "spacer"))
        assertThat(underTest.isMoving(TestEditTiles[0].tile.tileSpec)).isTrue()
        assertThat(underTest.dragInProgress).isTrue()
    }

    @Test
    fun moveDrag_listChanges() {
        underTest.onStarted(TestEditTiles[4])
        underTest.onMoved(3, false)

        // Tile E goes to index 3
        // [ a ] [ b ] [ c ] [ e ]
        // [ Large D ] [ X ] [ X ]
        assertThat(underTest.tiles.toStrings())
            .isEqualTo(listOf("a", "b", "c", "e", "d", "spacer", "spacer"))
    }

    @Test
    fun moveDragOnSidesOfLargeTile_listChanges() {
        val draggedCell = TestEditTiles[4]

        underTest.onStarted(draggedCell)
        underTest.onMoved(4, true)

        // Tile E goes to the right side of tile D, list is unchanged
        // [ a ] [ b ] [ c ] [ X ]
        // [ Large D ] [ e ] [ X ]
        assertThat(underTest.tiles.toStrings())
            .isEqualTo(listOf("a", "b", "c", "spacer", "d", "e", "spacer"))

        underTest.onMoved(4, false)

        // Tile E goes to the left side of tile D, they swap positions
        // [ a ] [ b ] [ c ] [ e ]
        // [ Large D ] [ X ] [ X ]
        assertThat(underTest.tiles.toStrings())
            .isEqualTo(listOf("a", "b", "c", "e", "d", "spacer", "spacer"))
    }

    @Test
    fun moveNewTile_tileIsAdded() {
        val newTile = createEditTile("newTile", 2)

        underTest.onStarted(newTile)
        underTest.onMoved(5, false)

        // New tile goes to index 5
        // [ a ] [ b ] [ c ] [ X ]
        // [ Large D ] [ newTile ]
        // [ e ] [ X ] [ X ] [ X ]
        assertThat(underTest.tiles.toStrings())
            .isEqualTo(
                listOf("a", "b", "c", "spacer", "d", "newTile", "e", "spacer", "spacer", "spacer")
            )
    }

    @Test
    fun movedTileOutOfBounds_tileDisappears() {
        underTest.onStarted(TestEditTiles[0])
        underTest.movedOutOfBounds()

        assertThat(underTest.tiles.toStrings()).doesNotContain(TestEditTiles[0].tile.tileSpec.spec)
    }

    private fun List<GridCell>.toStrings(): List<String> {
        return map {
            if (it is TileGridCell) {
                it.tile.tileSpec.spec
            } else {
                "spacer"
            }
        }
    }

    companion object {
        private fun createEditTile(tileSpec: String, width: Int): SizedTile<EditTileViewModel> {
            return SizedTileImpl(
                EditTileViewModel(
                    tileSpec = TileSpec.create(tileSpec),
                    icon = Icon.Resource(0, null),
                    label = AnnotatedString("unused"),
                    appName = null,
                    isCurrent = true,
                    availableEditActions = emptySet(),
                    category = TileCategory.UNKNOWN,
                ),
                width,
            )
        }

        // [ a ] [ b ] [ c ]
        // [ Large D ] [ e ] [ f ]
        private val TestEditTiles =
            listOf(
                createEditTile("a", 1),
                createEditTile("b", 1),
                createEditTile("c", 1),
                createEditTile("d", 2),
                createEditTile("e", 1),
            )
    }
}
