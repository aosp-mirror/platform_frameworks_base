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

import android.bluetooth.BluetoothDevice
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.internal.logging.uiEventLogger
import com.android.settingslib.bluetooth.CachedBluetoothDevice
import com.android.systemui.SysuiTestCase
import com.android.systemui.common.shared.model.Icon
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.haptics.slider.sliderHapticsViewModelFactory
import com.android.systemui.kosmos.testScope
import com.android.systemui.res.R
import com.android.systemui.testKosmos
import com.android.systemui.volume.data.repository.audioSharingRepository
import com.android.systemui.volume.domain.interactor.audioSharingInteractor
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock

@SmallTest
@RunWith(AndroidJUnit4::class)
class AudioSharingStreamSliderViewModelTest : SysuiTestCase() {

    private val kosmos = testKosmos()
    private val testScope = kosmos.testScope

    private lateinit var stream: AudioSharingStreamSliderViewModel

    @Before
    fun setUp() {
        stream = audioSharingStreamSliderViewModel()
    }

    private fun audioSharingStreamSliderViewModel(): AudioSharingStreamSliderViewModel {
        return AudioSharingStreamSliderViewModel(
            testScope.backgroundScope,
            context,
            kosmos.audioSharingInteractor,
            kosmos.uiEventLogger,
            kosmos.sliderHapticsViewModelFactory,
        )
    }

    @Test
    fun slider_media_inAudioSharing() =
        with(kosmos) {
            testScope.runTest {
                val audioSharingSlider by collectLastValue(stream.slider)

                val bluetoothDevice: BluetoothDevice = mock {}
                val cachedDevice: CachedBluetoothDevice = mock {
                    on { groupId }.thenReturn(123)
                    on { device }.thenReturn(bluetoothDevice)
                    on { name }.thenReturn("my headset 2")
                }
                audioSharingRepository.setSecondaryDevice(cachedDevice)

                audioSharingRepository.setInAudioSharing(true)
                audioSharingRepository.setSecondaryGroupId(123)

                runCurrent()

                assertThat(audioSharingSlider!!.label).isEqualTo("my headset 2")
                assertThat(audioSharingSlider!!.icon)
                    .isEqualTo(Icon.Resource(R.drawable.ic_volume_media_bt, null))
            }
        }
}
