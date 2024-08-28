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

import android.app.activityManager
import android.app.keyguardManager
import android.content.applicationContext
import android.content.packageManager
import android.media.AudioManager
import android.media.IVolumeController
import android.os.Handler
import android.os.looper
import android.platform.test.annotations.DisableFlags
import android.platform.test.annotations.EnableFlags
import android.platform.test.flag.junit.SetFlagsRule
import android.testing.TestableLooper
import android.view.accessibility.accessibilityManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.settingslib.volume.data.model.VolumeControllerEvent
import com.android.systemui.Flags
import com.android.systemui.SysuiTestCase
import com.android.systemui.dump.dumpManager
import com.android.systemui.keyguard.WakefulnessLifecycle
import com.android.systemui.keyguard.wakefulnessLifecycle
import com.android.systemui.kosmos.Kosmos
import com.android.systemui.kosmos.testScope
import com.android.systemui.plugins.VolumeDialogController
import com.android.systemui.testKosmos
import com.android.systemui.util.RingerModeLiveData
import com.android.systemui.util.concurrency.FakeExecutor
import com.android.systemui.util.concurrency.FakeThreadFactory
import com.android.systemui.util.time.fakeSystemClock
import com.android.systemui.volume.data.repository.audioRepository
import com.android.systemui.volume.domain.interactor.audioSharingInteractor
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@SmallTest
@TestableLooper.RunWithLooper
class VolumeDialogControllerImplTestKt : SysuiTestCase() {

    @get:Rule val setFlagsRule = SetFlagsRule()

    private val kosmos: Kosmos = testKosmos()
    private val audioManager: AudioManager = mock {}
    private val callbacks: VolumeDialogController.Callbacks = mock {}

    private lateinit var threadFactory: FakeThreadFactory
    private lateinit var underTest: VolumeDialogControllerImpl

    @Before
    fun setUp() =
        with(kosmos) {
            audioRepository.init()
            threadFactory =
                FakeThreadFactory(FakeExecutor(fakeSystemClock)).apply { setLooper(looper) }
            underTest =
                VolumeDialogControllerImpl(
                        applicationContext,
                        mock {},
                        mock {
                            on { ringerMode }.thenReturn(mock<RingerModeLiveData> {})
                            on { ringerModeInternal }.thenReturn(mock<RingerModeLiveData> {})
                        },
                        threadFactory,
                        audioManager,
                        mock {},
                        mock {},
                        mock {},
                        volumeControllerAdapter,
                        accessibilityManager,
                        packageManager,
                        wakefulnessLifecycle,
                        keyguardManager,
                        activityManager,
                        mock { on { userContext }.thenReturn(applicationContext) },
                        dumpManager,
                        audioSharingInteractor,
                        mock {},
                    )
                    .apply {
                        setEnableDialogs(true, true)
                        addCallback(callbacks, Handler(looper))
                    }
        }

    @Test
    @EnableFlags(Flags.FLAG_USE_VOLUME_CONTROLLER)
    fun useVolumeControllerEnabled_listensToVolumeController() =
        testVolumeController { stream: Int, flags: Int ->
            audioRepository.sendVolumeControllerEvent(
                VolumeControllerEvent.VolumeChanged(streamType = stream, flags = flags)
            )
        }

    @Test
    @DisableFlags(Flags.FLAG_USE_VOLUME_CONTROLLER)
    fun useVolumeControllerDisabled_listensToVolumeController() =
        testVolumeController { stream: Int, flags: Int ->
            audioManager.emitVolumeChange(stream, flags)
        }

    private fun testVolumeController(
        emitVolumeChange: suspend Kosmos.(stream: Int, flags: Int) -> Unit
    ) =
        with(kosmos) {
            testScope.runTest {
                whenever(wakefulnessLifecycle.wakefulness)
                    .thenReturn(WakefulnessLifecycle.WAKEFULNESS_AWAKE)
                underTest.setVolumeController()
                runCurrent()

                emitVolumeChange(AudioManager.STREAM_SYSTEM, AudioManager.FLAG_SHOW_UI)
                runCurrent()
                TestableLooper.get(this@VolumeDialogControllerImplTestKt).processAllMessages()

                verify(callbacks) { 1 * { onShowRequested(any(), any(), any()) } }
            }
        }

    private companion object {

        private fun AudioManager.emitVolumeChange(stream: Int, flags: Int = 0) {
            val captor = argumentCaptor<IVolumeController>()
            verify(this) { 1 * { volumeController = captor.capture() } }
            captor.firstValue.volumeChanged(stream, flags)
        }
    }
}
