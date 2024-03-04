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
        assertThat(underTest.processVolumeToValue(3, volumeRange, true)).isEqualTo(0)
    }

    @Test
    fun processVolumeToValue_returnsTranslatedVolume() {
        assertThat(underTest.processVolumeToValue(2, volumeRange, false)).isEqualTo(20f)
    }

    private companion object {
        val volumeRange = 0..10
    }
}
