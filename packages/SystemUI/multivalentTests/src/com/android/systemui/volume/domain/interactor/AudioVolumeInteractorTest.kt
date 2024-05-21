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
import com.android.systemui.volume.data.repository.audioRepository
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@SmallTest
class AudioVolumeInteractorTest : SysuiTestCase() {

    private val kosmos = testKosmos()

    private val underTest: AudioVolumeInteractor =
        with(kosmos) { AudioVolumeInteractor(audioRepository, notificationsSoundPolicyInteractor) }

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
    fun streamIsMuted_getStream_volumeMin() {
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
    fun ringerModeVibrateAndMuted_getNotificationStream_volumeIsMin() {
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
    fun ringerModeVibrate_getRingerStream_volumeIsMin() {
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

    @Test
    fun streamNotAffectedByMute_isNotMutable() {
        with(kosmos) {
            testScope.runTest {
                val audioStreamModel by collectLastValue(underTest.getAudioStream(audioStream))
                audioRepository.setAudioStreamModel(
                    audioStreamModel!!.copy(isAffectedByMute = false)
                )

                assertThat(audioStreamModel!!.isAffectedByMute).isFalse()
            }
        }
    }

    @Test
    fun muteRingerStream_ringerMode_vibrate() {
        with(kosmos) {
            testScope.runTest {
                val ringerMode by collectLastValue(audioRepository.ringerMode)
                underTest.setMuted(AudioStream(AudioManager.STREAM_RING), true)

                assertThat(ringerMode).isEqualTo(RingerMode(AudioManager.RINGER_MODE_VIBRATE))
            }
        }
    }

    @Test
    fun unMuteRingerStream_ringerMode_normal() {
        with(kosmos) {
            testScope.runTest {
                val ringerMode by collectLastValue(audioRepository.ringerMode)
                underTest.setMuted(AudioStream(AudioManager.STREAM_RING), false)

                assertThat(ringerMode).isEqualTo(RingerMode(AudioManager.RINGER_MODE_NORMAL))
            }
        }
    }

    @Test
    fun testReducingVolumeToMin_mutes() =
        with(kosmos) {
            testScope.runTest {
                val audioStreamModel by
                    collectLastValue(audioRepository.getAudioStream(audioStream))
                underTest.setVolume(audioStream, audioStreamModel!!.maxVolume)
                runCurrent()

                underTest.setVolume(audioStream, audioStreamModel!!.minVolume)
                runCurrent()

                assertThat(audioStreamModel!!.isMuted).isTrue()
            }
        }

    @Test
    fun testIncreasingVolumeFromMin_unmutes() =
        with(kosmos) {
            testScope.runTest {
                val audioStreamModel by
                    collectLastValue(audioRepository.getAudioStream(audioStream))
                audioRepository.setMuted(audioStream, true)
                audioRepository.setVolume(audioStream, audioStreamModel!!.minVolume)
                runCurrent()

                underTest.setVolume(audioStream, audioStreamModel!!.maxVolume)
                runCurrent()

                assertThat(audioStreamModel!!.isMuted).isFalse()
            }
        }

    @Test
    fun testUnmutingMinVolume_increasesVolume() =
        with(kosmos) {
            testScope.runTest {
                val audioStreamModel by
                    collectLastValue(audioRepository.getAudioStream(audioStream))
                audioRepository.setMuted(audioStream, true)
                audioRepository.setVolume(audioStream, audioStreamModel!!.minVolume)
                runCurrent()

                underTest.setMuted(audioStream, false)
                runCurrent()

                assertThat(audioStreamModel!!.volume).isGreaterThan(audioStreamModel!!.minVolume)
            }
        }

    private companion object {
        val audioStream = AudioStream(AudioManager.STREAM_SYSTEM)
    }
}
