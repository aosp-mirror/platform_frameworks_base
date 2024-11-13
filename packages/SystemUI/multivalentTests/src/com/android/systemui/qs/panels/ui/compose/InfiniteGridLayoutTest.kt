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
import com.android.systemui.qs.panels.data.repository.DefaultLargeTilesRepository
import com.android.systemui.qs.panels.data.repository.defaultLargeTilesRepository
import com.android.systemui.qs.panels.ui.compose.infinitegrid.InfiniteGridLayout
import com.android.systemui.qs.panels.ui.viewmodel.MockTileViewModel
import com.android.systemui.qs.panels.ui.viewmodel.fixedColumnsSizeViewModel
import com.android.systemui.qs.panels.ui.viewmodel.iconTilesViewModel
import com.android.systemui.qs.pipeline.shared.TileSpec
import com.android.systemui.testKosmos
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
class InfiniteGridLayoutTest : SysuiTestCase() {
    private val kosmos =
        testKosmos().apply {
            defaultLargeTilesRepository =
                object : DefaultLargeTilesRepository {
                    override val defaultLargeTiles: Set<TileSpec> = setOf(TileSpec.create("large"))
                }
        }

    private val underTest =
        with(kosmos) { InfiniteGridLayout(iconTilesViewModel, fixedColumnsSizeViewModel) }

    @Test
    fun correctPagination_underOnePage_sameOrder() =
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
                        smallTile(),
                    )

                val pages = underTest.splitIntoPages(tiles, rows = rows, columns = columns)

                assertThat(pages).hasSize(1)
                assertThat(pages[0]).isEqualTo(tiles)
            }
        }

    @Test
    fun correctPagination_twoPages_sameOrder() =
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
                        smallTile(),
                        smallTile(),
                        largeTile(),
                        largeTile(),
                        smallTile(),
                        smallTile(),
                        largeTile(),
                    )
                // --- Page 1 ---
                // [L L] [S] [S]
                // [L L] [L L]
                // [S] [S] [L L]
                // --- Page 2 ---
                // [L L] [S] [S]
                // [L L]

                val pages = underTest.splitIntoPages(tiles, rows = rows, columns = columns)

                assertThat(pages).hasSize(2)
                assertThat(pages[0]).isEqualTo(tiles.take(8))
                assertThat(pages[1]).isEqualTo(tiles.drop(8))
            }
        }

    companion object {
        fun largeTile() = MockTileViewModel(TileSpec.create("large"))

        fun smallTile() = MockTileViewModel(TileSpec.create("small"))
    }
}
