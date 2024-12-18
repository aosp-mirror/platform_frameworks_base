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

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.kosmos.testScope
import com.android.systemui.qs.pipeline.shared.TileSpec
import com.android.systemui.qs.tiles.base.interactor.QSTileAvailabilityInteractor
import com.android.systemui.statusbar.connectivity.ConnectivityModule.Companion.AIRPLANE_MODE_TILE_SPEC
import com.android.systemui.statusbar.connectivity.ConnectivityModule.Companion.HOTSPOT_TILE_SPEC
import com.android.systemui.statusbar.policy.PolicyModule.Companion.WORK_MODE_TILE_SPEC
import com.android.systemui.testKosmos
import com.android.systemui.user.data.repository.fakeUserRepository
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@SmallTest
class NewTilesAvailabilityInteractorTest : SysuiTestCase() {
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
    }

    private val underTest by lazy { kosmos.newTilesAvailabilityInteractor }

    @Test
    fun defaultUser_getAvailabilityFlow() = with(kosmos) {
        testScope.runTest {
            val availability by collectLastValue(underTest.newTilesAvailable)

            assertThat(availability).isEqualTo(
                    mapOf(
                            TileSpec.create(AIRPLANE_MODE_TILE_SPEC) to true,
                            TileSpec.create(WORK_MODE_TILE_SPEC) to true,
                            TileSpec.create(HOTSPOT_TILE_SPEC) to false,
                    )
            )
        }
    }

    @Test
    fun getAvailabilityFlow_userChange() = with(kosmos) {
        testScope.runTest {
            val availability by collectLastValue(underTest.newTilesAvailable)
            fakeUserRepository.asMainUser()

            assertThat(availability).isEqualTo(
                    mapOf(
                            TileSpec.create(AIRPLANE_MODE_TILE_SPEC) to true,
                            TileSpec.create(WORK_MODE_TILE_SPEC) to false,
                            TileSpec.create(HOTSPOT_TILE_SPEC) to false,
                    )
            )
        }
    }

    @Test
    fun noAvailabilityInteractor_emptyMap() = with(kosmos) {
        testScope.runTest {
            tileAvailabilityInteractorsMap = emptyMap()

            val availability by collectLastValue(underTest.newTilesAvailable)

            assertThat(availability).isEmpty()
        }
    }
}
