/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.systemui.qs.tiles.impl.custom.domain.interactor

import android.content.ComponentName
import android.graphics.drawable.Icon
import android.os.UserHandle
import android.service.quicksettings.Tile
import android.text.format.DateUtils
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.collectValues
import com.android.systemui.kosmos.testScope
import com.android.systemui.qs.external.TileServiceKey
import com.android.systemui.qs.pipeline.shared.TileSpec
import com.android.systemui.qs.tiles.impl.custom.TileSubject.Companion.assertThat
import com.android.systemui.qs.tiles.impl.custom.customTileDefaultsRepository
import com.android.systemui.qs.tiles.impl.custom.customTileRepository
import com.android.systemui.qs.tiles.impl.custom.customTileStatePersister
import com.android.systemui.qs.tiles.impl.custom.data.entity.CustomTileDefaults
import com.android.systemui.qs.tiles.impl.custom.tileSpec
import com.android.systemui.testKosmos
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalCoroutinesApi::class)
class CustomTileInteractorTest : SysuiTestCase() {

    private val kosmos = testKosmos().apply { tileSpec = TileSpec.create(TEST_COMPONENT) }

    private val underTest: CustomTileInteractor =
        with(kosmos) {
            CustomTileInteractor(
                tileSpec = tileSpec,
                defaultsRepository = customTileDefaultsRepository,
                customTileRepository = customTileRepository,
                tileScope = testScope.backgroundScope,
                backgroundContext = testScope.testScheduler,
            )
        }

    @Test
    fun activeTileIsAvailableAfterRestored() =
        with(kosmos) {
            testScope.runTest {
                customTileRepository.setTileActive(true)
                customTileStatePersister.persistState(
                    TileServiceKey(TEST_COMPONENT, TEST_USER_1.identifier),
                    TEST_TILE_1,
                )

                underTest.initForUser(TEST_USER_1)

                assertThat(underTest.getTile(TEST_USER_1)).isEqualTo(TEST_TILE_1)
                assertThat(underTest.getTiles(TEST_USER_1).first()).isEqualTo(TEST_TILE_1)
            }
        }

    @Test
    fun notActiveTileIsAvailableAfterUpdated() =
        with(kosmos) {
            testScope.runTest {
                customTileRepository.setTileActive(false)
                customTileStatePersister.persistState(
                    TileServiceKey(TEST_COMPONENT, TEST_USER_1.identifier),
                    TEST_TILE_1,
                )
                val tiles = collectValues(underTest.getTiles(TEST_USER_1))
                val initJob = launch { underTest.initForUser(TEST_USER_1) }

                underTest.updateTile(TEST_TILE_1)
                runCurrent()
                initJob.join()

                assertThat(tiles()).hasSize(1)
                assertThat(tiles().last()).isEqualTo(TEST_TILE_1)
            }
        }

    @Test
    fun notActiveTileIsAvailableAfterDefaultsUpdated() =
        with(kosmos) {
            testScope.runTest {
                customTileRepository.setTileActive(false)
                customTileStatePersister.persistState(
                    TileServiceKey(TEST_COMPONENT, TEST_USER_1.identifier),
                    TEST_TILE_1,
                )
                val tiles = collectValues(underTest.getTiles(TEST_USER_1))
                val initJob = launch { underTest.initForUser(TEST_USER_1) }

                customTileDefaultsRepository.putDefaults(TEST_USER_1, TEST_COMPONENT, TEST_DEFAULTS)
                customTileDefaultsRepository.requestNewDefaults(TEST_USER_1, TEST_COMPONENT)
                runCurrent()
                initJob.join()

                assertThat(tiles()).hasSize(1)
                assertThat(tiles().last()).isEqualTo(TEST_TILE_1)
            }
        }

    @Test(expected = IllegalStateException::class)
    fun getTileBeforeInitThrows() =
        with(kosmos) { testScope.runTest { underTest.getTile(TEST_USER_1) } }

    @Test
    fun initSuspendsForActiveTileNotRestoredAndNotUpdated() =
        with(kosmos) {
            testScope.runTest {
                customTileRepository.setTileActive(true)
                val tiles = collectValues(underTest.getTiles(TEST_USER_1))

                val initJob = backgroundScope.launch { underTest.initForUser(TEST_USER_1) }
                advanceTimeBy(1 * DateUtils.DAY_IN_MILLIS)

                // Is still suspended
                assertThat(initJob.isActive).isTrue()
                assertThat(tiles()).isEmpty()
            }
        }

    @Test
    fun initSuspendedForNotActiveTileWithoutUpdates() =
        with(kosmos) {
            testScope.runTest {
                customTileRepository.setTileActive(false)
                customTileStatePersister.persistState(
                    TileServiceKey(TEST_COMPONENT, TEST_USER_1.identifier),
                    TEST_TILE_1,
                )
                val tiles = collectValues(underTest.getTiles(TEST_USER_1))

                val initJob = backgroundScope.launch { underTest.initForUser(TEST_USER_1) }
                advanceTimeBy(1 * DateUtils.DAY_IN_MILLIS)

                // Is still suspended
                assertThat(initJob.isActive).isTrue()
                assertThat(tiles()).isEmpty()
            }
        }

    @Test
    fun toggleableFollowsTheRepository() {
        with(kosmos) {
            testScope.runTest {
                customTileRepository.setTileToggleable(false)
                assertThat(underTest.isTileToggleable()).isFalse()

                customTileRepository.setTileToggleable(true)
                assertThat(underTest.isTileToggleable()).isTrue()
            }
        }
    }

    @Test
    fun activeFollowsTheRepository() {
        with(kosmos) {
            testScope.runTest {
                customTileRepository.setTileActive(false)
                assertThat(underTest.isTileActive()).isFalse()

                customTileRepository.setTileActive(true)
                assertThat(underTest.isTileActive()).isTrue()
            }
        }
    }

    @Test
    fun initForTheSameUserProcessedOnce() =
        with(kosmos) {
            testScope.runTest {
                customTileRepository.setTileActive(false)
                customTileStatePersister.persistState(
                    TileServiceKey(TEST_COMPONENT, TEST_USER_1.identifier),
                    TEST_TILE_1,
                )
                val tiles = collectValues(underTest.getTiles(TEST_USER_1))
                val initJob = launch {
                    underTest.initForUser(TEST_USER_1)
                    underTest.initForUser(TEST_USER_1)
                }

                underTest.updateTile(TEST_TILE_1)
                runCurrent()
                initJob.join()

                assertThat(tiles()).hasSize(1)
                assertThat(tiles().last()).isEqualTo(TEST_TILE_1)
            }
        }

    @Test
    fun initForDifferentUsersProcessedOnce() =
        with(kosmos) {
            testScope.runTest {
                customTileRepository.setTileActive(true)
                customTileStatePersister.persistState(
                    TileServiceKey(TEST_COMPONENT, TEST_USER_1.identifier),
                    TEST_TILE_1,
                )
                customTileStatePersister.persistState(
                    TileServiceKey(TEST_COMPONENT, TEST_USER_2.identifier),
                    TEST_TILE_2,
                )
                val tiles1 by collectValues(underTest.getTiles(TEST_USER_1))
                val tiles2 by collectValues(underTest.getTiles(TEST_USER_2))

                val initJob = launch {
                    underTest.initForUser(TEST_USER_1)
                    underTest.initForUser(TEST_USER_2)
                }
                runCurrent()
                initJob.join()

                assertThat(tiles1).isEmpty()
                assertThat(tiles2).hasSize(1)
                assertThat(tiles2.last()).isEqualTo(TEST_TILE_2)
            }
        }

    private companion object {

        val TEST_COMPONENT = ComponentName("test.pkg", "test.cls")
        val TEST_USER_1 = UserHandle.of(1)!!
        val TEST_USER_2 = UserHandle.of(2)!!
        val TEST_TILE_1 by lazy {
            Tile().apply {
                label = "test_tile_1"
                icon = Icon.createWithContentUri("file://test_1")
            }
        }
        val TEST_TILE_2 by lazy {
            Tile().apply {
                label = "test_tile_2"
                icon = Icon.createWithContentUri("file://test_2")
            }
        }
        val TEST_DEFAULTS by lazy { CustomTileDefaults.Result(TEST_TILE_1.icon, TEST_TILE_1.label) }
    }
}
