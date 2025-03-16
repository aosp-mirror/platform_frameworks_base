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

package com.android.systemui.volume.panel.component.volume.domain.interactor

import android.media.AudioManager
import android.platform.test.annotations.EnableFlags
import android.testing.TestableLooper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.settingslib.volume.shared.model.AudioStream
import com.android.systemui.Flags
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.kosmos.Kosmos
import com.android.systemui.kosmos.testScope
import com.android.systemui.testKosmos
import com.android.systemui.volume.data.repository.audioRepository
import com.android.systemui.volume.data.repository.audioSystemRepository
import com.android.systemui.volume.panel.component.volume.domain.model.SliderType
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
@RunWith(AndroidJUnit4::class)
@TestableLooper.RunWithLooper(setAsMainLooper = true)
class AudioSlidersInteractorTest : SysuiTestCase() {

    private val kosmos = testKosmos()

    private lateinit var underTest: AudioSlidersInteractor

    @Before
    fun setUp() =
        with(kosmos) {
            audioRepository.setMode(AudioManager.MODE_NORMAL)
            underTest = audioSlidersInteractor
        }

    @Test
    fun shouldAddAllStreams_notInCall() =
        with(kosmos) {
            testScope.runTest {
                val sliders by collectLastValue(underTest.volumePanelSliders)
                runCurrent()

                assertThat(sliders).isEqualTo(
                    mutableListOf(
                        AudioManager.STREAM_MUSIC,
                        AudioManager.STREAM_VOICE_CALL,
                        AudioManager.STREAM_RING,
                        AudioManager.STREAM_NOTIFICATION,
                        AudioManager.STREAM_ALARM
                    ).map { SliderType.Stream(AudioStream(it)) })
            }
        }

    @Test
    fun shouldAddAllStreams_inCall() =
        with(kosmos) {
            testScope.runTest {
                audioRepository.setMode(AudioManager.MODE_IN_CALL)

                val sliders by collectLastValue(underTest.volumePanelSliders)
                runCurrent()

                // Call stream is before music stream while in call.
                assertThat(sliders).isEqualTo(
                    mutableListOf(
                        AudioManager.STREAM_VOICE_CALL,
                        AudioManager.STREAM_MUSIC,
                        AudioManager.STREAM_RING,
                        AudioManager.STREAM_NOTIFICATION,
                        AudioManager.STREAM_ALARM
                    ).map { SliderType.Stream(AudioStream(it)) })
            }
        }


    @Test
    @EnableFlags(Flags.FLAG_ONLY_SHOW_MEDIA_STREAM_SLIDER_IN_SINGLE_VOLUME_MODE)
    fun shouldAddMusicStreamOnly_singleVolumeMode() =
        with(kosmos) {
            testScope.runTest {
                audioSystemRepository.setIsSingleVolume(true)

                val sliders by collectLastValue(underTest.volumePanelSliders)
                runCurrent()

                assertThat(sliders).isEqualTo(
                    mutableListOf(SliderType.Stream(AudioStream(AudioManager.STREAM_MUSIC))))
            }
        }
}
