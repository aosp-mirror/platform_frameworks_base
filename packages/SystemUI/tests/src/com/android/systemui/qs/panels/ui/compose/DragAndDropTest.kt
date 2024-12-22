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

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onChildren
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.AnnotatedString
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.FlakyTest
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.common.shared.model.ContentDescription
import com.android.systemui.common.shared.model.Icon
import com.android.systemui.qs.panels.shared.model.SizedTile
import com.android.systemui.qs.panels.shared.model.SizedTileImpl
import com.android.systemui.qs.panels.ui.compose.infinitegrid.DefaultEditTileGrid
import com.android.systemui.qs.panels.ui.viewmodel.EditTileViewModel
import com.android.systemui.qs.pipeline.shared.TileSpec
import com.android.systemui.qs.shared.model.TileCategory
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@FlakyTest(bugId = 360351805)
@SmallTest
@RunWith(AndroidJUnit4::class)
class DragAndDropTest : SysuiTestCase() {
    @get:Rule val composeRule = createComposeRule()

    // TODO(ostonge): Investigate why drag isn't detected when using performTouchInput
    @Composable
    private fun EditTileGridUnderTest(
        listState: EditTileListState,
        onSetTiles: (List<TileSpec>) -> Unit,
    ) {
        DefaultEditTileGrid(
            listState = listState,
            otherTiles = listOf(),
            columns = 4,
            modifier = Modifier.fillMaxSize(),
            onRemoveTile = {},
            onSetTiles = onSetTiles,
            onResize = { _, _ -> },
        )
    }

    @Test
    fun draggedTile_shouldDisappear() {
        var tiles by mutableStateOf(TestEditTiles)
        val listState = EditTileListState(tiles, 4)
        composeRule.setContent {
            EditTileGridUnderTest(listState) {
                tiles = it.map { tileSpec -> createEditTile(tileSpec.spec) }
            }
        }
        composeRule.waitForIdle()

        listState.onStarted(TestEditTiles[0])

        // Tile is being dragged, it should be replaced with a placeholder
        composeRule.onNodeWithContentDescription("tileA").assertDoesNotExist()

        // Available tiles should disappear
        composeRule.onNodeWithTag(AVAILABLE_TILES_GRID_TEST_TAG).assertDoesNotExist()

        // Remove drop zone should appear
        composeRule.onNodeWithText("Remove").assertExists()

        // Every other tile should still be in the same order
        composeRule.assertTileGridContainsExactly(listOf("tileB", "tileC", "tileD_large", "tileE"))
    }

    @Test
    fun draggedTile_shouldChangePosition() {
        var tiles by mutableStateOf(TestEditTiles)
        val listState = EditTileListState(tiles, 4)
        composeRule.setContent {
            EditTileGridUnderTest(listState) {
                tiles = it.map { tileSpec -> createEditTile(tileSpec.spec) }
            }
        }
        composeRule.waitForIdle()

        listState.onStarted(TestEditTiles[0])
        listState.onMoved(1, false)
        listState.onDrop()

        // Available tiles should re-appear
        composeRule.onNodeWithTag(AVAILABLE_TILES_GRID_TEST_TAG).assertExists()

        // Remove drop zone should disappear
        composeRule.onNodeWithText("Remove").assertDoesNotExist()

        // Tile A and B should swap places
        composeRule.assertTileGridContainsExactly(
            listOf("tileB", "tileA", "tileC", "tileD_large", "tileE")
        )
    }

    @Test
    fun draggedTileOut_shouldBeRemoved() {
        var tiles by mutableStateOf(TestEditTiles)
        val listState = EditTileListState(tiles, 4)
        composeRule.setContent {
            EditTileGridUnderTest(listState) {
                tiles = it.map { tileSpec -> createEditTile(tileSpec.spec) }
            }
        }
        composeRule.waitForIdle()

        listState.onStarted(TestEditTiles[0])
        listState.movedOutOfBounds()
        listState.onDrop()

        // Available tiles should re-appear
        composeRule.onNodeWithTag(AVAILABLE_TILES_GRID_TEST_TAG).assertExists()

        // Remove drop zone should disappear
        composeRule.onNodeWithText("Remove").assertDoesNotExist()

        // Tile A is gone
        composeRule.assertTileGridContainsExactly(listOf("tileB", "tileC", "tileD_large", "tileE"))
    }

    @Test
    fun draggedNewTileIn_shouldBeAdded() {
        var tiles by mutableStateOf(TestEditTiles)
        val listState = EditTileListState(tiles, 4)
        composeRule.setContent {
            EditTileGridUnderTest(listState) {
                tiles = it.map { tileSpec -> createEditTile(tileSpec.spec) }
            }
        }
        composeRule.waitForIdle()

        listState.onStarted(createEditTile("newTile"))
        // Insert after tileD, which is at index 4
        // [ a ] [ b ] [ c ] [ empty ]
        // [ tile d ] [ e ]
        listState.onMoved(4, insertAfter = true)
        listState.onDrop()

        // Available tiles should re-appear
        composeRule.onNodeWithTag(AVAILABLE_TILES_GRID_TEST_TAG).assertExists()

        // Remove drop zone should disappear
        composeRule.onNodeWithText("Remove").assertDoesNotExist()

        // newTile is added after tileD
        composeRule.assertTileGridContainsExactly(
            listOf("tileA", "tileB", "tileC", "tileD_large", "newTile", "tileE")
        )
    }

    private fun ComposeContentTestRule.assertTileGridContainsExactly(specs: List<String>) {
        onNodeWithTag(CURRENT_TILES_GRID_TEST_TAG).onChildren().apply {
            fetchSemanticsNodes().forEachIndexed { index, _ ->
                get(index).assert(hasContentDescription(specs[index]))
            }
        }
    }

    companion object {
        private const val CURRENT_TILES_GRID_TEST_TAG = "CurrentTilesGrid"
        private const val AVAILABLE_TILES_GRID_TEST_TAG = "AvailableTilesGrid"

        private fun createEditTile(tileSpec: String): SizedTile<EditTileViewModel> {
            return SizedTileImpl(
                EditTileViewModel(
                    tileSpec = TileSpec.create(tileSpec),
                    icon =
                        Icon.Resource(
                            android.R.drawable.star_on,
                            ContentDescription.Loaded(tileSpec),
                        ),
                    label = AnnotatedString(tileSpec),
                    appName = null,
                    isCurrent = true,
                    availableEditActions = emptySet(),
                    category = TileCategory.UNKNOWN,
                ),
                getWidth(tileSpec),
            )
        }

        private fun getWidth(tileSpec: String): Int {
            return if (tileSpec.endsWith("large")) {
                2
            } else {
                1
            }
        }

        private val TestEditTiles =
            listOf(
                createEditTile("tileA"),
                createEditTile("tileB"),
                createEditTile("tileC"),
                createEditTile("tileD_large"),
                createEditTile("tileE"),
            )
    }
}
