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

package com.android.settingslib.media.data.repository

import android.media.AudioManager
import android.media.IVolumeController
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
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
import org.mockito.MockitoAnnotations

@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
@RunWith(AndroidJUnit4::class)
class AudioManagerVolumeControllerExtTest {

    private val testScope = TestScope()

    @Captor private lateinit var volumeControllerCaptor: ArgumentCaptor<IVolumeController>
    @Mock private lateinit var audioManager: AudioManager

    @Before
    fun setup() {
        MockitoAnnotations.initMocks(this)
    }

    @Test
    fun displaySafeVolumeWarning_emitsEvent() =
        testEvent(VolumeControllerEvent.DisplaySafeVolumeWarning(1)) { displaySafeVolumeWarning(1) }

    @Test
    fun volumeChanged_emitsEvent() =
        testEvent(VolumeControllerEvent.VolumeChanged(1, 2)) { volumeChanged(1, 2) }

    @Test
    fun masterMuteChanged_emitsEvent() =
        testEvent(VolumeControllerEvent.MasterMuteChanged(1)) { masterMuteChanged(1) }

    @Test
    fun setLayoutDirection_emitsEvent() =
        testEvent(VolumeControllerEvent.SetLayoutDirection(1)) { setLayoutDirection(1) }

    @Test
    fun setA11yMode_emitsEvent() =
        testEvent(VolumeControllerEvent.SetA11yMode(1)) { setA11yMode(1) }

    @Test
    fun displayCsdWarning_emitsEvent() =
        testEvent(VolumeControllerEvent.DisplayCsdWarning(1, 2)) { displayCsdWarning(1, 2) }

    @Test fun dismiss_emitsEvent() = testEvent(VolumeControllerEvent.Dismiss) { dismiss() }

    private fun testEvent(
        expectedEvent: VolumeControllerEvent,
        emit: IVolumeController.() -> Unit,
    ) =
        testScope.runTest {
            var event: VolumeControllerEvent? = null
            audioManager.volumeControllerEvents().onEach { event = it }.launchIn(backgroundScope)
            runCurrent()
            verify(audioManager).volumeController = volumeControllerCaptor.capture()

            volumeControllerCaptor.value.emit()
            runCurrent()

            assertThat(event).isEqualTo(expectedEvent)
        }
}
