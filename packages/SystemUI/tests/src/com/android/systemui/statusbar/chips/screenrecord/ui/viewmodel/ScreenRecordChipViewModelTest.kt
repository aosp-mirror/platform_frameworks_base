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

package com.android.systemui.statusbar.chips.screenrecord.ui.viewmodel

import android.view.View
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.animation.mockDialogTransitionAnimator
import com.android.systemui.common.shared.model.Icon
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.kosmos.Kosmos
import com.android.systemui.kosmos.testCase
import com.android.systemui.kosmos.testScope
import com.android.systemui.mediaprojection.data.model.MediaProjectionState
import com.android.systemui.mediaprojection.data.repository.fakeMediaProjectionRepository
import com.android.systemui.mediaprojection.taskswitcher.FakeActivityTaskManager
import com.android.systemui.res.R
import com.android.systemui.screenrecord.data.model.ScreenRecordModel
import com.android.systemui.screenrecord.data.repository.screenRecordRepository
import com.android.systemui.statusbar.chips.screenrecord.ui.view.EndScreenRecordingDialogDelegate
import com.android.systemui.statusbar.chips.ui.model.ColorsModel
import com.android.systemui.statusbar.chips.ui.model.OngoingActivityChipModel
import com.android.systemui.statusbar.chips.ui.view.ChipBackgroundContainer
import com.android.systemui.statusbar.phone.SystemUIDialog
import com.android.systemui.statusbar.phone.mockSystemUIDialogFactory
import com.android.systemui.util.time.fakeSystemClock
import com.google.common.truth.Truth.assertThat
import kotlin.test.Test
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.mockito.ArgumentMatchers
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

@SmallTest
class ScreenRecordChipViewModelTest : SysuiTestCase() {
    private val kosmos = Kosmos().also { it.testCase = this }
    private val testScope = kosmos.testScope
    private val screenRecordRepo = kosmos.screenRecordRepository
    private val mediaProjectionRepo = kosmos.fakeMediaProjectionRepository
    private val systemClock = kosmos.fakeSystemClock
    private val mockSystemUIDialog = mock<SystemUIDialog>()

    private val chipBackgroundView = mock<ChipBackgroundContainer>()
    private val chipView =
        mock<View>().apply {
            whenever(
                    this.requireViewById<ChipBackgroundContainer>(
                        R.id.ongoing_activity_chip_background
                    )
                )
                .thenReturn(chipBackgroundView)
        }

    private val underTest = kosmos.screenRecordChipViewModel

    @Before
    fun setUp() {
        whenever(kosmos.mockSystemUIDialogFactory.create(any<EndScreenRecordingDialogDelegate>()))
            .thenReturn(mockSystemUIDialog)
    }

    @Test
    fun chip_doingNothingState_isHidden() =
        testScope.runTest {
            val latest by collectLastValue(underTest.chip)

            screenRecordRepo.screenRecordState.value = ScreenRecordModel.DoingNothing

            assertThat(latest).isInstanceOf(OngoingActivityChipModel.Hidden::class.java)
        }

    @Test
    fun chip_startingState_isHidden() =
        testScope.runTest {
            val latest by collectLastValue(underTest.chip)

            screenRecordRepo.screenRecordState.value = ScreenRecordModel.Starting(400)

            assertThat(latest).isInstanceOf(OngoingActivityChipModel.Hidden::class.java)
        }

    @Test
    fun chip_recordingState_isShownWithIcon() =
        testScope.runTest {
            val latest by collectLastValue(underTest.chip)

            screenRecordRepo.screenRecordState.value = ScreenRecordModel.Recording

            assertThat(latest).isInstanceOf(OngoingActivityChipModel.Shown::class.java)
            val icon = (latest as OngoingActivityChipModel.Shown).icon
            assertThat((icon as Icon.Resource).res).isEqualTo(R.drawable.ic_screenrecord)
        }

    @Test
    fun chip_colorsAreRed() =
        testScope.runTest {
            val latest by collectLastValue(underTest.chip)

            screenRecordRepo.screenRecordState.value = ScreenRecordModel.Recording

            assertThat((latest as OngoingActivityChipModel.Shown).colors).isEqualTo(ColorsModel.Red)
        }

    @Test
    fun chip_timeResetsOnEachNewRecording() =
        testScope.runTest {
            val latest by collectLastValue(underTest.chip)

            systemClock.setElapsedRealtime(1234)
            screenRecordRepo.screenRecordState.value = ScreenRecordModel.Recording

            assertThat(latest).isInstanceOf(OngoingActivityChipModel.Shown::class.java)
            assertThat((latest as OngoingActivityChipModel.Shown).startTimeMs).isEqualTo(1234)

            screenRecordRepo.screenRecordState.value = ScreenRecordModel.DoingNothing
            assertThat(latest).isInstanceOf(OngoingActivityChipModel.Hidden::class.java)

            systemClock.setElapsedRealtime(5678)
            screenRecordRepo.screenRecordState.value = ScreenRecordModel.Recording

            assertThat(latest).isInstanceOf(OngoingActivityChipModel.Shown::class.java)
            assertThat((latest as OngoingActivityChipModel.Shown).startTimeMs).isEqualTo(5678)
        }

    @Test
    fun chip_notProjecting_clickListenerShowsDialog() =
        testScope.runTest {
            val latest by collectLastValue(underTest.chip)
            screenRecordRepo.screenRecordState.value = ScreenRecordModel.Recording
            mediaProjectionRepo.mediaProjectionState.value = MediaProjectionState.NotProjecting

            val clickListener = ((latest as OngoingActivityChipModel.Shown).onClickListener)

            clickListener.onClick(chipView)
            // EndScreenRecordingDialogDelegate will test that the dialog has the right message
            verify(kosmos.mockDialogTransitionAnimator)
                .showFromView(
                    eq(mockSystemUIDialog),
                    eq(chipBackgroundView),
                    eq(null),
                    ArgumentMatchers.anyBoolean(),
                )
        }

    @Test
    fun chip_projectingEntireScreen_clickListenerShowsDialog() =
        testScope.runTest {
            val latest by collectLastValue(underTest.chip)
            screenRecordRepo.screenRecordState.value = ScreenRecordModel.Recording
            mediaProjectionRepo.mediaProjectionState.value =
                MediaProjectionState.Projecting.EntireScreen("host.package")

            val clickListener = ((latest as OngoingActivityChipModel.Shown).onClickListener)

            clickListener.onClick(chipView)
            // EndScreenRecordingDialogDelegate will test that the dialog has the right message
            verify(kosmos.mockDialogTransitionAnimator)
                .showFromView(
                    eq(mockSystemUIDialog),
                    eq(chipBackgroundView),
                    eq(null),
                    ArgumentMatchers.anyBoolean(),
                )
        }

    @Test
    fun chip_projectingSingleTask_clickListenerShowsDialog() =
        testScope.runTest {
            val latest by collectLastValue(underTest.chip)
            screenRecordRepo.screenRecordState.value = ScreenRecordModel.Recording
            mediaProjectionRepo.mediaProjectionState.value =
                MediaProjectionState.Projecting.SingleTask(
                    "host.package",
                    FakeActivityTaskManager.createTask(taskId = 1)
                )

            val clickListener = ((latest as OngoingActivityChipModel.Shown).onClickListener)

            clickListener.onClick(chipView)
            // EndScreenRecordingDialogDelegate will test that the dialog has the right message
            verify(kosmos.mockDialogTransitionAnimator)
                .showFromView(
                    eq(mockSystemUIDialog),
                    eq(chipBackgroundView),
                    eq(null),
                    ArgumentMatchers.anyBoolean(),
                )
        }
}
