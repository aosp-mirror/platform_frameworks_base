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

package com.android.systemui.volume.dialog.domain.interactor

import android.app.ActivityManager
import android.media.AudioManager
import android.testing.TestableLooper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.kosmos.Kosmos
import com.android.systemui.kosmos.collectLastValue
import com.android.systemui.kosmos.runTest
import com.android.systemui.kosmos.useUnconfinedTestDispatcher
import com.android.systemui.plugins.fakeVolumeDialogController
import com.android.systemui.testKosmos
import com.android.systemui.volume.Events
import com.android.systemui.volume.dialog.data.repository.volumeDialogVisibilityRepository
import com.android.systemui.volume.dialog.shared.model.VolumeDialogVisibilityModel
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
@TestableLooper.RunWithLooper
class VolumeDialogSafetyWarningInteractorTest : SysuiTestCase() {

    private val kosmos: Kosmos = testKosmos()

    private lateinit var underTest: VolumeDialogSafetyWarningInteractor

    @Before
    fun setup() {
        kosmos.useUnconfinedTestDispatcher()
        underTest = kosmos.volumeDialogSafetyWarningInteractor
    }

    @Test
    fun dismiss_isShowingSafetyWarning_isFalse() =
        with(kosmos) {
            runTest {
                val isShowingSafetyWarning by collectLastValue(underTest.isShowingSafetyWarning)

                underTest.onSafetyWarningDismissed()

                assertThat(isShowingSafetyWarning).isFalse()
            }
        }

    @Test
    fun flagShowUi_isShowingSafetyWarning_isTrue() =
        with(kosmos) {
            runTest {
                val isShowingSafetyWarning by collectLastValue(underTest.isShowingSafetyWarning)

                fakeVolumeDialogController.onShowSafetyWarning(AudioManager.FLAG_SHOW_UI)

                assertThat(isShowingSafetyWarning).isTrue()
            }
        }

    @Test
    fun flagShowUiWarnings_isShowingSafetyWarning_isTrue() =
        with(kosmos) {
            runTest {
                val isShowingSafetyWarning by collectLastValue(underTest.isShowingSafetyWarning)

                fakeVolumeDialogController.onShowSafetyWarning(AudioManager.FLAG_SHOW_UI_WARNINGS)

                assertThat(isShowingSafetyWarning).isTrue()
            }
        }

    @Test
    fun invisibleAndNoFlags_isShowingSafetyWarning_isFalse() =
        with(kosmos) {
            runTest {
                val isShowingSafetyWarning by collectLastValue(underTest.isShowingSafetyWarning)
                volumeDialogVisibilityRepository.updateVisibility {
                    VolumeDialogVisibilityModel.Invisible
                }

                fakeVolumeDialogController.onShowSafetyWarning(0)

                assertThat(isShowingSafetyWarning).isFalse()
            }
        }

    @Test
    fun visibleAndNoFlags_isShowingSafetyWarning_isTrue() =
        with(kosmos) {
            runTest {
                val isShowingSafetyWarning by collectLastValue(underTest.isShowingSafetyWarning)
                volumeDialogVisibilityRepository.updateVisibility {
                    VolumeDialogVisibilityModel.Visible(
                        Events.SHOW_REASON_VOLUME_CHANGED,
                        false,
                        ActivityManager.LOCK_TASK_MODE_LOCKED,
                    )
                }

                fakeVolumeDialogController.onShowSafetyWarning(0)

                assertThat(isShowingSafetyWarning).isTrue()
            }
        }
}
