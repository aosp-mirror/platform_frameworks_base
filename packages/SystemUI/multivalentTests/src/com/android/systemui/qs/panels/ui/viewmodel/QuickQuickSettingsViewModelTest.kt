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

package com.android.systemui.qs.panels.ui.viewmodel

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.common.ui.data.repository.fakeConfigurationRepository
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.kosmos.Kosmos
import com.android.systemui.kosmos.testCase
import com.android.systemui.kosmos.testScope
import com.android.systemui.qs.panels.domain.interactor.qsPreferencesInteractor
import com.android.systemui.qs.pipeline.domain.interactor.currentTilesInteractor
import com.android.systemui.qs.pipeline.shared.TileSpec
import com.android.systemui.res.R
import com.android.systemui.testKosmos
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
class QuickQuickSettingsViewModelTest : SysuiTestCase() {

    private val tiles =
        listOf(
                "$PREFIX_SMALL:1",
                "$PREFIX_SMALL:2",
                "$PREFIX_LARGE:3",
                "$PREFIX_SMALL:4",
                "$PREFIX_LARGE:5",
                "$PREFIX_LARGE:6",
                "$PREFIX_SMALL:7",
                "$PREFIX_SMALL:8",
                "$PREFIX_LARGE:9",
            )
            .map(TileSpec::create)

    private val kosmos =
        testKosmos().apply {
            qsPreferencesInteractor.setLargeTilesSpecs(
                tiles.filter { it.spec.startsWith(PREFIX_LARGE) }.toSet()
            )
        }

    private val underTest = kosmos.quickQuickSettingsViewModel

    @Before
    fun setUp() {
        kosmos.setTiles(tiles)
    }

    @Test
    fun splitIntoRows_onlyFirstTwoRowsOfTiles() =
        with(kosmos) {
            testScope.runTest {
                setRows(2)
                val columns by collectLastValue(underTest.columns)
                val tileViewModels by collectLastValue(underTest.tileViewModels)

                assertThat(columns).isEqualTo(4)
                // All tiles in 4 columns
                // [1] [2] [3 3]
                // [4] [5 5]
                // [6 6] [7] [8]
                // [9 9]

                assertThat(tileViewModels!!.map { it.tile.spec }).isEqualTo(tiles.take(5))
            }
        }

    @Test
    fun changeRows_tilesChange() =
        with(kosmos) {
            testScope.runTest {
                setRows(2)
                val columns by collectLastValue(underTest.columns)
                val tileViewModels by collectLastValue(underTest.tileViewModels)

                assertThat(columns).isEqualTo(4)
                // All tiles in 4 columns
                // [1] [2] [3 3]
                // [4] [5 5]
                // [6 6] [7] [8]
                // [9 9]

                setRows(3)
                assertThat(tileViewModels!!.map { it.tile.spec }).isEqualTo(tiles.take(8))
                setRows(1)
                assertThat(tileViewModels!!.map { it.tile.spec }).isEqualTo(tiles.take(3))
            }
        }

    @Test
    fun changeTiles_tilesChange() =
        with(kosmos) {
            testScope.runTest {
                setRows(2)
                val columns by collectLastValue(underTest.columns)
                val tileViewModels by collectLastValue(underTest.tileViewModels)

                assertThat(columns).isEqualTo(4)
                // All tiles in 4 columns
                // [1] [2] [3 3]
                // [4] [5 5]
                // [6 6] [7] [8]
                // [9 9]

                // Remove tile small:4
                currentTilesInteractor.removeTiles(setOf(tiles[3]))

                assertThat(tileViewModels!!.map { it.tile.spec })
                    .isEqualTo(
                        listOf(
                                "$PREFIX_SMALL:1",
                                "$PREFIX_SMALL:2",
                                "$PREFIX_LARGE:3",
                                "$PREFIX_LARGE:5",
                                "$PREFIX_LARGE:6",
                            )
                            .map(TileSpec::create)
                    )
            }
        }

    private fun Kosmos.setTiles(tiles: List<TileSpec>) {
        currentTilesInteractor.setTiles(tiles)
    }

    private fun Kosmos.setRows(rows: Int) {
        testCase.context.orCreateTestableResources.addOverride(
            R.integer.quick_qs_panel_max_rows,
            rows,
        )
        fakeConfigurationRepository.onConfigurationChange()
    }

    private companion object {
        const val PREFIX_SMALL = "small"
        const val PREFIX_LARGE = "large"
    }
}
