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

package com.android.systemui.mediarouter.data.repository

import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.kosmos.Kosmos
import com.android.systemui.kosmos.testScope
import com.android.systemui.statusbar.policy.CastDevice
import com.android.systemui.statusbar.policy.fakeCastController
import com.google.common.truth.Truth.assertThat
import kotlin.test.Test
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

@SmallTest
@OptIn(ExperimentalCoroutinesApi::class)
class MediaRouterRepositoryTest : SysuiTestCase() {
    val kosmos = Kosmos()
    val testScope = kosmos.testScope
    val castController = kosmos.fakeCastController

    val underTest = kosmos.realMediaRouterRepository

    @Test
    fun castDevices_empty_isEmpty() =
        testScope.runTest {
            val latest by collectLastValue(underTest.castDevices)
            // Required to let the listener attach before the devices get set
            runCurrent()

            castController.castDevices = emptyList()

            assertThat(latest).isEmpty()
        }

    @Test
    fun castDevices_onlyIncludesMediaRouterOriginDevices() =
        testScope.runTest {
            val latest by collectLastValue(underTest.castDevices)
            // Required to let the listener attach before the devices get set
            runCurrent()

            val projectionDevice =
                CastDevice(
                    id = "idProjection",
                    name = "name",
                    description = "desc",
                    state = CastDevice.CastState.Connected,
                    origin = CastDevice.CastOrigin.MediaProjection,
                )
            val routerDevice1 =
                CastDevice(
                    id = "idRouter1",
                    name = "name",
                    description = "desc",
                    state = CastDevice.CastState.Connected,
                    origin = CastDevice.CastOrigin.MediaRouter,
                )

            val routerDevice2 =
                CastDevice(
                    id = "idRouter2",
                    name = "name",
                    description = "desc",
                    state = CastDevice.CastState.Connected,
                    origin = CastDevice.CastOrigin.MediaRouter,
                )
            castController.setCastDevices(listOf(projectionDevice, routerDevice1, routerDevice2))

            assertThat(latest).containsExactly(routerDevice1, routerDevice2).inOrder()
        }

    @Test
    fun stopCasting_notifiesCastController() {
        val device =
            CastDevice(
                id = "id",
                name = "name",
                description = "desc",
                state = CastDevice.CastState.Connected,
                origin = CastDevice.CastOrigin.MediaRouter,
            )

        underTest.stopCasting(device)

        assertThat(castController.lastStoppedDevice).isEqualTo(device)
    }
}
