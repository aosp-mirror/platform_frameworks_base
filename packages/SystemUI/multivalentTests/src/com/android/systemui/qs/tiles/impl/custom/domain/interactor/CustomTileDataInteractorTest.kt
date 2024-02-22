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
import android.content.pm.UserInfo
import android.graphics.drawable.Icon
import android.service.quicksettings.Tile
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.coroutines.collectValues
import com.android.systemui.kosmos.testScope
import com.android.systemui.qs.external.componentName
import com.android.systemui.qs.external.iQSTileService
import com.android.systemui.qs.external.tileServiceManagerFacade
import com.android.systemui.qs.external.tileServicesFacade
import com.android.systemui.qs.pipeline.shared.TileSpec
import com.android.systemui.qs.tiles.base.interactor.DataUpdateTrigger
import com.android.systemui.qs.tiles.impl.custom.TileSubject.Companion.assertThat
import com.android.systemui.qs.tiles.impl.custom.customTileDefaultsRepository
import com.android.systemui.qs.tiles.impl.custom.customTileInteractor
import com.android.systemui.qs.tiles.impl.custom.customTilePackagesUpdatesRepository
import com.android.systemui.qs.tiles.impl.custom.customTileRepository
import com.android.systemui.qs.tiles.impl.custom.customTileServiceInteractor
import com.android.systemui.qs.tiles.impl.custom.data.entity.CustomTileDefaults
import com.android.systemui.qs.tiles.impl.custom.tileSpec
import com.android.systemui.testKosmos
import com.android.systemui.user.data.repository.fakeUserRepository
import com.android.systemui.user.data.repository.userRepository
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalCoroutinesApi::class)
class CustomTileDataInteractorTest : SysuiTestCase() {

    private val kosmos =
        testKosmos().apply {
            componentName = TEST_COMPONENT
            tileSpec = TileSpec.create(componentName)
        }
    private val underTest =
        with(kosmos) {
            CustomTileDataInteractor(
                tileSpec = tileSpec,
                defaultsRepository = customTileDefaultsRepository,
                serviceInteractor = customTileServiceInteractor,
                customTileInteractor = customTileInteractor,
                packageUpdatesRepository = customTilePackagesUpdatesRepository,
                userRepository = userRepository,
                tileScope = testScope.backgroundScope,
            )
        }

    private suspend fun setup() {
        with(kosmos) {
            fakeUserRepository.setUserInfos(listOf(TEST_USER_1))
            fakeUserRepository.setSelectedUserInfo(TEST_USER_1)
        }
    }

    @Test
    fun activeTileIsNotBoundUntilDataCollected() =
        with(kosmos) {
            testScope.runTest {
                setup()
                customTileRepository.setTileActive(true)

                runCurrent()

                assertThat(iQSTileService.isTileListening).isFalse()
                assertThat(tileServiceManagerFacade.isBound).isFalse()
            }
        }

    @Test
    fun notActiveTileIsNotBoundUntilDataCollected() =
        with(kosmos) {
            testScope.runTest {
                setup()
                customTileRepository.setTileActive(false)

                runCurrent()

                assertThat(iQSTileService.isTileListening).isFalse()
                assertThat(tileServiceManagerFacade.isBound).isFalse()
            }
        }

    @Test
    fun tileIsUnboundWhenDataIsNotListened() =
        with(kosmos) {
            testScope.runTest {
                setup()
                customTileRepository.setTileActive(false)
                customTileDefaultsRepository.putDefaults(
                    TEST_USER_1.userHandle,
                    componentName,
                    CustomTileDefaults.Result(TEST_TILE.icon, TEST_TILE.label),
                )
                val dataJob =
                    underTest
                        .tileData(TEST_USER_1.userHandle, flowOf(DataUpdateTrigger.InitialRequest))
                        .launchIn(backgroundScope)
                runCurrent()
                tileServiceManagerFacade.processPendingBind()
                assertThat(iQSTileService.isTileListening).isTrue()
                assertThat(tileServiceManagerFacade.isBound).isTrue()

                dataJob.cancel()
                runCurrent()

                assertThat(iQSTileService.isTileListening).isFalse()
                assertThat(tileServiceManagerFacade.isBound).isFalse()
            }
        }

    @Test
    fun tileDataCollection() =
        with(kosmos) {
            testScope.runTest {
                setup()
                customTileDefaultsRepository.putDefaults(
                    TEST_USER_1.userHandle,
                    componentName,
                    CustomTileDefaults.Result(TEST_TILE.icon, TEST_TILE.label),
                )
                val tileData by
                    collectLastValue(
                        underTest.tileData(
                            TEST_USER_1.userHandle,
                            flowOf(DataUpdateTrigger.InitialRequest)
                        )
                    )
                runCurrent()
                tileServicesFacade.customTileInterface!!.updateTileState(TEST_TILE, 1)

                runCurrent()

                with(tileData!!) {
                    assertThat(user.identifier).isEqualTo(TEST_USER_1.id)
                    assertThat(componentName).isEqualTo(componentName)
                    assertThat(tile).isEqualTo(TEST_TILE)
                    assertThat(callingAppUid).isEqualTo(1)
                    assertThat(hasPendingBind).isEqualTo(true)
                    assertThat(isToggleable).isEqualTo(false)
                    assertThat(defaultTileIcon).isEqualTo(TEST_TILE.icon)
                    assertThat(defaultTileLabel).isEqualTo(TEST_TILE.label)
                }
            }
        }

    @Test
    fun tileAvailableWhenDefaultsAreLoaded() =
        with(kosmos) {
            testScope.runTest {
                setup()
                customTileDefaultsRepository.putDefaults(
                    TEST_USER_1.userHandle,
                    tileSpec.componentName,
                    CustomTileDefaults.Result(TEST_TILE.icon, TEST_TILE.label),
                )

                val isAvailable by collectValues(underTest.availability(TEST_USER_1.userHandle))
                runCurrent()

                assertThat(isAvailable).containsExactlyElementsIn(arrayOf(true)).inOrder()
            }
        }

    @Test
    fun tileUnavailableWhenDefaultsAreNotLoaded() =
        with(kosmos) {
            testScope.runTest {
                setup()
                customTileDefaultsRepository.putDefaults(
                    TEST_USER_1.userHandle,
                    tileSpec.componentName,
                    CustomTileDefaults.Error,
                )

                val isAvailable by collectValues(underTest.availability(TEST_USER_1.userHandle))
                runCurrent()

                assertThat(isAvailable).containsExactlyElementsIn(arrayOf(false)).inOrder()
            }
        }

    @Test
    fun tileAvailabilityUndefinedWhenDefaultsAreLoadedForAnotherUser() =
        with(kosmos) {
            testScope.runTest {
                setup()
                customTileDefaultsRepository.putDefaults(
                    TEST_USER_2.userHandle,
                    tileSpec.componentName,
                    CustomTileDefaults.Error,
                )

                val isAvailable by collectValues(underTest.availability(TEST_USER_1.userHandle))
                runCurrent()

                assertThat(isAvailable).containsExactlyElementsIn(arrayOf()).inOrder()
            }
        }

    private companion object {

        val TEST_COMPONENT = ComponentName("test.pkg", "test.cls")
        val TEST_USER_1 = UserInfo(1, "first user", UserInfo.FLAG_MAIN)
        val TEST_USER_2 = UserInfo(2, "second user", UserInfo.FLAG_MAIN)
        val TEST_TILE =
            Tile().apply {
                label = "test_tile_1"
                icon = Icon.createWithContentUri("file://test_1")
            }
    }
}
