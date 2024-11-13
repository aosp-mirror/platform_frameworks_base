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

package com.android.systemui.volume

import android.media.IVolumeController
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.settingslib.volume.data.model.VolumeControllerEvent
import com.android.systemui.SysuiTestCase
import com.android.systemui.kosmos.applicationCoroutineScope
import com.android.systemui.kosmos.testScope
import com.android.systemui.testKosmos
import com.android.systemui.volume.data.repository.audioRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@SmallTest
class VolumeControllerAdapterTest : SysuiTestCase() {

    private val kosmos = testKosmos()
    private val eventsFlow = MutableStateFlow<VolumeControllerEvent?>(null)
    private val underTest =
        with(kosmos) { VolumeControllerAdapter(applicationCoroutineScope, audioRepository) }

    private val volumeController = mock<IVolumeController> {}

    @Before
    fun setUp() {
        kosmos.audioRepository.init()
    }

    @Test
    fun volumeControllerEvent_volumeChanged_callsMethod() =
        testEvent(VolumeControllerEvent.VolumeChanged(3, 0)) {
            verify(volumeController) { 1 * { volumeController.volumeChanged(eq(3), eq(0)) } }
        }

    @Test
    fun volumeControllerEvent_dismiss_callsMethod() =
        testEvent(VolumeControllerEvent.Dismiss) {
            verify(volumeController) { 1 * { volumeController.dismiss() } }
        }

    @Test
    fun volumeControllerEvent_displayCsdWarning_callsMethod() =
        testEvent(VolumeControllerEvent.DisplayCsdWarning(0, 1)) {
            verify(volumeController) { 1 * { volumeController.displayCsdWarning(eq(0), eq(1)) } }
        }

    @Test
    fun volumeControllerEvent_displaySafeVolumeWarning_callsMethod() =
        testEvent(VolumeControllerEvent.DisplaySafeVolumeWarning(1)) {
            verify(volumeController) { 1 * { volumeController.displaySafeVolumeWarning(eq(1)) } }
        }

    @Test
    fun volumeControllerEvent_masterMuteChanged_callsMethod() =
        testEvent(VolumeControllerEvent.MasterMuteChanged(1)) {
            verify(volumeController) { 1 * { volumeController.masterMuteChanged(1) } }
        }

    @Test
    fun volumeControllerEvent_setA11yMode_callsMethod() =
        testEvent(VolumeControllerEvent.SetA11yMode(1)) {
            verify(volumeController) { 1 * { volumeController.setA11yMode(1) } }
        }

    @Test
    fun volumeControllerEvent_SetLayoutDirection_callsMethod() =
        testEvent(VolumeControllerEvent.SetLayoutDirection(1)) {
            verify(volumeController) { 1 * { volumeController.setLayoutDirection(eq(1)) } }
        }

    private fun testEvent(event: VolumeControllerEvent, verify: () -> Unit) =
        kosmos.testScope.runTest {
            kosmos.audioRepository.sendVolumeControllerEvent(event)
            underTest.collectToController(volumeController)

            eventsFlow.value = event
            runCurrent()

            verify()
        }
}
