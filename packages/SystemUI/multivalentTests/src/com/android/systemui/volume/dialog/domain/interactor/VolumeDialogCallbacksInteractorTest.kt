/*
 * Copyright (C) 2025 The Android Open Source Project
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

package com.android.systemui.volume.dialog.domain.interactor

import android.media.AudioManager.RINGER_MODE_NORMAL
import android.media.AudioManager.RINGER_MODE_SILENT
import android.media.AudioManager.RINGER_MODE_VIBRATE
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.kosmos.collectLastValue
import com.android.systemui.kosmos.runTest
import com.android.systemui.kosmos.useUnconfinedTestDispatcher
import com.android.systemui.plugins.fakeVolumeDialogController
import com.android.systemui.testKosmos
import com.android.systemui.volume.dialog.domain.model.VolumeDialogEventModel
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
class VolumeDialogCallbacksInteractorTest : SysuiTestCase() {

    private val kosmos = testKosmos().apply { useUnconfinedTestDispatcher() }

    private val underTest: VolumeDialogCallbacksInteractor by lazy {
        kosmos.volumeDialogCallbacksInteractor
    }

    @Test
    fun initialEvent_isSubscribedToEvents() =
        kosmos.runTest {
            val event by collectLastValue(underTest.event)
            assertThat(event).isInstanceOf(VolumeDialogEventModel.SubscribedToEvents::class.java)
        }

    @Test
    fun showSilentHint_setsRingerModeToNormal() =
        kosmos.runTest {
            fakeVolumeDialogController.setRingerMode(RINGER_MODE_VIBRATE, false)

            underTest // It should eagerly collect the values and update the controller
            fakeVolumeDialogController.onShowSilentHint()
            fakeVolumeDialogController.getState()

            assertThat(fakeVolumeDialogController.state.ringerModeInternal)
                .isEqualTo(RINGER_MODE_NORMAL)
        }

    @Test
    fun showVibrateHint_setsRingerModeToSilent() =
        kosmos.runTest {
            fakeVolumeDialogController.setRingerMode(RINGER_MODE_VIBRATE, false)

            underTest // It should eagerly collect the values and update the controller
            fakeVolumeDialogController.onShowVibrateHint()
            fakeVolumeDialogController.getState()

            assertThat(fakeVolumeDialogController.state.ringerModeInternal)
                .isEqualTo(RINGER_MODE_SILENT)
        }
}
