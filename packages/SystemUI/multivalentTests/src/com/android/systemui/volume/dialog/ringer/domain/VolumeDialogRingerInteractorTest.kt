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

package com.android.systemui.volume.dialog.ringer.domain

import android.media.AudioManager.RINGER_MODE_NORMAL
import android.media.AudioManager.RINGER_MODE_SILENT
import android.media.AudioManager.RINGER_MODE_VIBRATE
import android.media.AudioManager.STREAM_RING
import android.testing.TestableLooper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.settingslib.volume.shared.model.RingerMode
import com.android.systemui.SysuiTestCase
import com.android.systemui.kosmos.collectLastValue
import com.android.systemui.kosmos.runTest
import com.android.systemui.kosmos.useUnconfinedTestDispatcher
import com.android.systemui.plugins.fakeVolumeDialogController
import com.android.systemui.testKosmos
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
@TestableLooper.RunWithLooper
class VolumeDialogRingerInteractorTest : SysuiTestCase() {

    private val kosmos = testKosmos().useUnconfinedTestDispatcher()
    private val controller = kosmos.fakeVolumeDialogController

    private lateinit var underTest: VolumeDialogRingerInteractor

    @Before
    fun setUp() {
        underTest = kosmos.volumeDialogRingerInteractor
        controller.setStreamVolume(STREAM_RING, 50)
    }

    @Test
    fun setRingerMode_normal() =
        kosmos.runTest {
            val ringerModel by collectLastValue(underTest.ringerModel)

            underTest.setRingerMode(RingerMode(RINGER_MODE_NORMAL))
            controller.getState()

            assertThat(ringerModel).isNotNull()
            assertThat(ringerModel?.currentRingerMode).isEqualTo(RingerMode(RINGER_MODE_NORMAL))
        }

    @Test
    fun setRingerMode_silent() =
        kosmos.runTest {
            val ringerModel by collectLastValue(underTest.ringerModel)

            underTest.setRingerMode(RingerMode(RINGER_MODE_SILENT))
            controller.getState()

            assertThat(ringerModel).isNotNull()
            assertThat(ringerModel?.currentRingerMode).isEqualTo(RingerMode(RINGER_MODE_SILENT))
        }

    @Test
    fun setRingerMode_vibrate() =
        kosmos.runTest {
            val ringerModel by collectLastValue(underTest.ringerModel)

            underTest.setRingerMode(RingerMode(RINGER_MODE_VIBRATE))
            controller.getState()

            assertThat(ringerModel).isNotNull()
            assertThat(ringerModel?.currentRingerMode).isEqualTo(RingerMode(RINGER_MODE_VIBRATE))
        }
}
