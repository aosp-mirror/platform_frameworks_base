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
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.kosmos.testScope
import com.android.systemui.qs.FakeQSTile
import com.android.systemui.qs.pipeline.data.repository.tileSpecRepository
import com.android.systemui.qs.pipeline.domain.interactor.currentTilesInteractor
import com.android.systemui.qs.pipeline.shared.TileSpec
import com.android.systemui.testKosmos
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@SmallTest
class DetailsViewModelTest : SysuiTestCase() {
    private val kosmos = testKosmos()
    private lateinit var underTest: DetailsViewModel
    private val spec = TileSpec.create("internet")
    private val specNoDetails = TileSpec.create("NoDetailsTile")

    @Before
    fun setUp() {
        underTest = kosmos.detailsViewModel
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun changeTileDetailsViewModel() = with(kosmos) {
        testScope.runTest {
            val specs = listOf(
                spec,
                specNoDetails,
            )
            tileSpecRepository.setTiles(0, specs)
            runCurrent()

            val tiles = currentTilesInteractor.currentTiles.value

            assertThat(currentTilesInteractor.currentTilesSpecs.size).isEqualTo(2)
            assertThat(tiles!![1].spec).isEqualTo(specNoDetails)
            (tiles!![1].tile as FakeQSTile).hasDetailsViewModel = false

            assertThat(underTest.activeTileDetails).isNull()

            // Click on the tile who has the `spec`.
            assertThat(underTest.onTileClicked(spec)).isTrue()
            assertThat(underTest.activeTileDetails).isNotNull()
            assertThat(underTest.activeTileDetails?.getTitle()).isEqualTo("internet")

            // Click on a tile who dose not have a valid spec.
            assertThat(underTest.onTileClicked(null)).isFalse()
            assertThat(underTest.activeTileDetails).isNull()

            // Click again on the tile who has the `spec`.
            assertThat(underTest.onTileClicked(spec)).isTrue()
            assertThat(underTest.activeTileDetails).isNotNull()
            assertThat(underTest.activeTileDetails?.getTitle()).isEqualTo("internet")

            // Click on a tile who dose not have a detailed view.
            assertThat(underTest.onTileClicked(specNoDetails)).isFalse()
            assertThat(underTest.activeTileDetails).isNull()

            underTest.closeDetailedView()
            assertThat(underTest.activeTileDetails).isNull()

            assertThat(underTest.onTileClicked(null)).isFalse()
        }
    }
}
