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
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.kosmos.testScope
import com.android.systemui.lifecycle.activateIn
import com.android.systemui.qs.panels.data.repository.DefaultLargeTilesRepository
import com.android.systemui.qs.panels.data.repository.defaultLargeTilesRepository
import com.android.systemui.qs.panels.data.repository.qsPreferencesRepository
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

@SmallTest
@RunWith(AndroidJUnit4::class)
class DynamicIconTilesInteractorTest : SysuiTestCase() {
    private val kosmos =
        testKosmos().apply {
            defaultLargeTilesRepository =
                object : DefaultLargeTilesRepository {
                    override val defaultLargeTiles: Set<TileSpec> = setOf(largeTile)
                }
            currentTilesInteractor.setTiles(listOf(largeTile, smallTile))
        }
    private lateinit var underTest: DynamicIconTilesInteractor

    @Before
    fun setUp() {
        with(kosmos) {
            underTest = dynamicIconTilesInteractorFactory.create()
            underTest.activateIn(testScope)
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun removingTile_updatesSharedPreferences() =
        with(kosmos) {
            testScope.runTest {
                val latest by collectLastValue(qsPreferencesRepository.largeTilesSpecs)
                runCurrent()

                // Remove the large tile from the current tiles
                currentTilesInteractor.removeTiles(listOf(largeTile))
                runCurrent()

                // Assert that it resized to small
                assertThat(latest).doesNotContain(largeTile)
            }
        }

    private companion object {
        private val largeTile = TileSpec.create("large")
        private val smallTile = TileSpec.create("small")
    }
}
