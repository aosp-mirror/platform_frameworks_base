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

package com.android.systemui.volume.panel.component.mediaoutput.domain.interactor

import android.graphics.drawable.TestStubDrawable
import android.media.AudioManager
import android.testing.TestableLooper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.settingslib.R
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.kosmos.testScope
import com.android.systemui.testKosmos
import com.android.systemui.volume.data.repository.TestAudioDevicesFactory
import com.android.systemui.volume.data.repository.audioRepository
import com.android.systemui.volume.data.repository.audioSharingRepository
import com.android.systemui.volume.domain.model.AudioOutputDevice
import com.android.systemui.volume.localMediaController
import com.android.systemui.volume.localMediaRepository
import com.android.systemui.volume.mediaControllerRepository
import com.android.systemui.volume.panel.component.mediaoutput.domain.model.MediaOutputComponentModel
import com.android.systemui.volume.panel.shared.model.filterData
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

private const val builtInDeviceName = "This phone"

@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
@RunWith(AndroidJUnit4::class)
@TestableLooper.RunWithLooper(setAsMainLooper = true)
class MediaOutputComponentInteractorTest : SysuiTestCase() {

    private val kosmos = testKosmos()

    private lateinit var underTest: MediaOutputComponentInteractor

    @Before
    fun setUp() =
        with(kosmos) {
            audioRepository.setMode(AudioManager.MODE_NORMAL)
            localMediaRepository.updateCurrentConnectedDevice(
                TestMediaDevicesFactory.builtInMediaDevice(deviceIcon = testIcon)
            )

            with(context.orCreateTestableResources) {
                addOverride(R.drawable.ic_smartphone, testIcon)
                addOverride(R.drawable.ic_media_tablet, testIcon)

                addOverride(R.string.media_transfer_this_device_name_tv, builtInDeviceName)
                addOverride(R.string.media_transfer_this_device_name_tablet, builtInDeviceName)
                addOverride(R.string.media_transfer_this_device_name, builtInDeviceName)
            }

            underTest = mediaOutputComponentInteractor
        }

    @Test
    fun inCall_stateIs_Calling() =
        with(kosmos) {
            testScope.runTest {
                with(audioRepository) {
                    setMode(AudioManager.MODE_IN_CALL)
                    setCommunicationDevice(TestAudioDevicesFactory.builtInDevice())
                }

                val model by collectLastValue(underTest.mediaOutputModel.filterData())
                runCurrent()

                assertThat(model)
                    .isEqualTo(
                        MediaOutputComponentModel.Calling(
                            device = AudioOutputDevice.BuiltIn(builtInDeviceName, testIcon),
                            isInAudioSharing = false,
                            canOpenAudioSwitcher = false,
                        )
                    )
            }
        }

    @Test
    fun hasSession_stateIs_MediaSession() =
        with(kosmos) {
            testScope.runTest {
                localMediaRepository.updateCurrentConnectedDevice(
                    TestMediaDevicesFactory.builtInMediaDevice()
                )
                mediaControllerRepository.setActiveSessions(listOf(localMediaController))

                val model by collectLastValue(underTest.mediaOutputModel.filterData())
                runCurrent()

                with(model as MediaOutputComponentModel.MediaSession) {
                    assertThat(session.appLabel).isEqualTo("local_media_controller_label")
                    assertThat(session.packageName).isEqualTo("local.test.pkg")
                    assertThat(session.canAdjustVolume).isTrue()
                    assertThat(device)
                        .isEqualTo(AudioOutputDevice.BuiltIn("built_in_media", testIcon))
                    assertThat(isInAudioSharing).isFalse()
                    assertThat(canOpenAudioSwitcher).isTrue()
                }
            }
        }

    @Test
    fun noMediaOrCall_stateIs_Idle() =
        with(kosmos) {
            testScope.runTest {
                audioSharingRepository.setInAudioSharing(true)

                val model by collectLastValue(underTest.mediaOutputModel.filterData())
                runCurrent()

                assertThat(model)
                    .isEqualTo(
                        MediaOutputComponentModel.Idle(
                            device = AudioOutputDevice.BuiltIn("built_in_media", testIcon),
                            isInAudioSharing = true,
                            canOpenAudioSwitcher = false,
                        )
                    )
            }
        }

    private companion object {
        val testIcon = TestStubDrawable()
    }
}
