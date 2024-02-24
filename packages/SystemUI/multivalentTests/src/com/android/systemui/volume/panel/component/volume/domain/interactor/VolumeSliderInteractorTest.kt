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

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@SmallTest
class VolumeSliderInteractorTest : SysuiTestCase() {

    private val underTest = VolumeSliderInteractor()

    @Test
    fun translateValueToVolume() {
        assertThat(underTest.translateValueToVolume(30f, volumeRange)).isEqualTo(3)
    }

    @Test
    fun processVolumeToValue_muted_zero() {
        assertThat(underTest.processVolumeToValue(3, volumeRange, null, true)).isEqualTo(0)
    }

    @Test
    fun processVolumeToValue_currentValue_currentValue() {
        assertThat(underTest.processVolumeToValue(3, volumeRange, 30f, false)).isEqualTo(30f)
    }

    @Test
    fun processVolumeToValue_currentValueDiffersVolume_returnsTranslatedVolume() {
        assertThat(underTest.processVolumeToValue(1, volumeRange, 60f, false)).isEqualTo(10f)
    }

    @Test
    fun processVolumeToValue_currentValueDiffersNotEnoughVolume_returnsTranslatedVolume() {
        assertThat(underTest.processVolumeToValue(1, volumeRange, 12f, false)).isEqualTo(12f)
    }

    private companion object {
        val volumeRange = 0..10
    }
}
