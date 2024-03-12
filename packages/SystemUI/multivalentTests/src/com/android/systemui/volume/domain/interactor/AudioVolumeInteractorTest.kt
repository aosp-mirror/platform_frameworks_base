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

package com.android.systemui.volume.domain.interactor

import android.media.AudioManager
import android.provider.Settings
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.settingslib.statusbar.notification.data.model.ZenMode
import com.android.settingslib.statusbar.notification.data.repository.updateNotificationPolicy
import com.android.settingslib.volume.domain.interactor.AudioVolumeInteractor
import com.android.settingslib.volume.shared.model.AudioStream
import com.android.settingslib.volume.shared.model.RingerMode
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.kosmos.testScope
import com.android.systemui.statusbar.notification.domain.interactor.notificationsSoundPolicyInteractor
import com.android.systemui.statusbar.notification.domain.interactor.notificationsSoundPolicyRepository
import com.android.systemui.testKosmos
import com.android.systemui.volume.audioRepository
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@SmallTest
class AudioVolumeInteractorTest : SysuiTestCase() {

    private val kosmos = testKosmos()

    private lateinit var underTest: AudioVolumeInteractor

    @Before
    fun setup() {
        with(kosmos) {
            underTest = AudioVolumeInteractor(audioRepository, notificationsSoundPolicyInteractor)

            audioRepository.setRingerMode(RingerMode(AudioManager.RINGER_MODE_NORMAL))

            notificationsSoundPolicyRepository.updateNotificationPolicy()
            notificationsSoundPolicyRepository.updateZenMode(ZenMode(Settings.Global.ZEN_MODE_OFF))
        }
    }

    @Test
    fun setMuted_mutesStream() {
        with(kosmos) {
            testScope.runTest {
                val model by collectLastValue(underTest.getAudioStream(audioStream))

                underTest.setMuted(audioStream, false)
                runCurrent()
                assertThat(model!!.isMuted).isFalse()

                underTest.setMuted(audioStream, true)
                runCurrent()
                assertThat(model!!.isMuted).isTrue()
            }
        }
    }

    @Test
    fun setVolume_changesVolume() {
        with(kosmos) {
            testScope.runTest {
                val model by collectLastValue(underTest.getAudioStream(audioStream))

                underTest.setVolume(audioStream, 10)
                runCurrent()
                assertThat(model!!.volume).isEqualTo(10)

                underTest.setVolume(audioStream, 20)
                runCurrent()
                assertThat(model!!.volume).isEqualTo(20)
            }
        }
    }

    @Test
    fun ringMuted_notificationVolume_cantChange() {
        with(kosmos) {
            testScope.runTest {
                val canChangeVolume by
                    collectLastValue(
                        underTest.canChangeVolume(AudioStream(AudioManager.STREAM_NOTIFICATION))
                    )

                underTest.setMuted(AudioStream(AudioManager.STREAM_RING), true)
                runCurrent()

                assertThat(canChangeVolume).isFalse()
            }
        }
    }

    @Test
    fun zenMuted_cantChange() {
        with(kosmos) {
            testScope.runTest {
                notificationsSoundPolicyRepository.updateNotificationPolicy()
                notificationsSoundPolicyRepository.updateZenMode(
                    ZenMode(Settings.Global.ZEN_MODE_NO_INTERRUPTIONS)
                )

                val canChangeVolume by
                    collectLastValue(
                        underTest.canChangeVolume(AudioStream(AudioManager.STREAM_NOTIFICATION))
                    )

                underTest.setMuted(AudioStream(AudioManager.STREAM_RING), true)
                runCurrent()

                assertThat(canChangeVolume).isFalse()
            }
        }
    }

    @Test
    fun streamIsMuted_getStream_volumeZero() {
        with(kosmos) {
            testScope.runTest {
                val model by collectLastValue(underTest.getAudioStream(audioStream))

                underTest.setMuted(audioStream, true)
                runCurrent()

                assertThat(model!!.volume).isEqualTo(0)
            }
        }
    }

    @Test
    fun streamIsZenMuted_getStream_lastAudibleVolume() {
        with(kosmos) {
            testScope.runTest {
                audioRepository.setLastAudibleVolume(audioStream, 30)
                notificationsSoundPolicyRepository.updateZenMode(
                    ZenMode(Settings.Global.ZEN_MODE_NO_INTERRUPTIONS)
                )

                val model by collectLastValue(underTest.getAudioStream(audioStream))
                runCurrent()

                assertThat(model!!.volume).isEqualTo(30)
            }
        }
    }

    @Test
    fun ringerModeVibrateAndMuted_getNotificationStream_volumeIsZero() {
        with(kosmos) {
            testScope.runTest {
                audioRepository.setRingerMode(RingerMode(AudioManager.RINGER_MODE_VIBRATE))
                underTest.setMuted(AudioStream(AudioManager.STREAM_NOTIFICATION), true)

                val model by
                    collectLastValue(
                        underTest.getAudioStream(AudioStream(AudioManager.STREAM_NOTIFICATION))
                    )
                runCurrent()

                assertThat(model!!.volume).isEqualTo(0)
            }
        }
    }

    @Test
    fun ringerModeVibrate_getRingerStream_volumeIsZero() {
        with(kosmos) {
            testScope.runTest {
                audioRepository.setRingerMode(RingerMode(AudioManager.RINGER_MODE_VIBRATE))

                val model by
                    collectLastValue(
                        underTest.getAudioStream(AudioStream(AudioManager.STREAM_RING))
                    )
                runCurrent()

                assertThat(model!!.volume).isEqualTo(0)
            }
        }
    }

    private companion object {
        val audioStream = AudioStream(AudioManager.STREAM_SYSTEM)
    }
}
