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
import android.testing.TestableLooper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.kosmos.Kosmos
import com.android.systemui.kosmos.testScope
import com.android.systemui.plugins.fakeVolumeDialogController
import com.android.systemui.testKosmos
import com.android.systemui.volume.Events
import com.android.systemui.volume.dialog.shared.model.VolumeDialogVisibilityModel
import com.google.common.truth.Truth.assertThat
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

private val dialogTimeoutDuration = 3.seconds

@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
@RunWith(AndroidJUnit4::class)
@TestableLooper.RunWithLooper
class VolumeDialogVisibilityInteractorTest : SysuiTestCase() {

    private val kosmos: Kosmos = testKosmos()

    private lateinit var underTest: VolumeDialogVisibilityInteractor

    @Before
    fun setUp() {
        underTest = kosmos.volumeDialogVisibilityInteractor
    }

    @Test
    fun testShowRequest_visible() =
        with(kosmos) {
            testScope.runTest {
                runCurrent()
                val visibilityModel by collectLastValue(underTest.dialogVisibility)
                fakeVolumeDialogController.onShowRequested(
                    Events.SHOW_REASON_VOLUME_CHANGED,
                    false,
                    ActivityManager.LOCK_TASK_MODE_LOCKED,
                )
                runCurrent()

                assertThat(visibilityModel!!)
                    .isEqualTo(
                        VolumeDialogVisibilityModel.Visible(
                            Events.SHOW_REASON_VOLUME_CHANGED,
                            false,
                            ActivityManager.LOCK_TASK_MODE_LOCKED,
                        )
                    )
            }
        }

    @Test
    fun testDismissRequest_dismissed() =
        with(kosmos) {
            testScope.runTest {
                runCurrent()
                val visibilityModel by collectLastValue(underTest.dialogVisibility)
                fakeVolumeDialogController.onShowRequested(
                    Events.SHOW_REASON_VOLUME_CHANGED,
                    false,
                    ActivityManager.LOCK_TASK_MODE_LOCKED,
                )
                runCurrent()

                fakeVolumeDialogController.onDismissRequested(Events.DISMISS_REASON_SCREEN_OFF)

                assertThat(visibilityModel!!)
                    .isEqualTo(
                        VolumeDialogVisibilityModel.Dismissed(Events.DISMISS_REASON_SCREEN_OFF)
                    )
            }
        }

    @Test
    fun testTimeout_dismissed() =
        with(kosmos) {
            testScope.runTest {
                runCurrent()
                underTest.resetDismissTimeout()
                val visibilityModel by collectLastValue(underTest.dialogVisibility)
                fakeVolumeDialogController.onShowRequested(
                    Events.SHOW_REASON_VOLUME_CHANGED,
                    false,
                    ActivityManager.LOCK_TASK_MODE_LOCKED,
                )
                runCurrent()

                advanceTimeBy(1.days)

                assertThat(visibilityModel!!)
                    .isEqualTo(VolumeDialogVisibilityModel.Dismissed(Events.DISMISS_REASON_TIMEOUT))
            }
        }

    @Test
    fun testResetTimeoutInterruptsEvents() =
        with(kosmos) {
            testScope.runTest {
                runCurrent()
                underTest.resetDismissTimeout()
                val visibilityModel by collectLastValue(underTest.dialogVisibility)
                fakeVolumeDialogController.onShowRequested(
                    Events.SHOW_REASON_VOLUME_CHANGED,
                    false,
                    ActivityManager.LOCK_TASK_MODE_LOCKED,
                )
                runCurrent()

                advanceTimeBy(dialogTimeoutDuration / 2)
                underTest.resetDismissTimeout()
                advanceTimeBy(dialogTimeoutDuration / 2)
                underTest.resetDismissTimeout()
                advanceTimeBy(dialogTimeoutDuration / 2)

                assertThat(visibilityModel)
                    .isInstanceOf(VolumeDialogVisibilityModel.Visible::class.java)
            }
        }
}
