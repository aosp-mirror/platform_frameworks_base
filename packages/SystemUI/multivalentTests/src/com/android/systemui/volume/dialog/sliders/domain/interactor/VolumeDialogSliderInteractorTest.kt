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

package com.android.systemui.volume.dialog.sliders.domain.interactor

import android.media.AudioManager
import android.service.notification.ZenPolicy
import android.testing.TestableLooper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.settingslib.notification.modes.TestModeBuilder
import com.android.systemui.SysuiTestCase
import com.android.systemui.kosmos.collectLastValue
import com.android.systemui.kosmos.runTest
import com.android.systemui.kosmos.useUnconfinedTestDispatcher
import com.android.systemui.plugins.VolumeDialogController
import com.android.systemui.plugins.fakeVolumeDialogController
import com.android.systemui.statusbar.policy.data.repository.zenModeRepository
import com.android.systemui.testKosmos
import com.android.systemui.volume.dialog.sliders.domain.model.VolumeDialogSliderType
import com.android.systemui.volume.dialog.sliders.domain.model.volumeDialogSliderType
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
@TestableLooper.RunWithLooper
class VolumeDialogSliderInteractorTest : SysuiTestCase() {

    private val kosmos =
        testKosmos().apply {
            useUnconfinedTestDispatcher()
            zenModeRepository.addMode(
                TestModeBuilder()
                    .setName("Blocks media, Active")
                    .setZenPolicy(ZenPolicy.Builder().allowMedia(false).build())
                    .setActive(true)
                    .build()
            )
        }

    private val underTest: VolumeDialogSliderInteractor by lazy {
        kosmos.volumeDialogSliderInteractor
    }

    @Test
    fun settingStreamVolume_setsActiveStream() =
        kosmos.runTest {
            // initialize the stream model
            fakeVolumeDialogController.setStreamVolume(volumeDialogSliderType.audioStream, 0)

            val sliderModel by collectLastValue(underTest.slider)
            underTest.setStreamVolume(1)

            assertThat(sliderModel!!.isActive).isTrue()
        }

    @Test
    fun streamVolumeIs_minMaxAreEnforced() =
        kosmos.runTest {
            fakeVolumeDialogController.updateState {
                states.put(
                    volumeDialogSliderType.audioStream,
                    VolumeDialogController.StreamState().apply {
                        levelMin = 0
                        level = 2
                        levelMax = 1
                    },
                )
            }

            val sliderModel by collectLastValue(underTest.slider)

            assertThat(sliderModel!!.level).isEqualTo(1)
        }

    @Test
    fun streamCantBeBlockedByZenMode_isDisabledByZenMode_false() =
        kosmos.runTest {
            volumeDialogSliderType = VolumeDialogSliderType.Stream(AudioManager.STREAM_VOICE_CALL)

            val isDisabledByZenMode by collectLastValue(underTest.isDisabledByZenMode)

            assertThat(isDisabledByZenMode).isFalse()
        }

    @Test
    fun remoteMediaStream_zenModeRestrictive_IsNotDisabledByZenMode() =
        kosmos.runTest {
            volumeDialogSliderType = VolumeDialogSliderType.RemoteMediaStream(0)

            val isDisabledByZenMode by collectLastValue(underTest.isDisabledByZenMode)

            assertThat(isDisabledByZenMode).isFalse()
        }

    @Test
    fun audioSharingStream_zenModeRestrictive_IsNotDisabledByZenMode() =
        kosmos.runTest {
            volumeDialogSliderType = VolumeDialogSliderType.AudioSharingStream(0)

            val isDisabledByZenMode by collectLastValue(underTest.isDisabledByZenMode)

            assertThat(isDisabledByZenMode).isFalse()
        }

    @Test
    fun streamBlockedByZenMode_isDisabledByZenMode_true() =
        kosmos.runTest {
            volumeDialogSliderType = VolumeDialogSliderType.Stream(AudioManager.STREAM_MUSIC)

            val isDisabledByZenMode by collectLastValue(underTest.isDisabledByZenMode)

            assertThat(isDisabledByZenMode).isTrue()
        }
}
