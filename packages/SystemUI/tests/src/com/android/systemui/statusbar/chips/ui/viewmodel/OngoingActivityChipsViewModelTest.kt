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

package com.android.systemui.statusbar.chips.ui.viewmodel

import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.common.shared.model.ContentDescription
import com.android.systemui.common.shared.model.Icon
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.kosmos.Kosmos
import com.android.systemui.kosmos.testScope
import com.android.systemui.mediaprojection.data.model.MediaProjectionState
import com.android.systemui.mediaprojection.data.repository.fakeMediaProjectionRepository
import com.android.systemui.mediaprojection.taskswitcher.FakeActivityTaskManager.Companion.createTask
import com.android.systemui.res.R
import com.android.systemui.screenrecord.data.model.ScreenRecordModel
import com.android.systemui.screenrecord.data.repository.screenRecordRepository
import com.android.systemui.statusbar.chips.domain.model.OngoingActivityChipModel
import com.android.systemui.statusbar.chips.mediaprojection.domain.interactor.MediaProjectionChipInteractorTest.Companion.NORMAL_PACKAGE
import com.android.systemui.statusbar.chips.mediaprojection.domain.interactor.MediaProjectionChipInteractorTest.Companion.setUpPackageManagerForMediaProjection
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test

@SmallTest
class OngoingActivityChipsViewModelTest : SysuiTestCase() {

    private val kosmos = Kosmos()
    private val testScope = kosmos.testScope

    private val screenRecordState = kosmos.screenRecordRepository.screenRecordState
    private val mediaProjectionState = kosmos.fakeMediaProjectionRepository.mediaProjectionState
    private val callState = kosmos.callChipInteractor.chip

    private val underTest = kosmos.ongoingActivityChipsViewModel

    @Before
    fun setUp() {
        setUpPackageManagerForMediaProjection(kosmos)
    }

    @Test
    fun chip_allHidden_hidden() =
        testScope.runTest {
            screenRecordState.value = ScreenRecordModel.DoingNothing
            mediaProjectionState.value = MediaProjectionState.NotProjecting
            callState.value = OngoingActivityChipModel.Hidden

            val latest by collectLastValue(underTest.chip)

            assertThat(latest).isEqualTo(OngoingActivityChipModel.Hidden)
        }

    @Test
    fun chip_screenRecordShow_restHidden_screenRecordShown() =
        testScope.runTest {
            screenRecordState.value = ScreenRecordModel.Recording
            mediaProjectionState.value = MediaProjectionState.NotProjecting
            callState.value = OngoingActivityChipModel.Hidden

            val latest by collectLastValue(underTest.chip)

            assertIsScreenRecordChip(latest)
        }

    @Test
    fun chip_screenRecordShowAndCallShow_screenRecordShown() =
        testScope.runTest {
            screenRecordState.value = ScreenRecordModel.Recording

            val callChip =
                OngoingActivityChipModel.Shown(
                    Icon.Resource(R.drawable.ic_call, ContentDescription.Loaded("icon")),
                    startTimeMs = 600L,
                ) {}
            callState.value = callChip

            val latest by collectLastValue(underTest.chip)

            assertIsScreenRecordChip(latest)
        }

    @Test
    fun chip_screenRecordShowAndMediaProjectionShow_screenRecordShown() =
        testScope.runTest {
            screenRecordState.value = ScreenRecordModel.Recording
            mediaProjectionState.value =
                MediaProjectionState.Projecting.EntireScreen(NORMAL_PACKAGE)
            callState.value = OngoingActivityChipModel.Hidden

            val latest by collectLastValue(underTest.chip)

            assertIsScreenRecordChip(latest)
        }

    @Test
    fun chip_mediaProjectionShowAndCallShow_mediaProjectionShown() =
        testScope.runTest {
            screenRecordState.value = ScreenRecordModel.DoingNothing
            mediaProjectionState.value =
                MediaProjectionState.Projecting.EntireScreen(NORMAL_PACKAGE)
            val callChip =
                OngoingActivityChipModel.Shown(
                    Icon.Resource(R.drawable.ic_call, ContentDescription.Loaded("icon")),
                    startTimeMs = 600L,
                ) {}
            callState.value = callChip

            val latest by collectLastValue(underTest.chip)

            assertIsShareToAppChip(latest)
        }

    @Test
    fun chip_screenRecordAndMediaProjectionHideAndCallShown_callShown() =
        testScope.runTest {
            screenRecordState.value = ScreenRecordModel.DoingNothing
            mediaProjectionState.value = MediaProjectionState.NotProjecting

            val callChip =
                OngoingActivityChipModel.Shown(
                    Icon.Resource(R.drawable.ic_call, ContentDescription.Loaded("icon")),
                    startTimeMs = 600L,
                ) {}
            callState.value = callChip

            val latest by collectLastValue(underTest.chip)

            assertThat(latest).isEqualTo(callChip)
        }

    @Test
    fun chip_higherPriorityChipAdded_lowerPriorityChipReplaced() =
        testScope.runTest {
            // Start with just the lower priority call chip
            val callChip =
                OngoingActivityChipModel.Shown(
                    Icon.Resource(R.drawable.ic_call, ContentDescription.Loaded("icon")),
                    startTimeMs = 600L,
                ) {}
            callState.value = callChip
            mediaProjectionState.value = MediaProjectionState.NotProjecting
            screenRecordState.value = ScreenRecordModel.DoingNothing

            val latest by collectLastValue(underTest.chip)

            assertThat(latest).isEqualTo(callChip)

            // WHEN the higher priority media projection chip is added
            mediaProjectionState.value =
                MediaProjectionState.Projecting.SingleTask(
                    NORMAL_PACKAGE,
                    createTask(taskId = 1),
                )

            // THEN the higher priority media projection chip is used
            assertIsShareToAppChip(latest)

            // WHEN the higher priority screen record chip is added
            screenRecordState.value = ScreenRecordModel.Recording

            // THEN the higher priority screen record chip is used
            assertIsScreenRecordChip(latest)
        }

    @Test
    fun chip_highestPriorityChipRemoved_showsNextPriorityChip() =
        testScope.runTest {
            // WHEN all chips are active
            screenRecordState.value = ScreenRecordModel.Recording
            mediaProjectionState.value =
                MediaProjectionState.Projecting.EntireScreen(NORMAL_PACKAGE)

            val callChip =
                OngoingActivityChipModel.Shown(
                    Icon.Resource(R.drawable.ic_call, ContentDescription.Loaded("icon")),
                    startTimeMs = 600L,
                ) {}
            callState.value = callChip

            val latest by collectLastValue(underTest.chip)

            // THEN the highest priority screen record is used
            assertIsScreenRecordChip(latest)

            // WHEN the higher priority screen record is removed
            screenRecordState.value = ScreenRecordModel.DoingNothing

            // THEN the lower priority media projection is used
            assertIsShareToAppChip(latest)

            // WHEN the higher priority media projection is removed
            mediaProjectionState.value = MediaProjectionState.NotProjecting

            // THEN the lower priority call is used
            assertThat(latest).isEqualTo(callChip)
        }

    companion object {
        fun assertIsScreenRecordChip(latest: OngoingActivityChipModel?) {
            assertThat(latest).isInstanceOf(OngoingActivityChipModel.Shown::class.java)
            val icon = (latest as OngoingActivityChipModel.Shown).icon
            assertThat((icon as Icon.Resource).res).isEqualTo(R.drawable.stat_sys_screen_record)
        }

        fun assertIsShareToAppChip(latest: OngoingActivityChipModel?) {
            assertThat(latest).isInstanceOf(OngoingActivityChipModel.Shown::class.java)
            val icon = (latest as OngoingActivityChipModel.Shown).icon
            assertThat((icon as Icon.Resource).res).isEqualTo(R.drawable.ic_screenshot_share)
        }
    }
}
