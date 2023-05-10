/*
 * Copyright (C) 2023 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui.qs.pipeline.data.repository

import android.provider.Settings
import android.testing.AndroidTestingRunner
import androidx.test.filters.SmallTest
import com.android.systemui.R
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.qs.QSHost
import com.android.systemui.qs.pipeline.shared.TileSpec
import com.android.systemui.qs.pipeline.shared.logging.QSPipelineLogger
import com.android.systemui.retail.data.repository.FakeRetailModeRepository
import com.android.systemui.util.settings.FakeSettings
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.MockitoAnnotations

@SmallTest
@RunWith(AndroidTestingRunner::class)
@OptIn(ExperimentalCoroutinesApi::class)
class TileSpecSettingsRepositoryTest : SysuiTestCase() {

    private lateinit var secureSettings: FakeSettings
    private lateinit var retailModeRepository: FakeRetailModeRepository

    @Mock private lateinit var logger: QSPipelineLogger

    private val testDispatcher = StandardTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    private lateinit var underTest: TileSpecSettingsRepository

    @Before
    fun setup() {
        MockitoAnnotations.initMocks(this)

        secureSettings = FakeSettings()
        retailModeRepository = FakeRetailModeRepository()
        retailModeRepository.setRetailMode(false)

        with(context.orCreateTestableResources) {
            addOverride(R.string.quick_settings_tiles_default, DEFAULT_TILES)
            addOverride(R.string.quick_settings_tiles_retail_mode, RETAIL_TILES)
        }

        underTest =
            TileSpecSettingsRepository(
                secureSettings,
                context.resources,
                logger,
                retailModeRepository,
                testDispatcher,
            )
    }

    @Test
    fun emptySetting_usesDefaultValue() =
        testScope.runTest {
            val tiles by collectLastValue(underTest.tilesSpecs(0))
            assertThat(tiles).isEqualTo(getDefaultTileSpecs())
        }

    @Test
    fun changeInSettings_changesValue() =
        testScope.runTest {
            val tiles by collectLastValue(underTest.tilesSpecs(0))

            storeTilesForUser("a", 0)
            assertThat(tiles).isEqualTo(listOf(TileSpec.create("a")))

            storeTilesForUser("a,custom(b/c)", 0)
            assertThat(tiles)
                .isEqualTo(listOf(TileSpec.create("a"), TileSpec.create("custom(b/c)")))
        }

    @Test
    fun tilesForCorrectUsers() =
        testScope.runTest {
            val tilesFromUser0 by collectLastValue(underTest.tilesSpecs(0))
            val tilesFromUser1 by collectLastValue(underTest.tilesSpecs(1))

            val user0Tiles = "a"
            val user1Tiles = "custom(b/c)"
            storeTilesForUser(user0Tiles, 0)
            storeTilesForUser(user1Tiles, 1)

            assertThat(tilesFromUser0).isEqualTo(user0Tiles.toTileSpecs())
            assertThat(tilesFromUser1).isEqualTo(user1Tiles.toTileSpecs())
        }

    @Test
    fun invalidTilesAreNotPresent() =
        testScope.runTest {
            val tiles by collectLastValue(underTest.tilesSpecs(0))

            val specs = "d,custom(bad)"
            storeTilesForUser(specs, 0)

            assertThat(tiles).isEqualTo(specs.toTileSpecs().filter { it != TileSpec.Invalid })
        }

    @Test
    fun noValidTiles_defaultSet() =
        testScope.runTest {
            val tiles by collectLastValue(underTest.tilesSpecs(0))

            storeTilesForUser("custom(bad),custom()", 0)

            assertThat(tiles).isEqualTo(getDefaultTileSpecs())
        }

    @Test
    fun addTileAtEnd() =
        testScope.runTest {
            val tiles by collectLastValue(underTest.tilesSpecs(0))

            storeTilesForUser("a", 0)

            underTest.addTile(userId = 0, TileSpec.create("b"))

            val expected = "a,b"
            assertThat(loadTilesForUser(0)).isEqualTo(expected)
            assertThat(tiles).isEqualTo(expected.toTileSpecs())
        }

    @Test
    fun addTileAtPosition() =
        testScope.runTest {
            val tiles by collectLastValue(underTest.tilesSpecs(0))

            storeTilesForUser("a,custom(b/c)", 0)

            underTest.addTile(userId = 0, TileSpec.create("d"), position = 1)

            val expected = "a,d,custom(b/c)"
            assertThat(loadTilesForUser(0)).isEqualTo(expected)
            assertThat(tiles).isEqualTo(expected.toTileSpecs())
        }

    @Test
    fun addInvalidTile_noop() =
        testScope.runTest {
            val tiles by collectLastValue(underTest.tilesSpecs(0))

            val specs = "a,custom(b/c)"
            storeTilesForUser(specs, 0)

            underTest.addTile(userId = 0, TileSpec.Invalid)

            assertThat(loadTilesForUser(0)).isEqualTo(specs)
            assertThat(tiles).isEqualTo(specs.toTileSpecs())
        }

    @Test
    fun addTileAtPosition_tooLarge_addedAtEnd() =
        testScope.runTest {
            val tiles by collectLastValue(underTest.tilesSpecs(0))

            val specs = "a,custom(b/c)"
            storeTilesForUser(specs, 0)

            underTest.addTile(userId = 0, TileSpec.create("d"), position = 100)

            val expected = "a,custom(b/c),d"
            assertThat(loadTilesForUser(0)).isEqualTo(expected)
            assertThat(tiles).isEqualTo(expected.toTileSpecs())
        }

    @Test
    fun addTileForOtherUser_addedInThatUser() =
        testScope.runTest {
            val tilesUser0 by collectLastValue(underTest.tilesSpecs(0))
            val tilesUser1 by collectLastValue(underTest.tilesSpecs(1))

            storeTilesForUser("a", 0)
            storeTilesForUser("b", 1)

            underTest.addTile(userId = 1, TileSpec.create("c"))

            assertThat(loadTilesForUser(0)).isEqualTo("a")
            assertThat(tilesUser0).isEqualTo("a".toTileSpecs())
            assertThat(loadTilesForUser(1)).isEqualTo("b,c")
            assertThat(tilesUser1).isEqualTo("b,c".toTileSpecs())
        }

    @Test
    fun removeTiles() =
        testScope.runTest {
            val tiles by collectLastValue(underTest.tilesSpecs(0))

            storeTilesForUser("a,b", 0)

            underTest.removeTiles(userId = 0, listOf(TileSpec.create("a")))

            assertThat(loadTilesForUser(0)).isEqualTo("b")
            assertThat(tiles).isEqualTo("b".toTileSpecs())
        }

    @Test
    fun removeTilesNotThere_noop() =
        testScope.runTest {
            val tiles by collectLastValue(underTest.tilesSpecs(0))

            val specs = "a,b"
            storeTilesForUser(specs, 0)

            underTest.removeTiles(userId = 0, listOf(TileSpec.create("c")))

            assertThat(loadTilesForUser(0)).isEqualTo(specs)
            assertThat(tiles).isEqualTo(specs.toTileSpecs())
        }

    @Test
    fun removeInvalidTile_noop() =
        testScope.runTest {
            val tiles by collectLastValue(underTest.tilesSpecs(0))

            val specs = "a,b"
            storeTilesForUser(specs, 0)

            underTest.removeTiles(userId = 0, listOf(TileSpec.Invalid))

            assertThat(loadTilesForUser(0)).isEqualTo(specs)
            assertThat(tiles).isEqualTo(specs.toTileSpecs())
        }

    @Test
    fun removeTileFromSecondaryUser_removedOnlyInCorrectUser() =
        testScope.runTest {
            val user0Tiles by collectLastValue(underTest.tilesSpecs(0))
            val user1Tiles by collectLastValue(underTest.tilesSpecs(1))

            val specs = "a,b"
            storeTilesForUser(specs, 0)
            storeTilesForUser(specs, 1)

            underTest.removeTiles(userId = 1, listOf(TileSpec.create("a")))

            assertThat(loadTilesForUser(0)).isEqualTo(specs)
            assertThat(user0Tiles).isEqualTo(specs.toTileSpecs())
            assertThat(loadTilesForUser(1)).isEqualTo("b")
            assertThat(user1Tiles).isEqualTo("b".toTileSpecs())
        }

    @Test
    fun removeMultipleTiles() =
        testScope.runTest {
            val tiles by collectLastValue(underTest.tilesSpecs(0))

            storeTilesForUser("a,b,c,d", 0)

            underTest.removeTiles(userId = 0, listOf(TileSpec.create("a"), TileSpec.create("c")))

            assertThat(loadTilesForUser(0)).isEqualTo("b,d")
            assertThat(tiles).isEqualTo("b,d".toTileSpecs())
        }

    @Test
    fun changeTiles() =
        testScope.runTest {
            val tiles by collectLastValue(underTest.tilesSpecs(0))

            val specs = "a,custom(b/c)"

            underTest.setTiles(userId = 0, specs.toTileSpecs())

            assertThat(loadTilesForUser(0)).isEqualTo(specs)
            assertThat(tiles).isEqualTo(specs.toTileSpecs())
        }

    @Test
    fun changeTiles_ignoresInvalid() =
        testScope.runTest {
            val tiles by collectLastValue(underTest.tilesSpecs(0))

            val specs = "a,custom(b/c)"

            underTest.setTiles(userId = 0, listOf(TileSpec.Invalid) + specs.toTileSpecs())

            assertThat(loadTilesForUser(0)).isEqualTo(specs)
            assertThat(tiles).isEqualTo(specs.toTileSpecs())
        }

    @Test
    fun changeTiles_empty_noChanges() =
        testScope.runTest {
            val tiles by collectLastValue(underTest.tilesSpecs(0))

            underTest.setTiles(userId = 0, emptyList())

            assertThat(loadTilesForUser(0)).isNull()
            assertThat(tiles).isEqualTo(getDefaultTileSpecs())
        }

    @Test
    fun changeTiles_forCorrectUser() =
        testScope.runTest {
            val user0Tiles by collectLastValue(underTest.tilesSpecs(0))
            val user1Tiles by collectLastValue(underTest.tilesSpecs(1))

            val specs = "a"
            storeTilesForUser(specs, 0)
            storeTilesForUser(specs, 1)

            underTest.setTiles(userId = 1, "b".toTileSpecs())

            assertThat(loadTilesForUser(0)).isEqualTo("a")
            assertThat(user0Tiles).isEqualTo(specs.toTileSpecs())

            assertThat(loadTilesForUser(1)).isEqualTo("b")
            assertThat(user1Tiles).isEqualTo("b".toTileSpecs())
        }

    @Test
    fun multipleConcurrentRemovals_bothRemoved() =
        testScope.runTest {
            val tiles by collectLastValue(underTest.tilesSpecs(0))

            val specs = "a,b,c"
            storeTilesForUser(specs, 0)

            coroutineScope {
                underTest.removeTiles(userId = 0, listOf(TileSpec.create("c")))
                underTest.removeTiles(userId = 0, listOf(TileSpec.create("a")))
            }

            assertThat(loadTilesForUser(0)).isEqualTo("b")
            assertThat(tiles).isEqualTo("b".toTileSpecs())
        }

    @Test
    fun retailMode_usesRetailTiles() =
        testScope.runTest {
            retailModeRepository.setRetailMode(true)

            val tiles by collectLastValue(underTest.tilesSpecs(0))

            assertThat(tiles).isEqualTo(RETAIL_TILES.toTileSpecs())
        }

    @Test
    fun retailMode_cannotModifyTiles() =
        testScope.runTest {
            retailModeRepository.setRetailMode(true)

            underTest.setTiles(0, DEFAULT_TILES.toTileSpecs())

            assertThat(loadTilesForUser(0)).isNull()
        }

    private fun getDefaultTileSpecs(): List<TileSpec> {
        return QSHost.getDefaultSpecs(context.resources).map(TileSpec::create)
    }

    private fun storeTilesForUser(specs: String, forUser: Int) {
        secureSettings.putStringForUser(SETTING, specs, forUser)
    }

    private fun loadTilesForUser(forUser: Int): String? {
        return secureSettings.getStringForUser(SETTING, forUser)
    }

    companion object {
        private const val DEFAULT_TILES = "a,b,c"
        private const val RETAIL_TILES = "d"
        private const val SETTING = Settings.Secure.QS_TILES

        private fun String.toTileSpecs(): List<TileSpec> {
            return split(",").map(TileSpec::create)
        }
    }
}
