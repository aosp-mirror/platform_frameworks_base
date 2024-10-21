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

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.kosmos.testScope
import com.android.systemui.qs.panels.data.repository.DefaultLargeTilesRepository
import com.android.systemui.qs.panels.data.repository.defaultLargeTilesRepository
import com.android.systemui.qs.pipeline.data.repository.FakeDefaultTilesRepository
import com.android.systemui.qs.pipeline.data.repository.fakeDefaultTilesRepository
import com.android.systemui.qs.pipeline.domain.interactor.currentTilesInteractor
import com.android.systemui.qs.pipeline.shared.TileSpec
import com.android.systemui.testKosmos
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
class SizedTilesResetInteractorTest : SysuiTestCase() {
    private val kosmos =
        testKosmos().apply {
            defaultLargeTilesRepository =
                object : DefaultLargeTilesRepository {
                    override val defaultLargeTiles: Set<TileSpec> = setOf(largeTile)
                }
            fakeDefaultTilesRepository = FakeDefaultTilesRepository(listOf(smallTile, largeTile))
        }
    private val underTest = with(kosmos) { sizedTilesResetInteractor }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun changeTiles_resetsCorrectly() {
        with(kosmos) {
            testScope.runTest {
                // Change current tiles and large tiles
                currentTilesInteractor.setTiles(listOf(largeTile, newTile))
                iconTilesInteractor.setLargeTiles(setOf(newTile))
                runCurrent()

                // Assert both current tiles and large tiles changed
                assertThat(currentTilesInteractor.currentTilesSpecs)
                    .containsExactly(largeTile, newTile)
                assertThat(iconTilesInteractor.largeTilesSpecs.value).containsExactly(newTile)

                // Reset to default
                underTest.reset()
                runCurrent()

                // Assert both current tiles and large tiles are back to the initial state
                assertThat(currentTilesInteractor.currentTilesSpecs)
                    .containsExactly(largeTile, smallTile)
                assertThat(iconTilesInteractor.largeTilesSpecs.value).containsExactly(largeTile)
            }
        }
    }

    private companion object {
        private val largeTile = TileSpec.create("large")
        private val smallTile = TileSpec.create("small")
        private val newTile = TileSpec.create("newTile")
    }
}
