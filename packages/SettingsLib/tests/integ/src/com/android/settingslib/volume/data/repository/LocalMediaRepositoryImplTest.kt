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

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.settingslib.media.LocalMediaManager
import com.android.settingslib.media.MediaDevice
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

    @Captor
    private lateinit var deviceCallbackCaptor: ArgumentCaptor<LocalMediaManager.DeviceCallback>

    private val testScope = TestScope()

    private lateinit var underTest: LocalMediaRepository

    @Before
    fun setup() {
        MockitoAnnotations.initMocks(this)

        underTest =
            LocalMediaRepositoryImpl(
                localMediaManager,
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
}
