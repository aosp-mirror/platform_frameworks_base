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

package com.android.systemui.statusbar.chips.call.ui.viewmodel

import android.app.PendingIntent
import android.view.View
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.common.shared.model.Icon
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.kosmos.Kosmos
import com.android.systemui.kosmos.testScope
import com.android.systemui.plugins.activityStarter
import com.android.systemui.res.R
import com.android.systemui.statusbar.chips.ui.model.OngoingActivityChipModel
import com.android.systemui.statusbar.chips.ui.view.ChipBackgroundContainer
import com.android.systemui.statusbar.phone.ongoingcall.data.repository.ongoingCallRepository
import com.android.systemui.statusbar.phone.ongoingcall.shared.model.OngoingCallModel
import com.android.systemui.util.time.fakeSystemClock
import com.google.common.truth.Truth.assertThat
import kotlin.test.Test
import kotlinx.coroutines.test.runTest
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

@SmallTest
class CallChipViewModelTest : SysuiTestCase() {
    private val kosmos = Kosmos()
    private val testScope = kosmos.testScope
    private val repo = kosmos.ongoingCallRepository

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

    private val underTest = kosmos.callChipViewModel

    @Test
    fun chip_noCall_isHidden() =
        testScope.runTest {
            val latest by collectLastValue(underTest.chip)

            repo.setOngoingCallState(OngoingCallModel.NoCall)

            assertThat(latest).isInstanceOf(OngoingActivityChipModel.Hidden::class.java)
        }

    @Test
    fun chip_inCall_isShown() =
        testScope.runTest {
            val latest by collectLastValue(underTest.chip)

            repo.setOngoingCallState(OngoingCallModel.InCall(startTimeMs = 345, intent = null))

            assertThat(latest).isInstanceOf(OngoingActivityChipModel.Shown::class.java)
        }

    @Test
    fun chip_inCall_startTimeConvertedToElapsedRealtime() =
        testScope.runTest {
            val latest by collectLastValue(underTest.chip)

            kosmos.fakeSystemClock.setCurrentTimeMillis(3000)
            kosmos.fakeSystemClock.setElapsedRealtime(400_000)

            repo.setOngoingCallState(OngoingCallModel.InCall(startTimeMs = 1000, intent = null))

            // The OngoingCallModel start time is relative to currentTimeMillis, so this call
            // started 2000ms ago (1000 - 3000). The OngoingActivityChipModel start time needs to be
            // relative to elapsedRealtime, so it should be 2000ms before the elapsed realtime set
            // on the clock.
            assertThat((latest as OngoingActivityChipModel.Shown).startTimeMs).isEqualTo(398_000)
        }

    @Test
    fun chip_inCall_iconIsPhone() =
        testScope.runTest {
            val latest by collectLastValue(underTest.chip)

            repo.setOngoingCallState(OngoingCallModel.InCall(startTimeMs = 1000, intent = null))

            assertThat(((latest as OngoingActivityChipModel.Shown).icon as Icon.Resource).res)
                .isEqualTo(com.android.internal.R.drawable.ic_phone)
        }

    @Test
    fun chip_resetsCorrectly() =
        testScope.runTest {
            val latest by collectLastValue(underTest.chip)
            kosmos.fakeSystemClock.setCurrentTimeMillis(3000)
            kosmos.fakeSystemClock.setElapsedRealtime(400_000)

            // Start a call
            repo.setOngoingCallState(OngoingCallModel.InCall(startTimeMs = 1000, intent = null))
            assertThat(latest).isInstanceOf(OngoingActivityChipModel.Shown::class.java)
            assertThat((latest as OngoingActivityChipModel.Shown).startTimeMs).isEqualTo(398_000)

            // End the call
            repo.setOngoingCallState(OngoingCallModel.NoCall)
            assertThat(latest).isInstanceOf(OngoingActivityChipModel.Hidden::class.java)

            // Let 100_000ms elapse
            kosmos.fakeSystemClock.setCurrentTimeMillis(103_000)
            kosmos.fakeSystemClock.setElapsedRealtime(500_000)

            // Start a new call, which started 1000ms ago
            repo.setOngoingCallState(OngoingCallModel.InCall(startTimeMs = 102_000, intent = null))
            assertThat(latest).isInstanceOf(OngoingActivityChipModel.Shown::class.java)
            assertThat((latest as OngoingActivityChipModel.Shown).startTimeMs).isEqualTo(499_000)
        }

    @Test
    fun chip_inCall_nullIntent_clickListenerDoesNothing() =
        testScope.runTest {
            val latest by collectLastValue(underTest.chip)

            repo.setOngoingCallState(OngoingCallModel.InCall(startTimeMs = 1000, intent = null))

            val clickListener = (latest as OngoingActivityChipModel.Shown).onClickListener

            clickListener.onClick(chipView)
            // Just verify nothing crashes
        }

    @Test
    fun chip_inCall_validIntent_clickListenerLaunchesIntent() =
        testScope.runTest {
            val latest by collectLastValue(underTest.chip)

            val intent = mock<PendingIntent>()
            repo.setOngoingCallState(OngoingCallModel.InCall(startTimeMs = 1000, intent = intent))
            val clickListener = (latest as OngoingActivityChipModel.Shown).onClickListener

            clickListener.onClick(chipView)

            verify(kosmos.activityStarter).postStartActivityDismissingKeyguard(intent, null)
        }
}
