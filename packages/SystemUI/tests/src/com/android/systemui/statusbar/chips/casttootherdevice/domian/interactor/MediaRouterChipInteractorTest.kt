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

package com.android.systemui.statusbar.chips.casttootherdevice.domian.interactor

import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.kosmos.Kosmos
import com.android.systemui.kosmos.testScope
import com.android.systemui.mediarouter.data.repository.fakeMediaRouterRepository
import com.android.systemui.statusbar.chips.casttootherdevice.domain.interactor.mediaRouterChipInteractor
import com.android.systemui.statusbar.chips.casttootherdevice.domain.model.MediaRouterCastModel
import com.android.systemui.statusbar.policy.CastDevice
import com.google.common.truth.Truth.assertThat
import kotlin.test.Test
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

@SmallTest
@OptIn(ExperimentalCoroutinesApi::class)
class MediaRouterChipInteractorTest : SysuiTestCase() {
    val kosmos = Kosmos()
    val testScope = kosmos.testScope
    val mediaRouterRepository = kosmos.fakeMediaRouterRepository

    val underTest = kosmos.mediaRouterChipInteractor

    @Test
    fun mediaRouterCastingState_noDevices_doingNothing() =
        testScope.runTest {
            val latest by collectLastValue(underTest.mediaRouterCastingState)

            mediaRouterRepository.castDevices.value = emptyList()

            assertThat(latest).isEqualTo(MediaRouterCastModel.DoingNothing)
        }

    @Test
    fun mediaRouterCastingState_disconnectedDevice_doingNothing() =
        testScope.runTest {
            val latest by collectLastValue(underTest.mediaRouterCastingState)

            mediaRouterRepository.castDevices.value =
                listOf(
                    CastDevice(
                        state = CastDevice.CastState.Disconnected,
                        id = "id",
                        name = "name",
                        description = "desc",
                        origin = CastDevice.CastOrigin.MediaRouter,
                    )
                )

            assertThat(latest).isEqualTo(MediaRouterCastModel.DoingNothing)
        }

    @Test
    fun mediaRouterCastingState_connectingDevice_casting_withName() =
        testScope.runTest {
            val latest by collectLastValue(underTest.mediaRouterCastingState)

            mediaRouterRepository.castDevices.value =
                listOf(
                    CastDevice(
                        state = CastDevice.CastState.Connecting,
                        id = "id",
                        name = "My Favorite Device",
                        description = "desc",
                        origin = CastDevice.CastOrigin.MediaRouter,
                    )
                )

            assertThat(latest)
                .isEqualTo(MediaRouterCastModel.Casting(deviceName = "My Favorite Device"))
        }

    @Test
    fun mediaRouterCastingState_connectedDevice_casting_withName() =
        testScope.runTest {
            val latest by collectLastValue(underTest.mediaRouterCastingState)

            mediaRouterRepository.castDevices.value =
                listOf(
                    CastDevice(
                        state = CastDevice.CastState.Connected,
                        id = "id",
                        name = "My Second Favorite Device",
                        description = "desc",
                        origin = CastDevice.CastOrigin.MediaRouter,
                    )
                )

            assertThat(latest)
                .isEqualTo(MediaRouterCastModel.Casting(deviceName = "My Second Favorite Device"))
        }

    @Test
    fun stopCasting_noDevices_doesNothing() =
        testScope.runTest {
            collectLastValue(underTest.mediaRouterCastingState)

            mediaRouterRepository.castDevices.value = emptyList()
            // Let the interactor catch up to the repo value
            runCurrent()

            underTest.stopCasting()

            assertThat(mediaRouterRepository.lastStoppedDevice).isNull()
        }

    @Test
    fun stopCasting_disconnectedDevice_doesNothing() =
        testScope.runTest {
            collectLastValue(underTest.mediaRouterCastingState)

            mediaRouterRepository.castDevices.value =
                listOf(
                    CastDevice(
                        state = CastDevice.CastState.Disconnected,
                        id = "id",
                        name = "name",
                        description = "desc",
                        origin = CastDevice.CastOrigin.MediaRouter,
                    )
                )
            // Let the interactor catch up to the repo value
            runCurrent()

            underTest.stopCasting()

            assertThat(mediaRouterRepository.lastStoppedDevice).isNull()
        }

    @Test
    fun stopCasting_connectingDevice_notifiesRepo() =
        testScope.runTest {
            collectLastValue(underTest.mediaRouterCastingState)

            val device =
                CastDevice(
                    state = CastDevice.CastState.Connecting,
                    id = "id",
                    name = "name",
                    description = "desc",
                    origin = CastDevice.CastOrigin.MediaRouter,
                )
            mediaRouterRepository.castDevices.value = listOf(device)
            // Let the interactor catch up to the repo value
            runCurrent()

            underTest.stopCasting()

            assertThat(mediaRouterRepository.lastStoppedDevice).isEqualTo(device)
        }

    @Test
    fun stopCasting_connectedDevice_notifiesRepo() =
        testScope.runTest {
            collectLastValue(underTest.mediaRouterCastingState)

            val device =
                CastDevice(
                    state = CastDevice.CastState.Connected,
                    id = "id",
                    name = "name",
                    description = "desc",
                    origin = CastDevice.CastOrigin.MediaRouter,
                )
            mediaRouterRepository.castDevices.value = listOf(device)
            // Let the interactor catch up to the repo value
            runCurrent()

            underTest.stopCasting()

            assertThat(mediaRouterRepository.lastStoppedDevice).isEqualTo(device)
        }

    @Test
    fun stopCasting_multipleConnectedDevices_notifiesRepoOfFirst() =
        testScope.runTest {
            collectLastValue(underTest.mediaRouterCastingState)

            val device1 =
                CastDevice(
                    state = CastDevice.CastState.Connected,
                    id = "id1",
                    name = "name",
                    description = "desc",
                    origin = CastDevice.CastOrigin.MediaRouter,
                )
            val device2 =
                CastDevice(
                    state = CastDevice.CastState.Connected,
                    id = "id2",
                    name = "name",
                    description = "desc",
                    origin = CastDevice.CastOrigin.MediaRouter,
                )
            mediaRouterRepository.castDevices.value = listOf(device1, device2)
            // Let the interactor catch up to the repo value
            runCurrent()

            underTest.stopCasting()

            assertThat(mediaRouterRepository.lastStoppedDevice).isEqualTo(device1)
        }
}
