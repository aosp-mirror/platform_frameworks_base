/*
 * Copyright (C) 2024 The Android Open Source Project
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package com.android.settingslib.volume.data.repository

import android.media.MediaRoute2Info
import android.media.MediaRouter2Manager
import android.media.RoutingSessionInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.settingslib.media.LocalMediaManager
import com.android.settingslib.media.MediaDevice
import com.android.settingslib.volume.data.model.RoutingSession
import com.android.settingslib.volume.shared.FakeAudioManagerIntentsReceiver
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.Captor
import org.mockito.Mock
import org.mockito.Mockito.any
import org.mockito.Mockito.anyInt
import org.mockito.Mockito.anyString
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.mockito.MockitoAnnotations

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@SmallTest
class LocalMediaRepositoryImplTest {

    @Mock private lateinit var localMediaManager: LocalMediaManager
    @Mock private lateinit var mediaDevice1: MediaDevice
    @Mock private lateinit var mediaDevice2: MediaDevice
    @Mock private lateinit var mediaRouter2Manager: MediaRouter2Manager

    @Captor
    private lateinit var deviceCallbackCaptor: ArgumentCaptor<LocalMediaManager.DeviceCallback>

    private val intentsReceiver = FakeAudioManagerIntentsReceiver()
    private val testScope = TestScope()

    private lateinit var underTest: LocalMediaRepository

    @Before
    fun setup() {
        MockitoAnnotations.initMocks(this)

        underTest =
            LocalMediaRepositoryImpl(
                intentsReceiver,
                localMediaManager,
                mediaRouter2Manager,
                testScope.backgroundScope,
                testScope.testScheduler,
            )
    }

    @Test
    fun mediaDevices_areUpdated() {
        testScope.runTest {
            var mediaDevices: Collection<MediaDevice>? = null
            underTest.mediaDevices.onEach { mediaDevices = it }.launchIn(backgroundScope)
            runCurrent()
            verify(localMediaManager).registerCallback(deviceCallbackCaptor.capture())
            deviceCallbackCaptor.value.onDeviceListUpdate(listOf(mediaDevice1, mediaDevice2))
            runCurrent()

            assertThat(mediaDevices).hasSize(2)
            assertThat(mediaDevices).contains(mediaDevice1)
            assertThat(mediaDevices).contains(mediaDevice2)
        }
    }

    @Test
    fun deviceListUpdated_currentConnectedDeviceUpdated() {
        testScope.runTest {
            var currentConnectedDevice: MediaDevice? = null
            underTest.currentConnectedDevice
                .onEach { currentConnectedDevice = it }
                .launchIn(backgroundScope)
            runCurrent()

            `when`(localMediaManager.currentConnectedDevice).thenReturn(mediaDevice1)
            verify(localMediaManager).registerCallback(deviceCallbackCaptor.capture())
            deviceCallbackCaptor.value.onDeviceListUpdate(listOf(mediaDevice1, mediaDevice2))
            runCurrent()

            assertThat(currentConnectedDevice).isEqualTo(mediaDevice1)
        }
    }

    @Test
    fun kek() {
        testScope.runTest {
            `when`(localMediaManager.remoteRoutingSessions)
                .thenReturn(
                    listOf(
                        testRoutingSessionInfo1,
                        testRoutingSessionInfo2,
                        testRoutingSessionInfo3,
                    )
                )
            `when`(localMediaManager.shouldEnableVolumeSeekBar(any())).then {
                (it.arguments[0] as RoutingSessionInfo) == testRoutingSessionInfo1
            }
            `when`(mediaRouter2Manager.getTransferableRoutes(any<RoutingSessionInfo>())).then {
                if ((it.arguments[0] as RoutingSessionInfo) == testRoutingSessionInfo2) {
                    return@then listOf(mock(MediaRoute2Info::class.java))
                }
                emptyList<MediaRoute2Info>()
            }
            var remoteRoutingSessions: Collection<RoutingSession>? = null
            underTest.remoteRoutingSessions
                .onEach { remoteRoutingSessions = it }
                .launchIn(backgroundScope)

            runCurrent()

            assertThat(remoteRoutingSessions)
                .containsExactlyElementsIn(
                    listOf(
                        RoutingSession(
                            routingSessionInfo = testRoutingSessionInfo1,
                            isVolumeSeekBarEnabled = true,
                            isMediaOutputDisabled = true,
                        ),
                        RoutingSession(
                            routingSessionInfo = testRoutingSessionInfo2,
                            isVolumeSeekBarEnabled = false,
                            isMediaOutputDisabled = false,
                        ),
                        RoutingSession(
                            routingSessionInfo = testRoutingSessionInfo3,
                            isVolumeSeekBarEnabled = false,
                            isMediaOutputDisabled = true,
                        )
                    )
                )
        }
    }

    @Test
    fun adjustSessionVolume_adjusts() {
        testScope.runTest {
            var volume = 0
            `when`(localMediaManager.adjustSessionVolume(anyString(), anyInt())).then {
                volume = it.arguments[1] as Int
                Unit
            }

            underTest.adjustSessionVolume("test_session", 10)

            assertThat(volume).isEqualTo(10)
        }
    }

    private companion object {
        val testRoutingSessionInfo1 =
            RoutingSessionInfo.Builder("id_1", "test.pkg.1").addSelectedRoute("route_1").build()
        val testRoutingSessionInfo2 =
            RoutingSessionInfo.Builder("id_2", "test.pkg.2").addSelectedRoute("route_2").build()
        val testRoutingSessionInfo3 =
            RoutingSessionInfo.Builder("id_3", "test.pkg.3").addSelectedRoute("route_3").build()
    }
}
