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

package com.android.systemui.qs.pipeline.domain.interactor

import android.content.Context
import android.content.pm.UserInfo
import android.os.UserHandle
import android.platform.test.annotations.DisableFlags
import android.platform.test.annotations.EnableFlags
import android.view.accessibility.Flags
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.accessibility.data.repository.FakeAccessibilityQsShortcutsRepository
import com.android.systemui.qs.FakeQSFactory
import com.android.systemui.qs.pipeline.domain.model.TileModel
import com.android.systemui.qs.pipeline.shared.TileSpec
import com.android.systemui.qs.tiles.ColorCorrectionTile
import com.android.systemui.qs.tiles.ColorInversionTile
import com.android.systemui.util.mockito.mock
import com.android.systemui.util.mockito.whenever
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock

@SmallTest
@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalCoroutinesApi::class)
class AccessibilityTilesInteractorTest : SysuiTestCase() {
    private val USER_0_INFO =
        UserInfo(
            0,
            "zero",
            "",
            UserInfo.FLAG_ADMIN or UserInfo.FLAG_FULL,
        )

    private val USER_1_INFO =
        UserInfo(
            1,
            "one",
            "",
            UserInfo.FLAG_ADMIN or UserInfo.FLAG_FULL,
        )

    private val USER_0_TILES = listOf(TileSpec.create(ColorInversionTile.TILE_SPEC))
    private val USER_1_TILES = listOf(TileSpec.create(ColorCorrectionTile.TILE_SPEC))
    private lateinit var currentTilesInteractor: CurrentTilesInteractor
    private lateinit var a11yQsShortcutsRepository: FakeAccessibilityQsShortcutsRepository
    private lateinit var underTest: AccessibilityTilesInteractor
    private lateinit var currentTiles: MutableStateFlow<List<TileModel>>
    private lateinit var userContext: MutableStateFlow<Context>
    private lateinit var qsFactory: FakeQSFactory
    private val testDispatcher = StandardTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    @Before
    fun setUp() {
        a11yQsShortcutsRepository = FakeAccessibilityQsShortcutsRepository()

        qsFactory = FakeQSFactory { spec: String ->
            FakeQSTile(userContext.value.userId).also { it.setTileSpec(spec) }
        }
        currentTiles = MutableStateFlow(emptyList())
        userContext = MutableStateFlow(mock(Context::class.java))
        setUser(USER_0_INFO)

        currentTilesInteractor = mock()
        whenever(currentTilesInteractor.currentTiles).thenReturn(currentTiles)
        whenever(currentTilesInteractor.userContext).thenReturn(userContext)
    }

    @Test
    @DisableFlags(Flags.FLAG_A11Y_QS_SHORTCUT)
    fun currentTilesChanged_a11yQsShortcutFlagOff_nothingHappen() =
        testScope.runTest {
            underTest = createInteractor()

            setTiles(USER_0_TILES)
            runCurrent()

            assertThat(a11yQsShortcutsRepository.notifyA11yManagerTilesChangedRequests).isEmpty()
        }

    @Test
    @EnableFlags(Flags.FLAG_A11Y_QS_SHORTCUT)
    fun currentTilesChanged_a11yQsShortcutFlagOn_notifyAccessibilityRepository() =
        testScope.runTest {
            underTest = createInteractor()

            setTiles(USER_0_TILES)
            runCurrent()

            val requests = a11yQsShortcutsRepository.notifyA11yManagerTilesChangedRequests
            assertThat(requests).hasSize(1)
            with(requests[0]) {
                assertThat(this.userContext.userId).isEqualTo(USER_0_INFO.id)
                assertThat(this.tiles).isEqualTo(USER_0_TILES)
            }
        }

    @Test
    @EnableFlags(Flags.FLAG_A11Y_QS_SHORTCUT)
    fun userChanged_a11yQsShortcutFlagOn_notifyAccessibilityRepositoryWithCorrectTilesAndUser() =
        testScope.runTest {
            underTest = createInteractor()
            setTiles(USER_0_TILES)
            runCurrent()

            // Change User and updates corresponding tiles
            setUser(USER_1_INFO)
            runCurrent()
            setTiles(USER_1_TILES)
            runCurrent()

            val requestsForUser1 =
                a11yQsShortcutsRepository.notifyA11yManagerTilesChangedRequests.filter {
                    it.userContext.userId == USER_1_INFO.id
                }
            assertThat(requestsForUser1).hasSize(1)
            assertThat(requestsForUser1[0].tiles).isEqualTo(USER_1_TILES)
        }

    private fun setTiles(tiles: List<TileSpec>) {
        currentTiles.tryEmit(
            tiles.mapNotNull { qsFactory.createTile(it.spec)?.let { it1 -> TileModel(it, it1) } }
        )
    }

    private fun setUser(userInfo: UserInfo) {
        userContext.tryEmit(
            mock(Context::class.java).also {
                whenever(it.userId).thenReturn(userInfo.id)
                whenever(it.user).thenReturn(UserHandle.of(userInfo.id))
            }
        )
    }

    private fun createInteractor(): AccessibilityTilesInteractor {
        return AccessibilityTilesInteractor(
                a11yQsShortcutsRepository,
                testDispatcher,
                testScope.backgroundScope
            )
            .apply { init(currentTilesInteractor) }
    }
}
