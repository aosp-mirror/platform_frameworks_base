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

import android.platform.test.annotations.EnabledOnRavenwood
import android.provider.Settings
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.qs.pipeline.shared.TileSpec
import com.android.systemui.qs.pipeline.shared.logging.QSPipelineLogger
import com.android.systemui.res.R
import com.android.systemui.retail.data.repository.FakeRetailModeRepository
import com.android.systemui.util.settings.FakeSettings
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.MockitoAnnotations

@SmallTest
@EnabledOnRavenwood
@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalCoroutinesApi::class)
class TileSpecSettingsRepositoryTest : SysuiTestCase() {

    private lateinit var secureSettings: FakeSettings
    private lateinit var retailModeRepository: FakeRetailModeRepository
    private val defaultTilesRepository =
        object : DefaultTilesRepository {
            override val defaultTiles: List<TileSpec>
                get() = DEFAULT_TILES.toTileSpecs()
        }

    @Mock private lateinit var logger: QSPipelineLogger

    private val userTileSpecRepositoryFactory =
        object : UserTileSpecRepository.Factory {
            override fun create(userId: Int): UserTileSpecRepository {
                return UserTileSpecRepository(
                    userId,
                    defaultTilesRepository,
                    secureSettings,
                    logger,
                    testScope.backgroundScope,
                    testDispatcher,
                )
            }
        }

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
            addOverride(R.string.quick_settings_tiles_retail_mode, RETAIL_TILES)
        }

        underTest =
            TileSpecSettingsRepository(
                context.resources,
                logger,
                retailModeRepository,
                userTileSpecRepositoryFactory
            )
    }

    @Test
    fun tilesForCorrectUsers() =
        testScope.runTest {
            val user0Tiles = "a"
            val user1Tiles = "custom(b/c)"
            storeTilesForUser(user0Tiles, 0)
            storeTilesForUser(user1Tiles, 1)

            val tilesFromUser0 by collectLastValue(underTest.tilesSpecs(0))
            val tilesFromUser1 by collectLastValue(underTest.tilesSpecs(1))

            assertThat(tilesFromUser0).isEqualTo(user0Tiles.toTileSpecs())
            assertThat(tilesFromUser1).isEqualTo(user1Tiles.toTileSpecs())
        }

    @Test
    fun addTileForOtherUser_addedInThatUser() =
        testScope.runTest {
            storeTilesForUser("a", 0)
            storeTilesForUser("b", 1)
            val tilesUser0 by collectLastValue(underTest.tilesSpecs(0))
            val tilesUser1 by collectLastValue(underTest.tilesSpecs(1))
            runCurrent()

            underTest.addTile(userId = 1, TileSpec.create("c"))

            assertThat(tilesUser0).isEqualTo("a".toTileSpecs())
            assertThat(loadTilesForUser(0)).isEqualTo("a")
            assertThat(tilesUser1).isEqualTo("b,c".toTileSpecs())
            assertThat(loadTilesForUser(1)).isEqualTo("b,c")
        }

    @Test
    fun removeTileFromSecondaryUser_removedOnlyInCorrectUser() =
        testScope.runTest {
            val specs = "a,b"
            storeTilesForUser(specs, 0)
            storeTilesForUser(specs, 1)
            val user0Tiles by collectLastValue(underTest.tilesSpecs(0))
            val user1Tiles by collectLastValue(underTest.tilesSpecs(1))
            runCurrent()

            underTest.removeTiles(userId = 1, listOf(TileSpec.create("a")))

            assertThat(user0Tiles).isEqualTo(specs.toTileSpecs())
            assertThat(loadTilesForUser(0)).isEqualTo(specs)
            assertThat(user1Tiles).isEqualTo("b".toTileSpecs())
            assertThat(loadTilesForUser(1)).isEqualTo("b")
        }

    @Test
    fun changeTiles_forCorrectUser() =
        testScope.runTest {
            val specs = "a"
            storeTilesForUser(specs, 0)
            storeTilesForUser(specs, 1)
            val user0Tiles by collectLastValue(underTest.tilesSpecs(0))
            val user1Tiles by collectLastValue(underTest.tilesSpecs(1))
            runCurrent()

            underTest.setTiles(userId = 1, "b".toTileSpecs())

            assertThat(user0Tiles).isEqualTo(specs.toTileSpecs())
            assertThat(loadTilesForUser(0)).isEqualTo("a")

            assertThat(user1Tiles).isEqualTo("b".toTileSpecs())
            assertThat(loadTilesForUser(1)).isEqualTo("b")
        }

    @Test
    fun retailMode_usesRetailTiles() =
        testScope.runTest {
            retailModeRepository.setRetailMode(true)

            val tiles by collectLastValue(underTest.tilesSpecs(0))
            runCurrent()

            assertThat(tiles).isEqualTo(RETAIL_TILES.toTileSpecs())
        }

    @Test
    fun retailMode_cannotModifyTiles() =
        testScope.runTest {
            retailModeRepository.setRetailMode(true)
            val tiles by collectLastValue(underTest.tilesSpecs(0))
            runCurrent()

            underTest.setTiles(0, listOf(TileSpec.create("a")))

            assertThat(loadTilesForUser(0)).isEqualTo(DEFAULT_TILES)
        }

    @Test
    fun prependDefault() =
        testScope.runTest {
            val tiles by collectLastValue(underTest.tilesSpecs(0))

            val startingTiles = listOf(TileSpec.create("e"), TileSpec.create("f"))

            underTest.setTiles(0, startingTiles)
            runCurrent()

            underTest.prependDefault(0)

            assertThat(tiles!!)
                .containsExactlyElementsIn(DEFAULT_TILES.toTileSpecs() + startingTiles)
        }

    private fun TestScope.storeTilesForUser(specs: String, forUser: Int) {
        secureSettings.putStringForUser(SETTING, specs, forUser)
        runCurrent()
    }

    private fun loadTilesForUser(forUser: Int): String? {
        return secureSettings.getStringForUser(SETTING, forUser)
    }

    companion object {
        private const val DEFAULT_TILES = "a,b,c"
        private const val RETAIL_TILES = "d"
        private const val SETTING = Settings.Secure.QS_TILES

        private fun String.toTileSpecs() = TilesSettingConverter.toTilesList(this)
    }
}
