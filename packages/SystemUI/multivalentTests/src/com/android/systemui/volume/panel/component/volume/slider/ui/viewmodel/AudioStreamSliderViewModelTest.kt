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

package com.android.systemui.volume.panel.component.volume.slider.ui.viewmodel

import android.app.Flags
import android.app.NotificationManager.INTERRUPTION_FILTER_NONE
import android.media.AudioManager
import android.platform.test.annotations.EnableFlags
import android.service.notification.ZenPolicy
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.internal.logging.uiEventLogger
import com.android.settingslib.notification.modes.TestModeBuilder
import com.android.settingslib.volume.shared.model.AudioStream
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.haptics.slider.sliderHapticsViewModelFactory
import com.android.systemui.kosmos.testScope
import com.android.systemui.statusbar.policy.data.repository.fakeZenModeRepository
import com.android.systemui.statusbar.policy.domain.interactor.zenModeInteractor
import com.android.systemui.testKosmos
import com.android.systemui.volume.domain.interactor.audioVolumeInteractor
import com.android.systemui.volume.shared.volumePanelLogger
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
class AudioStreamSliderViewModelTest : SysuiTestCase() {

    private val kosmos = testKosmos()
    private val testScope = kosmos.testScope
    private val zenModeRepository = kosmos.fakeZenModeRepository

    private lateinit var mediaStream: AudioStreamSliderViewModel
    private lateinit var alarmsStream: AudioStreamSliderViewModel
    private lateinit var notificationStream: AudioStreamSliderViewModel
    private lateinit var otherStream: AudioStreamSliderViewModel

    @Before
    fun setUp() {
        mediaStream = audioStreamSliderViewModel(AudioManager.STREAM_MUSIC)
        alarmsStream = audioStreamSliderViewModel(AudioManager.STREAM_ALARM)
        notificationStream = audioStreamSliderViewModel(AudioManager.STREAM_NOTIFICATION)
        otherStream = audioStreamSliderViewModel(AudioManager.STREAM_VOICE_CALL)
    }

    private fun audioStreamSliderViewModel(stream: Int): AudioStreamSliderViewModel {
        return AudioStreamSliderViewModel(
            AudioStreamSliderViewModel.FactoryAudioStreamWrapper(AudioStream(stream)),
            testScope.backgroundScope,
            context,
            kosmos.audioVolumeInteractor,
            kosmos.zenModeInteractor,
            kosmos.uiEventLogger,
            kosmos.volumePanelLogger,
            kosmos.sliderHapticsViewModelFactory,
        )
    }

    @Test
    @EnableFlags(Flags.FLAG_MODES_UI, Flags.FLAG_MODES_UI_ICONS)
    fun slider_media_hasDisabledByModesText() =
        testScope.runTest {
            val mediaSlider by collectLastValue(mediaStream.slider)

            zenModeRepository.addMode(
                TestModeBuilder()
                    .setName("Media is ok")
                    .setZenPolicy(ZenPolicy.Builder().allowAllSounds().build())
                    .setActive(true)
                    .build()
            )
            zenModeRepository.addMode(
                TestModeBuilder()
                    .setName("No media plz")
                    .setZenPolicy(ZenPolicy.Builder().allowMedia(false).build())
                    .setActive(true)
                    .build()
            )
            runCurrent()

            assertThat(mediaSlider!!.disabledMessage)
                .isEqualTo("Unavailable because No media plz is on")

            zenModeRepository.clearModes()
            runCurrent()

            assertThat(mediaSlider!!.disabledMessage).isEqualTo("Unavailable")
        }

    @Test
    @EnableFlags(Flags.FLAG_MODES_UI, Flags.FLAG_MODES_UI_ICONS)
    fun slider_alarms_hasDisabledByModesText() =
        testScope.runTest {
            val alarmsSlider by collectLastValue(alarmsStream.slider)

            zenModeRepository.addMode(
                TestModeBuilder()
                    .setName("Alarms are ok")
                    .setZenPolicy(ZenPolicy.Builder().allowAllSounds().build())
                    .setActive(true)
                    .build()
            )
            zenModeRepository.addMode(
                TestModeBuilder()
                    .setName("Zzzzz")
                    .setZenPolicy(ZenPolicy.Builder().allowAlarms(false).build())
                    .setActive(true)
                    .build()
            )
            runCurrent()

            assertThat(alarmsSlider!!.disabledMessage).isEqualTo("Unavailable because Zzzzz is on")

            zenModeRepository.clearModes()
            runCurrent()

            assertThat(alarmsSlider!!.disabledMessage).isEqualTo("Unavailable")
        }

    @Test
    @EnableFlags(Flags.FLAG_MODES_UI, Flags.FLAG_MODES_UI_ICONS)
    fun slider_other_hasDisabledByModesText() =
        testScope.runTest {
            val otherSlider by collectLastValue(otherStream.slider)

            zenModeRepository.addMode(
                TestModeBuilder()
                    .setName("Everything blocked")
                    .setInterruptionFilter(INTERRUPTION_FILTER_NONE)
                    .setActive(true)
                    .build()
            )
            runCurrent()

            assertThat(otherSlider!!.disabledMessage)
                .isEqualTo("Unavailable because Everything blocked is on")

            zenModeRepository.clearModes()
            runCurrent()

            assertThat(otherSlider!!.disabledMessage).isEqualTo("Unavailable")
        }

    @Test
    @EnableFlags(Flags.FLAG_MODES_UI, Flags.FLAG_MODES_UI_ICONS)
    fun slider_notification_hasSpecialDisabledText() =
        testScope.runTest {
            val notificationSlider by collectLastValue(notificationStream.slider)
            runCurrent()

            assertThat(notificationSlider!!.disabledMessage)
                .isEqualTo("Unavailable because ring is muted")
        }
}
