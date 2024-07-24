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

@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
@EnabledOnRavenwood
@RunWith(AndroidJUnit4::class)
class AutoAddSettingsRepositoryTest : SysuiTestCase() {
    private val secureSettings = FakeSettings()
    private val userAutoAddRepositoryFactory =
        object : UserAutoAddRepository.Factory {
            override fun create(userId: Int): UserAutoAddRepository {
                return UserAutoAddRepository(
                    userId,
                    secureSettings,
                    logger,
                    testScope.backgroundScope,
                    testDispatcher,
                )
            }
        }

    @Mock private lateinit var logger: QSPipelineLogger

    private val testDispatcher = StandardTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    private lateinit var underTest: AutoAddSettingRepository

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)

        underTest = AutoAddSettingRepository(userAutoAddRepositoryFactory)
    }

    @Test
    fun tilesForCorrectUsers() =
        testScope.runTest {
            val user0Tiles = "a"
            val user1Tiles = "custom(b/c)"
            storeForUser(user0Tiles, 0)
            storeForUser(user1Tiles, 1)
            val tilesFromUser0 by collectLastValue(underTest.autoAddedTiles(0))
            val tilesFromUser1 by collectLastValue(underTest.autoAddedTiles(1))
            runCurrent()

            assertThat(tilesFromUser0).isEqualTo(user0Tiles.toTilesSet())
            assertThat(tilesFromUser1).isEqualTo(user1Tiles.toTilesSet())
        }

    @Test
    fun markAdded_multipleUsers() =
        testScope.runTest {
            val tilesFromUser0 by collectLastValue(underTest.autoAddedTiles(0))
            val tilesFromUser1 by collectLastValue(underTest.autoAddedTiles(1))
            runCurrent()

            underTest.markTileAdded(userId = 1, TileSpec.create("a"))

            assertThat(tilesFromUser0).isEmpty()
            assertThat(tilesFromUser1).containsExactlyElementsIn(setOf(TileSpec.create("a")))
        }

    @Test
    fun unmarkAdded_multipleUsers() =
        testScope.runTest {
            val specs = "a,b"
            storeForUser(specs, 0)
            storeForUser(specs, 1)
            val tilesFromUser0 by collectLastValue(underTest.autoAddedTiles(0))
            val tilesFromUser1 by collectLastValue(underTest.autoAddedTiles(1))
            runCurrent()

            underTest.unmarkTileAdded(1, TileSpec.create("a"))

            assertThat(tilesFromUser0).isEqualTo(specs.toTilesSet())
            assertThat(tilesFromUser1).isEqualTo(setOf(TileSpec.create("b")))
        }

    private fun storeForUser(specs: String, userId: Int) {
        secureSettings.putStringForUser(SETTING, specs, userId)
    }

    companion object {
        private const val SETTING = Settings.Secure.QS_AUTO_ADDED_TILES

        private fun String.toTilesSet() = TilesSettingConverter.toTilesSet(this)
    }
}
