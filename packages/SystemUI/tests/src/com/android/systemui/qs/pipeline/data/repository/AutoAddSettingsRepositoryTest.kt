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
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.qs.pipeline.shared.TileSpec
import com.android.systemui.util.settings.FakeSettings
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
@RunWith(AndroidTestingRunner::class)
class AutoAddSettingsRepositoryTest : SysuiTestCase() {
    private val secureSettings = FakeSettings()

    private val testDispatcher = StandardTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    private lateinit var underTest: AutoAddSettingRepository

    @Before
    fun setUp() {
        underTest =
            AutoAddSettingRepository(
                secureSettings,
                testDispatcher,
            )
    }

    @Test
    fun nonExistentSetting_emptySet() =
        testScope.runTest {
            val specs by collectLastValue(underTest.autoAddedTiles(0))

            assertThat(specs).isEmpty()
        }

    @Test
    fun settingsChange_correctValues() =
        testScope.runTest {
            val userId = 0
            val specs by collectLastValue(underTest.autoAddedTiles(userId))

            val value = "a,custom(b/c)"
            storeForUser(value, userId)

            assertThat(specs).isEqualTo(value.toSet())

            val newValue = "a"
            storeForUser(newValue, userId)

            assertThat(specs).isEqualTo(newValue.toSet())
        }

    @Test
    fun tilesForCorrectUsers() =
        testScope.runTest {
            val tilesFromUser0 by collectLastValue(underTest.autoAddedTiles(0))
            val tilesFromUser1 by collectLastValue(underTest.autoAddedTiles(1))

            val user0Tiles = "a"
            val user1Tiles = "custom(b/c)"
            storeForUser(user0Tiles, 0)
            storeForUser(user1Tiles, 1)

            assertThat(tilesFromUser0).isEqualTo(user0Tiles.toSet())
            assertThat(tilesFromUser1).isEqualTo(user1Tiles.toSet())
        }

    @Test
    fun noInvalidTileSpecs() =
        testScope.runTest {
            val userId = 0
            val tiles by collectLastValue(underTest.autoAddedTiles(userId))

            val specs = "d,custom(bad)"
            storeForUser(specs, userId)

            assertThat(tiles).isEqualTo("d".toSet())
        }

    @Test
    fun markAdded() =
        testScope.runTest {
            val userId = 0
            val specs = mutableSetOf(TileSpec.create("a"))
            underTest.markTileAdded(userId, TileSpec.create("a"))

            assertThat(loadForUser(userId).toSet()).containsExactlyElementsIn(specs)

            specs.add(TileSpec.create("b"))
            underTest.markTileAdded(userId, TileSpec.create("b"))

            assertThat(loadForUser(userId).toSet()).containsExactlyElementsIn(specs)
        }

    @Test
    fun markAdded_multipleUsers() =
        testScope.runTest {
            underTest.markTileAdded(userId = 1, TileSpec.create("a"))

            assertThat(loadForUser(0).toSet()).isEmpty()
            assertThat(loadForUser(1).toSet())
                .containsExactlyElementsIn(setOf(TileSpec.create("a")))
        }

    @Test
    fun markAdded_Invalid_noop() =
        testScope.runTest {
            val userId = 0
            underTest.markTileAdded(userId, TileSpec.Invalid)

            assertThat(loadForUser(userId).toSet()).isEmpty()
        }

    @Test
    fun unmarkAdded() =
        testScope.runTest {
            val userId = 0
            val specs = "a,custom(b/c)"
            storeForUser(specs, userId)

            underTest.unmarkTileAdded(userId, TileSpec.create("a"))

            assertThat(loadForUser(userId).toSet())
                .containsExactlyElementsIn(setOf(TileSpec.create("custom(b/c)")))
        }

    @Test
    fun unmarkAdded_multipleUsers() =
        testScope.runTest {
            val specs = "a,b"
            storeForUser(specs, 0)
            storeForUser(specs, 1)

            underTest.unmarkTileAdded(1, TileSpec.create("a"))

            assertThat(loadForUser(0).toSet()).isEqualTo(specs.toSet())
            assertThat(loadForUser(1).toSet()).isEqualTo(setOf(TileSpec.create("b")))
        }

    private fun storeForUser(specs: String, userId: Int) {
        secureSettings.putStringForUser(SETTING, specs, userId)
    }

    private fun loadForUser(userId: Int): String {
        return secureSettings.getStringForUser(SETTING, userId) ?: ""
    }

    companion object {
        private const val SETTING = Settings.Secure.QS_AUTO_ADDED_TILES
        private const val DELIMITER = ","

        fun Set<TileSpec>.toSeparatedString() = joinToString(DELIMITER, transform = TileSpec::spec)

        fun String.toSet(): Set<TileSpec> {
            return if (isNullOrBlank()) {
                emptySet()
            } else {
                split(DELIMITER).map(TileSpec::create).toSet()
            }
        }
    }
}
