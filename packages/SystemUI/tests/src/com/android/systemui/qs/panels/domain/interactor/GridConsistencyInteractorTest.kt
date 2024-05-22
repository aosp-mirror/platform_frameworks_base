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
import com.android.systemui.qs.panels.data.repository.gridLayoutTypeRepository
import com.android.systemui.qs.panels.data.repository.iconTilesRepository
import com.android.systemui.qs.panels.shared.model.InfiniteGridLayoutType
import com.android.systemui.qs.panels.shared.model.PartitionedGridLayoutType
import com.android.systemui.qs.pipeline.data.repository.tileSpecRepository
import com.android.systemui.qs.pipeline.domain.interactor.currentTilesInteractor
import com.android.systemui.qs.pipeline.shared.TileSpec
import com.android.systemui.testKosmos
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
@RunWith(AndroidTestingRunner::class)
class GridConsistencyInteractorTest : SysuiTestCase() {

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

    private val underTest = with(kosmos) { gridConsistencyInteractor }

    @Before
    fun setUp() {
        // Mostly testing InfiniteGridConsistencyInteractor because it reorders tiles
        with(kosmos) { gridLayoutTypeRepository.setLayout(InfiniteGridLayoutType) }
        underTest.start()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun changeLayoutType_usesCorrectGridConsistencyInteractor() =
        with(kosmos) {
            testScope.runTest {
                // Using the no-op grid consistency interactor
                gridLayoutTypeRepository.setLayout(PartitionedGridLayoutType)

                // Setting an invalid layout with holes
                // [ Large A ] [ sa ]
                // [ Large B ] [ Large C ]
                // [ sb ] [ Large D ]
                val newTiles =
                    listOf(
                        TileSpec.create("largeA"),
                        TileSpec.create("smallA"),
                        TileSpec.create("largeB"),
                        TileSpec.create("largeC"),
                        TileSpec.create("smallB"),
                        TileSpec.create("largeD"),
                    )
                tileSpecRepository.setTiles(0, newTiles)

                runCurrent()

                val tiles = currentTilesInteractor.currentTiles.value
                val tileSpecs = tiles.map { it.spec }

                // Saved tiles should be unchanged
                assertThat(tileSpecs).isEqualTo(newTiles)
            }
        }

    @Test
    fun validTilesWithInfiniteGridConsistencyInteractor_unchangedList() =
        with(kosmos) {
            testScope.runTest {
                // Setting a valid layout with holes
                // [ Large A ] [ sa ][ sb ]
                // [ Large B ] [ Large C ]
                // [ Large D ]
                val newTiles =
                    listOf(
                        TileSpec.create("largeA"),
                        TileSpec.create("smallA"),
                        TileSpec.create("smallB"),
                        TileSpec.create("largeB"),
                        TileSpec.create("largeC"),
                        TileSpec.create("largeD"),
                    )
                tileSpecRepository.setTiles(0, newTiles)

                runCurrent()

                val tiles = currentTilesInteractor.currentTiles.value
                val tileSpecs = tiles.map { it.spec }

                // Saved tiles should be unchanged
                assertThat(tileSpecs).isEqualTo(newTiles)
            }
        }

    @Test
    fun invalidTilesWithInfiniteGridConsistencyInteractor_savesNewList() =
        with(kosmos) {
            testScope.runTest {
                // Setting an invalid layout with holes
                // [ sa ] [ Large A ]
                // [ Large B ] [ sb ] [ sc ]
                // [ sd ] [ se ] [ Large C ]
                val newTiles =
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
                tileSpecRepository.setTiles(0, newTiles)

                runCurrent()

                val tiles = currentTilesInteractor.currentTiles.value
                val tileSpecs = tiles.map { it.spec }

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

                // Saved tiles should be unchanged
                assertThat(tileSpecs).isEqualTo(expectedTiles)
            }
        }
}
