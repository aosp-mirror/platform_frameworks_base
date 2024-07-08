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
import com.android.systemui.kosmos.testScope
import com.android.systemui.qs.panels.data.repository.IconTilesRepository
import com.android.systemui.qs.panels.data.repository.iconTilesRepository
import com.android.systemui.qs.panels.ui.viewmodel.MockTileViewModel
import com.android.systemui.qs.panels.ui.viewmodel.iconTilesViewModel
import com.android.systemui.qs.panels.ui.viewmodel.partitionedGridViewModel
import com.android.systemui.qs.pipeline.shared.TileSpec
import com.android.systemui.testKosmos
import com.google.common.truth.Truth
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
class PartitionedGridLayoutTest : SysuiTestCase() {
    private val kosmos =
        testKosmos().apply {
            iconTilesRepository =
                object : IconTilesRepository {
                    override fun isIconTile(spec: TileSpec): Boolean {
                        return spec.spec.startsWith("small")
                    }
                }
        }

    private val underTest = with(kosmos) { PartitionedGridLayout(partitionedGridViewModel) }

    @Test
    fun correctPagination_underOnePage_partitioned_sameRelativeOrder() =
        with(kosmos) {
            testScope.runTest {
                val rows = 3
                val columns = 4

                val tiles =
                    listOf(
                        largeTile(),
                        smallTile(),
                        smallTile(),
                        largeTile(),
                        largeTile(),
                        smallTile()
                    )
                val (smallTiles, largeTiles) =
                    tiles.partition { iconTilesViewModel.isIconTile(it.spec) }

                // [L L] [L L]
                // [L L]
                // [S] [S] [S]

                val pages = underTest.splitIntoPages(tiles, rows = rows, columns = columns)

                Truth.assertThat(pages).hasSize(1)
                Truth.assertThat(pages[0]).isEqualTo(largeTiles + smallTiles)
            }
        }

    @Test
    fun correctPagination_twoPages_partitioned_sameRelativeOrder() =
        with(kosmos) {
            testScope.runTest {
                val rows = 3
                val columns = 4

                val tiles =
                    listOf(
                        largeTile(),
                        smallTile(),
                        smallTile(),
                        largeTile(),
                        smallTile(),
                        smallTile(),
                        largeTile(),
                        smallTile(),
                        smallTile(),
                    )
                // --- Page 1 ---
                // [L L] [L L]
                // [L L]
                // [S] [S] [S] [S]
                // --- Page 2 ---
                // [S] [S]

                val (smallTiles, largeTiles) =
                    tiles.partition { iconTilesViewModel.isIconTile(it.spec) }

                val pages = underTest.splitIntoPages(tiles, rows = rows, columns = columns)

                val expectedPage0 = largeTiles + smallTiles.take(4)
                val expectedPage1 = smallTiles.drop(4)

                Truth.assertThat(pages).hasSize(2)
                Truth.assertThat(pages[0]).isEqualTo(expectedPage0)
                Truth.assertThat(pages[1]).isEqualTo(expectedPage1)
            }
        }

    companion object {
        fun largeTile() = MockTileViewModel(TileSpec.create("large"))

        fun smallTile() = MockTileViewModel(TileSpec.create("small"))
    }
}
