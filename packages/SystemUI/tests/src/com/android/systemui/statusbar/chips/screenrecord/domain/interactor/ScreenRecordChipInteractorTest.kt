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

package com.android.systemui.statusbar.chips.screenrecord.domain.interactor

import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.common.shared.model.Icon
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.kosmos.Kosmos
import com.android.systemui.kosmos.testScope
import com.android.systemui.res.R
import com.android.systemui.screenrecord.data.model.ScreenRecordModel
import com.android.systemui.screenrecord.data.repository.screenRecordRepository
import com.android.systemui.statusbar.chips.domain.model.OngoingActivityChipModel
import com.android.systemui.statusbar.chips.ui.viewmodel.screenRecordChipInteractor
import com.android.systemui.util.time.fakeSystemClock
import com.google.common.truth.Truth.assertThat
import kotlin.test.Test
import kotlinx.coroutines.test.runTest

@SmallTest
class ScreenRecordChipInteractorTest : SysuiTestCase() {
    private val kosmos = Kosmos()
    private val testScope = kosmos.testScope
    private val screenRecordRepo = kosmos.screenRecordRepository
    private val systemClock = kosmos.fakeSystemClock

    private val underTest = kosmos.screenRecordChipInteractor

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
            assertThat((icon as Icon.Resource).res).isEqualTo(R.drawable.stat_sys_screen_record)
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
}
