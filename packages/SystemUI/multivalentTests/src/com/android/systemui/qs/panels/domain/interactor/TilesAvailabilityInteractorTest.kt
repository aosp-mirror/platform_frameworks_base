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

import android.platform.test.annotations.DisableFlags
import android.platform.test.annotations.EnableFlags
import android.platform.test.flag.junit.FlagsParameterization
import androidx.test.filters.SmallTest
import com.android.systemui.Flags.FLAG_QS_NEW_TILES
import com.android.systemui.SysuiTestCase
import com.android.systemui.kosmos.testScope
import com.android.systemui.plugins.qs.QSFactory
import com.android.systemui.qs.FakeQSFactory
import com.android.systemui.qs.FakeQSTile
import com.android.systemui.qs.pipeline.shared.TileSpec
import com.android.systemui.qs.qsTileFactory
import com.android.systemui.qs.tiles.base.interactor.QSTileAvailabilityInteractor
import com.android.systemui.statusbar.connectivity.ConnectivityModule.Companion.AIRPLANE_MODE_TILE_SPEC
import com.android.systemui.statusbar.connectivity.ConnectivityModule.Companion.HOTSPOT_TILE_SPEC
import com.android.systemui.statusbar.connectivity.ConnectivityModule.Companion.INTERNET_TILE_SPEC
import com.android.systemui.statusbar.policy.PolicyModule.Companion.FLASHLIGHT_TILE_SPEC
import com.android.systemui.statusbar.policy.PolicyModule.Companion.WORK_MODE_TILE_SPEC
import com.android.systemui.testKosmos
import com.android.systemui.user.data.repository.fakeUserRepository
import com.android.systemui.user.data.repository.userRepository
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import platform.test.runner.parameterized.ParameterizedAndroidJunit4
import platform.test.runner.parameterized.Parameters

@RunWith(ParameterizedAndroidJunit4::class)
@SmallTest
class TilesAvailabilityInteractorTest(flags: FlagsParameterization) : SysuiTestCase() {

    init {
        mSetFlagsRule.setFlagsParameterization(flags)
    }

    private val createdTiles = mutableListOf<FakeQSTile>()

    private val kosmos = testKosmos().apply {
        tileAvailabilityInteractorsMap = buildMap {
            put(AIRPLANE_MODE_TILE_SPEC, QSTileAvailabilityInteractor.AlwaysAvailableInteractor)
            put(WORK_MODE_TILE_SPEC, FakeTileAvailabilityInteractor(
                    mapOf(
                            fakeUserRepository.getSelectedUserInfo().id to flowOf(true),
                    ).withDefault { flowOf(false) }
            ))
            put(HOTSPOT_TILE_SPEC, FakeTileAvailabilityInteractor(
                    emptyMap<Int, Flow<Boolean>>().withDefault { flowOf(false) }
            ))
        }

        qsTileFactory = constantFactory(
                tilesForCreator(
                        userRepository.getSelectedUserInfo().id,
                        mapOf(
                                AIRPLANE_MODE_TILE_SPEC to false,
                                WORK_MODE_TILE_SPEC to false,
                                HOTSPOT_TILE_SPEC to true,
                                INTERNET_TILE_SPEC to true,
                                FLASHLIGHT_TILE_SPEC to false,
                        )
                )
        )
    }

    private val underTest by lazy { kosmos.tilesAvailabilityInteractor }

    @Test
    @DisableFlags(FLAG_QS_NEW_TILES)
    fun flagOff_usesAvailabilityFromFactoryTiles() = with(kosmos) {
        testScope.runTest {
            val unavailableTiles = underTest.getUnavailableTiles(
                    setOf(
                            AIRPLANE_MODE_TILE_SPEC,
                            WORK_MODE_TILE_SPEC,
                            HOTSPOT_TILE_SPEC,
                            INTERNET_TILE_SPEC,
                            FLASHLIGHT_TILE_SPEC,
                    ).map(TileSpec::create)
            )
            assertThat(unavailableTiles).isEqualTo(setOf(
                    AIRPLANE_MODE_TILE_SPEC,
                    WORK_MODE_TILE_SPEC,
                    FLASHLIGHT_TILE_SPEC,
            ).mapTo(mutableSetOf(), TileSpec::create))
        }
    }

    @Test
    fun tileCannotBeCreated_isUnavailable() = with(kosmos) {
        testScope.runTest {
            val badSpec = TileSpec.create("unknown")
            val unavailableTiles = underTest.getUnavailableTiles(
                    setOf(
                        badSpec
                    )
            )
            assertThat(unavailableTiles).contains(badSpec)
        }
    }

    @Test
    @EnableFlags(FLAG_QS_NEW_TILES)
    fun flagOn_defaultsToInteractorTiles_usesFactoryForOthers() = with(kosmos) {
        testScope.runTest {
            val unavailableTiles = underTest.getUnavailableTiles(
                    setOf(
                            AIRPLANE_MODE_TILE_SPEC,
                            WORK_MODE_TILE_SPEC,
                            HOTSPOT_TILE_SPEC,
                            INTERNET_TILE_SPEC,
                            FLASHLIGHT_TILE_SPEC,
                    ).map(TileSpec::create)
            )
            assertThat(unavailableTiles).isEqualTo(setOf(
                    HOTSPOT_TILE_SPEC,
                    FLASHLIGHT_TILE_SPEC,
            ).mapTo(mutableSetOf(), TileSpec::create))
        }
    }

    @Test
    @EnableFlags(FLAG_QS_NEW_TILES)
    fun flagOn_defaultsToInteractorTiles_usesFactoryForOthers_userChange() = with(kosmos) {
        testScope.runTest {
            fakeUserRepository.asMainUser()
            val unavailableTiles = underTest.getUnavailableTiles(
                    setOf(
                            AIRPLANE_MODE_TILE_SPEC,
                            WORK_MODE_TILE_SPEC,
                            HOTSPOT_TILE_SPEC,
                            INTERNET_TILE_SPEC,
                            FLASHLIGHT_TILE_SPEC,
                    ).map(TileSpec::create)
            )
            assertThat(unavailableTiles).isEqualTo(setOf(
                    WORK_MODE_TILE_SPEC,
                    HOTSPOT_TILE_SPEC,
                    FLASHLIGHT_TILE_SPEC,
            ).mapTo(mutableSetOf(), TileSpec::create))
        }
    }

    @Test
    @EnableFlags(FLAG_QS_NEW_TILES)
    fun flagOn_onlyNeededTilesAreCreated_andThenDestroyed() = with(kosmos) {
        testScope.runTest {
            underTest.getUnavailableTiles(
                    setOf(
                            AIRPLANE_MODE_TILE_SPEC,
                            WORK_MODE_TILE_SPEC,
                            HOTSPOT_TILE_SPEC,
                            INTERNET_TILE_SPEC,
                            FLASHLIGHT_TILE_SPEC,
                    ).map(TileSpec::create)
            )
            assertThat(createdTiles.map { it.tileSpec })
                    .containsExactly(INTERNET_TILE_SPEC, FLASHLIGHT_TILE_SPEC)
            assertThat(createdTiles.all { it.destroyed }).isTrue()
        }
    }

    @Test
    @DisableFlags(FLAG_QS_NEW_TILES)
    fun flagOn_TilesAreCreatedAndThenDestroyed() = with(kosmos) {
        testScope.runTest {
            val allTiles = setOf(
                    AIRPLANE_MODE_TILE_SPEC,
                    WORK_MODE_TILE_SPEC,
                    HOTSPOT_TILE_SPEC,
                    INTERNET_TILE_SPEC,
                    FLASHLIGHT_TILE_SPEC,
                )
            underTest.getUnavailableTiles(allTiles.map(TileSpec::create))
            assertThat(createdTiles.map { it.tileSpec })
                    .containsExactlyElementsIn(allTiles)
            assertThat(createdTiles.all { it.destroyed }).isTrue()
        }
    }


    private fun constantFactory(creatorTiles: Set<FakeQSTile>): QSFactory {
        return FakeQSFactory { spec ->
            creatorTiles.firstOrNull { it.tileSpec == spec }?.also {
                createdTiles.add(it)
            }
        }
    }

    companion object {
        private fun tilesForCreator(
                user: Int,
                specAvailabilities: Map<String, Boolean>
        ): Set<FakeQSTile> {
            return specAvailabilities.mapTo(mutableSetOf()) {
                FakeQSTile(user, it.value).apply {
                    tileSpec = it.key
                }
            }
        }

        @JvmStatic
        @Parameters(name = "{0}")
        fun getParams(): List<FlagsParameterization> {
            return FlagsParameterization.allCombinationsOf(FLAG_QS_NEW_TILES)
        }
    }
}
