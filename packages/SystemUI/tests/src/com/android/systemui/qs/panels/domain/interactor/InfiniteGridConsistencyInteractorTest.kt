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

package com.android.systemui.qs.panels.domain.interactor

import android.testing.AndroidTestingRunner
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.kosmos.testScope
import com.android.systemui.qs.panels.data.repository.IconTilesRepository
import com.android.systemui.qs.panels.data.repository.iconTilesRepository
import com.android.systemui.qs.pipeline.shared.TileSpec
import com.android.systemui.testKosmos
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidTestingRunner::class)
class InfiniteGridConsistencyInteractorTest : SysuiTestCase() {

    private val iconOnlyTiles =
        MutableStateFlow(
            setOf(
                TileSpec.create("smallA"),
                TileSpec.create("smallB"),
                TileSpec.create("smallC"),
                TileSpec.create("smallD"),
                TileSpec.create("smallE"),
            )
        )
    private val kosmos =
        testKosmos().apply {
            iconTilesRepository =
                object : IconTilesRepository {
                    override val iconTilesSpecs: StateFlow<Set<TileSpec>>
                        get() = iconOnlyTiles.asStateFlow()
                }
        }
    private val underTest = with(kosmos) { infiniteGridConsistencyInteractor }

    @Test
    fun validTiles_returnsUnchangedList() =
        with(kosmos) {
            testScope.runTest {
                // Original grid
                // [ Large A ] [ sa ][ sb ]
                // [ Large B ] [ Large C ]
                // [ Large D ]
                val tiles =
                    listOf(
                        TileSpec.create("largeA"),
                        TileSpec.create("smallA"),
                        TileSpec.create("smallB"),
                        TileSpec.create("largeB"),
                        TileSpec.create("largeC"),
                        TileSpec.create("largeD"),
                    )

                val newTiles = underTest.reconcileTiles(tiles)

                assertThat(newTiles).isEqualTo(tiles)
            }
        }

    @Test
    fun invalidTiles_moveIconTileForward() =
        with(kosmos) {
            testScope.runTest {
                // Original grid
                // [ Large A ] [ sa ]
                // [ Large B ] [ Large C ]
                // [ sb ] [ Large D ]
                val tiles =
                    listOf(
                        TileSpec.create("largeA"),
                        TileSpec.create("smallA"),
                        TileSpec.create("largeB"),
                        TileSpec.create("largeC"),
                        TileSpec.create("smallB"),
                        TileSpec.create("largeD"),
                    )
                // Expected grid
                // [ Large A ] [ sa ][ sb ]
                // [ Large B ] [ Large C ]
                // [ Large D ]
                val expectedTiles =
                    listOf(
                        TileSpec.create("largeA"),
                        TileSpec.create("smallA"),
                        TileSpec.create("smallB"),
                        TileSpec.create("largeB"),
                        TileSpec.create("largeC"),
                        TileSpec.create("largeD"),
                    )

                val newTiles = underTest.reconcileTiles(tiles)

                assertThat(newTiles).isEqualTo(expectedTiles)
            }
        }

    @Test
    fun invalidTiles_moveIconTileBack() =
        with(kosmos) {
            testScope.runTest {
                // Original grid
                // [ sa ] [ Large A ]
                // [ Large B ] [ Large C ]
                // [ Large D ]
                val tiles =
                    listOf(
                        TileSpec.create("smallA"),
                        TileSpec.create("largeA"),
                        TileSpec.create("largeB"),
                        TileSpec.create("largeC"),
                        TileSpec.create("largeD"),
                    )
                // Expected grid
                // [ Large A ] [ Large B ]
                // [ Large C ] [ Large D ]
                // [ sa ]
                val expectedTiles =
                    listOf(
                        TileSpec.create("largeA"),
                        TileSpec.create("largeB"),
                        TileSpec.create("largeC"),
                        TileSpec.create("largeD"),
                        TileSpec.create("smallA"),
                    )

                val newTiles = underTest.reconcileTiles(tiles)

                assertThat(newTiles).isEqualTo(expectedTiles)
            }
        }

    @Test
    fun invalidTiles_multipleCorrections() =
        with(kosmos) {
            testScope.runTest {
                // Original grid
                // [ sa ] [ Large A ]
                // [ Large B ] [ sb ] [ sc ]
                // [ sd ] [ se ] [ Large C ]
                val tiles =
                    listOf(
                        TileSpec.create("smallA"),
                        TileSpec.create("largeA"),
                        TileSpec.create("largeB"),
                        TileSpec.create("smallB"),
                        TileSpec.create("smallC"),
                        TileSpec.create("smallD"),
                        TileSpec.create("smallE"),
                        TileSpec.create("largeC"),
                    )
                // Expected grid
                // [ sa ] [ Large A ] [ sb ]
                // [ Large B ] [ sc ] [ sd ]
                // [ se ] [ Large C ]
                val expectedTiles =
                    listOf(
                        TileSpec.create("smallA"),
                        TileSpec.create("largeA"),
                        TileSpec.create("smallB"),
                        TileSpec.create("largeB"),
                        TileSpec.create("smallC"),
                        TileSpec.create("smallD"),
                        TileSpec.create("smallE"),
                        TileSpec.create("largeC"),
                    )

                val newTiles = underTest.reconcileTiles(tiles)

                assertThat(newTiles).isEqualTo(expectedTiles)
            }
        }
}
