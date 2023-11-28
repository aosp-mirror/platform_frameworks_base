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

package com.android.systemui.qs.tiles.impl.custom.data.repository

import android.content.ComponentName
import android.graphics.drawable.Icon
import android.os.UserHandle
import android.service.quicksettings.Tile
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.collectValues
import com.android.systemui.qs.external.FakeCustomTileStatePersister
import com.android.systemui.qs.external.TileServiceKey
import com.android.systemui.qs.pipeline.shared.TileSpec
import com.android.systemui.qs.tiles.impl.custom.TileSubject.Companion.assertThat
import com.android.systemui.qs.tiles.impl.custom.commons.copy
import com.android.systemui.qs.tiles.impl.custom.data.entity.CustomTileDefaults
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalCoroutinesApi::class)
class CustomTileRepositoryTest : SysuiTestCase() {

    private val testScope = TestScope()

    private val persister = FakeCustomTileStatePersister()

    private val underTest: CustomTileRepository =
        CustomTileRepositoryImpl(
            TileSpec.create(TEST_COMPONENT),
            persister,
            testScope.testScheduler,
        )

    @Test
    fun persistableTileIsRestoredForUser() =
        testScope.runTest {
            persister.persistState(TEST_TILE_KEY_1, TEST_TILE_1)
            persister.persistState(TEST_TILE_KEY_2, TEST_TILE_2)

            underTest.restoreForTheUserIfNeeded(TEST_USER_1, true)
            runCurrent()

            assertThat(underTest.getTile(TEST_USER_1)).isEqualTo(TEST_TILE_1)
            assertThat(underTest.getTiles(TEST_USER_1).first()).isEqualTo(TEST_TILE_1)
        }

    @Test
    fun notPersistableTileIsNotRestored() =
        testScope.runTest {
            persister.persistState(TEST_TILE_KEY_1, TEST_TILE_1)
            val tiles = collectValues(underTest.getTiles(TEST_USER_1))

            underTest.restoreForTheUserIfNeeded(TEST_USER_1, false)
            runCurrent()

            assertThat(tiles()).isEmpty()
        }

    @Test
    fun emptyPersistedStateIsHandled() =
        testScope.runTest {
            val tiles = collectValues(underTest.getTiles(TEST_USER_1))

            underTest.restoreForTheUserIfNeeded(TEST_USER_1, true)
            runCurrent()

            assertThat(tiles()).isEmpty()
        }

    @Test
    fun updatingWithPersistableTilePersists() =
        testScope.runTest {
            underTest.updateWithTile(TEST_USER_1, TEST_TILE_1, true)
            runCurrent()

            assertThat(persister.readState(TEST_TILE_KEY_1)).isEqualTo(TEST_TILE_1)
        }

    @Test
    fun updatingWithNotPersistableTileDoesntPersist() =
        testScope.runTest {
            underTest.updateWithTile(TEST_USER_1, TEST_TILE_1, false)
            runCurrent()

            assertThat(persister.readState(TEST_TILE_KEY_1)).isNull()
        }

    @Test
    fun updateWithTileEmits() =
        testScope.runTest {
            underTest.updateWithTile(TEST_USER_1, TEST_TILE_1, true)
            runCurrent()

            assertThat(underTest.getTiles(TEST_USER_1).first()).isEqualTo(TEST_TILE_1)
            assertThat(underTest.getTile(TEST_USER_1)).isEqualTo(TEST_TILE_1)
        }

    @Test
    fun updatingPeristableWithDefaultsPersists() =
        testScope.runTest {
            underTest.updateWithDefaults(TEST_USER_1, TEST_DEFAULTS_1, true)
            runCurrent()

            assertThat(persister.readState(TEST_TILE_KEY_1)).isEqualTo(TEST_TILE_1)
        }

    @Test
    fun updatingNotPersistableWithDefaultsDoesntPersist() =
        testScope.runTest {
            underTest.updateWithDefaults(TEST_USER_1, TEST_DEFAULTS_1, false)
            runCurrent()

            assertThat(persister.readState(TEST_TILE_KEY_1)).isNull()
        }

    @Test
    fun updatingPeristableWithErrorDefaultsDoesntPersist() =
        testScope.runTest {
            underTest.updateWithDefaults(TEST_USER_1, CustomTileDefaults.Error, true)
            runCurrent()

            assertThat(persister.readState(TEST_TILE_KEY_1)).isNull()
        }

    @Test
    fun updateWithDefaultsEmits() =
        testScope.runTest {
            underTest.updateWithDefaults(TEST_USER_1, TEST_DEFAULTS_1, true)
            runCurrent()

            assertThat(underTest.getTiles(TEST_USER_1).first()).isEqualTo(TEST_TILE_1)
            assertThat(underTest.getTile(TEST_USER_1)).isEqualTo(TEST_TILE_1)
        }

    @Test
    fun getTileForAnotherUserReturnsNull() =
        testScope.runTest {
            underTest.updateWithTile(TEST_USER_1, TEST_TILE_1, true)
            runCurrent()

            assertThat(underTest.getTile(TEST_USER_2)).isNull()
        }

    @Test
    fun getTilesForAnotherUserEmpty() =
        testScope.runTest {
            val tiles = collectValues(underTest.getTiles(TEST_USER_2))

            underTest.updateWithTile(TEST_USER_1, TEST_TILE_1, true)
            runCurrent()

            assertThat(tiles()).isEmpty()
        }

    @Test
    fun updatingWithTileForTheSameUserAddsData() =
        testScope.runTest {
            underTest.updateWithTile(TEST_USER_1, TEST_TILE_1, true)
            runCurrent()

            underTest.updateWithTile(TEST_USER_1, Tile().apply { subtitle = "test_subtitle" }, true)
            runCurrent()

            val expectedTile = TEST_TILE_1.copy().apply { subtitle = "test_subtitle" }
            assertThat(underTest.getTile(TEST_USER_1)).isEqualTo(expectedTile)
            assertThat(underTest.getTiles(TEST_USER_1).first()).isEqualTo(expectedTile)
        }

    @Test
    fun updatingWithTileForAnotherUserOverridesTile() =
        testScope.runTest {
            underTest.updateWithTile(TEST_USER_1, TEST_TILE_1, true)
            runCurrent()

            val tiles = collectValues(underTest.getTiles(TEST_USER_2))
            underTest.updateWithTile(TEST_USER_2, TEST_TILE_2, true)
            runCurrent()

            assertThat(underTest.getTile(TEST_USER_2)).isEqualTo(TEST_TILE_2)
            assertThat(tiles()).hasSize(1)
            assertThat(tiles().last()).isEqualTo(TEST_TILE_2)
        }

    @Test
    fun updatingWithDefaultsForTheSameUserAddsData() =
        testScope.runTest {
            underTest.updateWithTile(TEST_USER_1, Tile().apply { subtitle = "test_subtitle" }, true)
            runCurrent()

            underTest.updateWithDefaults(TEST_USER_1, TEST_DEFAULTS_1, true)
            runCurrent()

            val expectedTile = TEST_TILE_1.copy().apply { subtitle = "test_subtitle" }
            assertThat(underTest.getTile(TEST_USER_1)).isEqualTo(expectedTile)
            assertThat(underTest.getTiles(TEST_USER_1).first()).isEqualTo(expectedTile)
        }

    @Test
    fun updatingWithDefaultsForAnotherUserOverridesTile() =
        testScope.runTest {
            underTest.updateWithDefaults(TEST_USER_1, TEST_DEFAULTS_1, true)
            runCurrent()

            val tiles = collectValues(underTest.getTiles(TEST_USER_2))
            underTest.updateWithDefaults(TEST_USER_2, TEST_DEFAULTS_2, true)
            runCurrent()

            assertThat(underTest.getTile(TEST_USER_2)).isEqualTo(TEST_TILE_2)
            assertThat(tiles()).hasSize(1)
            assertThat(tiles().last()).isEqualTo(TEST_TILE_2)
        }

    private companion object {

        val TEST_COMPONENT = ComponentName("test.pkg", "test.cls")

        val TEST_USER_1 = UserHandle.of(1)!!
        val TEST_TILE_1 =
            Tile().apply {
                label = "test_tile_1"
                icon = Icon.createWithContentUri("file://test_1")
            }
        val TEST_TILE_KEY_1 = TileServiceKey(TEST_COMPONENT, TEST_USER_1.identifier)
        val TEST_DEFAULTS_1 = CustomTileDefaults.Result(TEST_TILE_1.icon, TEST_TILE_1.label)

        val TEST_USER_2 = UserHandle.of(2)!!
        val TEST_TILE_2 =
            Tile().apply {
                label = "test_tile_2"
                icon = Icon.createWithContentUri("file://test_2")
            }
        val TEST_TILE_KEY_2 = TileServiceKey(TEST_COMPONENT, TEST_USER_2.identifier)
        val TEST_DEFAULTS_2 = CustomTileDefaults.Result(TEST_TILE_2.icon, TEST_TILE_2.label)
    }
}
