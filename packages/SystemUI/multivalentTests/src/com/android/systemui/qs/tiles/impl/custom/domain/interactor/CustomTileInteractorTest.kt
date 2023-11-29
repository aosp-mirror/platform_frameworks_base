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
import com.android.systemui.qs.external.FakeCustomTileStatePersister
import com.android.systemui.qs.external.TileServiceKey
import com.android.systemui.qs.external.TileServiceManager
import com.android.systemui.qs.pipeline.shared.TileSpec
import com.android.systemui.qs.tiles.impl.custom.TileSubject.Companion.assertThat
import com.android.systemui.qs.tiles.impl.custom.data.entity.CustomTileDefaults
import com.android.systemui.qs.tiles.impl.custom.data.repository.FakeCustomTileDefaultsRepository
import com.android.systemui.qs.tiles.impl.custom.data.repository.FakeCustomTileRepository
import com.android.systemui.util.mockito.whenever
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.MockitoAnnotations

@SmallTest
@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalCoroutinesApi::class)
class CustomTileInteractorTest : SysuiTestCase() {

    @Mock private lateinit var tileServiceManager: TileServiceManager

    private val testScope = TestScope()

    private val defaultsRepository = FakeCustomTileDefaultsRepository()
    private val customTileStatePersister = FakeCustomTileStatePersister()
    private val customTileRepository =
        FakeCustomTileRepository(
            TEST_TILE_SPEC,
            customTileStatePersister,
            testScope.testScheduler,
        )

    private lateinit var underTest: CustomTileInteractor

    @Before
    fun setup() {
        MockitoAnnotations.initMocks(this)

        underTest =
            CustomTileInteractor(
                TEST_USER,
                defaultsRepository,
                customTileRepository,
                tileServiceManager,
                testScope.backgroundScope,
                testScope.testScheduler,
            )
    }

    @Test
    fun activeTileIsAvailableAfterRestored() =
        testScope.runTest {
            whenever(tileServiceManager.isActiveTile).thenReturn(true)
            customTileStatePersister.persistState(
                TileServiceKey(TEST_COMPONENT, TEST_USER.identifier),
                TEST_TILE,
            )

            underTest.init()

            assertThat(underTest.tile).isEqualTo(TEST_TILE)
            assertThat(underTest.tiles.first()).isEqualTo(TEST_TILE)
        }

    @Test
    fun notActiveTileIsAvailableAfterUpdated() =
        testScope.runTest {
            whenever(tileServiceManager.isActiveTile).thenReturn(false)
            customTileStatePersister.persistState(
                TileServiceKey(TEST_COMPONENT, TEST_USER.identifier),
                TEST_TILE,
            )
            val tiles = collectValues(underTest.tiles)
            val initJob = launch { underTest.init() }

            underTest.updateTile(TEST_TILE)
            runCurrent()
            initJob.join()

            assertThat(tiles()).hasSize(1)
            assertThat(tiles().last()).isEqualTo(TEST_TILE)
        }

    @Test
    fun notActiveTileIsAvailableAfterDefaultsUpdated() =
        testScope.runTest {
            whenever(tileServiceManager.isActiveTile).thenReturn(false)
            customTileStatePersister.persistState(
                TileServiceKey(TEST_COMPONENT, TEST_USER.identifier),
                TEST_TILE,
            )
            val tiles = collectValues(underTest.tiles)
            val initJob = launch { underTest.init() }

            defaultsRepository.putDefaults(TEST_USER, TEST_COMPONENT, TEST_DEFAULTS)
            defaultsRepository.requestNewDefaults(TEST_USER, TEST_COMPONENT)
            runCurrent()
            initJob.join()

            assertThat(tiles()).hasSize(1)
            assertThat(tiles().last()).isEqualTo(TEST_TILE)
        }

    @Test(expected = IllegalStateException::class)
    fun getTileBeforeInitThrows() = testScope.runTest { underTest.tile }

    @Test
    fun initSuspendsForActiveTileNotRestoredAndNotUpdated() =
        testScope.runTest {
            whenever(tileServiceManager.isActiveTile).thenReturn(true)
            val tiles = collectValues(underTest.tiles)

            val initJob = backgroundScope.launch { underTest.init() }
            advanceTimeBy(1 * DateUtils.DAY_IN_MILLIS)

            // Is still suspended
            assertThat(initJob.isActive).isTrue()
            assertThat(tiles()).isEmpty()
        }

    @Test
    fun initSuspendedForNotActiveTileWithoutUpdates() =
        testScope.runTest {
            whenever(tileServiceManager.isActiveTile).thenReturn(false)
            customTileStatePersister.persistState(
                TileServiceKey(TEST_COMPONENT, TEST_USER.identifier),
                TEST_TILE,
            )
            val tiles = collectValues(underTest.tiles)

            val initJob = backgroundScope.launch { underTest.init() }
            advanceTimeBy(1 * DateUtils.DAY_IN_MILLIS)

            // Is still suspended
            assertThat(initJob.isActive).isTrue()
            assertThat(tiles()).isEmpty()
        }

    private companion object {

        val TEST_COMPONENT = ComponentName("test.pkg", "test.cls")
        val TEST_TILE_SPEC = TileSpec.create(TEST_COMPONENT)
        val TEST_USER = UserHandle.of(1)!!
        val TEST_TILE =
            Tile().apply {
                label = "test_tile_1"
                icon = Icon.createWithContentUri("file://test_1")
            }
        val TEST_DEFAULTS = CustomTileDefaults.Result(TEST_TILE.icon, TEST_TILE.label)
    }
}
