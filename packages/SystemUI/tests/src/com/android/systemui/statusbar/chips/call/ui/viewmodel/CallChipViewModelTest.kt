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
import android.platform.test.annotations.DisableFlags
import android.platform.test.annotations.EnableFlags
import android.view.View
import androidx.test.filters.SmallTest
import com.android.systemui.Flags.FLAG_STATUS_BAR_CALL_CHIP_NOTIFICATION_ICON
import com.android.systemui.SysuiTestCase
import com.android.systemui.common.shared.model.Icon
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.kosmos.Kosmos
import com.android.systemui.kosmos.testScope
import com.android.systemui.plugins.activityStarter
import com.android.systemui.res.R
import com.android.systemui.statusbar.StatusBarIconView
import com.android.systemui.statusbar.chips.ui.model.ColorsModel
import com.android.systemui.statusbar.chips.ui.model.OngoingActivityChipModel
import com.android.systemui.statusbar.chips.ui.view.ChipBackgroundContainer
import com.android.systemui.statusbar.phone.ongoingcall.data.repository.ongoingCallRepository
import com.android.systemui.statusbar.phone.ongoingcall.shared.model.OngoingCallModel
import com.android.systemui.statusbar.phone.ongoingcall.shared.model.inCallModel
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
    fun chip_inCall_zeroStartTime_isShownAsIconOnly() =
        testScope.runTest {
            val latest by collectLastValue(underTest.chip)

            repo.setOngoingCallState(inCallModel(startTimeMs = 0))

            assertThat(latest).isInstanceOf(OngoingActivityChipModel.Shown.IconOnly::class.java)
        }

    @Test
    fun chip_inCall_negativeStartTime_isShownAsIconOnly() =
        testScope.runTest {
            val latest by collectLastValue(underTest.chip)

            repo.setOngoingCallState(inCallModel(startTimeMs = -2))

            assertThat(latest).isInstanceOf(OngoingActivityChipModel.Shown.IconOnly::class.java)
        }

    @Test
    fun chip_inCall_positiveStartTime_isShownAsTimer() =
        testScope.runTest {
            val latest by collectLastValue(underTest.chip)

            repo.setOngoingCallState(inCallModel(startTimeMs = 345))

            assertThat(latest).isInstanceOf(OngoingActivityChipModel.Shown.Timer::class.java)
        }

    @Test
    fun chip_inCall_startTimeConvertedToElapsedRealtime() =
        testScope.runTest {
            val latest by collectLastValue(underTest.chip)

            kosmos.fakeSystemClock.setCurrentTimeMillis(3000)
            kosmos.fakeSystemClock.setElapsedRealtime(400_000)

            repo.setOngoingCallState(inCallModel(startTimeMs = 1000))

            // The OngoingCallModel start time is relative to currentTimeMillis, so this call
            // started 2000ms ago (1000 - 3000). The OngoingActivityChipModel start time needs to be
            // relative to elapsedRealtime, so it should be 2000ms before the elapsed realtime set
            // on the clock.
            assertThat((latest as OngoingActivityChipModel.Shown.Timer).startTimeMs)
                .isEqualTo(398_000)
        }

    @Test
    @DisableFlags(FLAG_STATUS_BAR_CALL_CHIP_NOTIFICATION_ICON)
    fun chip_positiveStartTime_notifIconFlagOff_iconIsPhone() =
        testScope.runTest {
            val latest by collectLastValue(underTest.chip)

            repo.setOngoingCallState(
                inCallModel(startTimeMs = 1000, notificationIcon = mock<StatusBarIconView>())
            )

            assertThat((latest as OngoingActivityChipModel.Shown).icon)
                .isInstanceOf(OngoingActivityChipModel.ChipIcon.SingleColorIcon::class.java)
            val icon =
                (((latest as OngoingActivityChipModel.Shown).icon)
                        as OngoingActivityChipModel.ChipIcon.SingleColorIcon)
                    .impl as Icon.Resource
            assertThat(icon.res).isEqualTo(com.android.internal.R.drawable.ic_phone)
            assertThat(icon.contentDescription).isNotNull()
        }

    @Test
    @EnableFlags(FLAG_STATUS_BAR_CALL_CHIP_NOTIFICATION_ICON)
    fun chip_positiveStartTime_notifIconFlagOn_iconIsNotifIcon() =
        testScope.runTest {
            val latest by collectLastValue(underTest.chip)

            val notifIcon = mock<StatusBarIconView>()
            repo.setOngoingCallState(inCallModel(startTimeMs = 1000, notificationIcon = notifIcon))

            assertThat((latest as OngoingActivityChipModel.Shown).icon)
                .isInstanceOf(OngoingActivityChipModel.ChipIcon.StatusBarView::class.java)
            val actualIcon =
                (((latest as OngoingActivityChipModel.Shown).icon)
                        as OngoingActivityChipModel.ChipIcon.StatusBarView)
                    .impl
            assertThat(actualIcon).isEqualTo(notifIcon)
        }

    @Test
    @DisableFlags(FLAG_STATUS_BAR_CALL_CHIP_NOTIFICATION_ICON)
    fun chip_zeroStartTime_notifIconFlagOff_iconIsPhone() =
        testScope.runTest {
            val latest by collectLastValue(underTest.chip)

            repo.setOngoingCallState(
                inCallModel(startTimeMs = 0, notificationIcon = mock<StatusBarIconView>())
            )

            assertThat((latest as OngoingActivityChipModel.Shown).icon)
                .isInstanceOf(OngoingActivityChipModel.ChipIcon.SingleColorIcon::class.java)
            val icon =
                (((latest as OngoingActivityChipModel.Shown).icon)
                        as OngoingActivityChipModel.ChipIcon.SingleColorIcon)
                    .impl as Icon.Resource
            assertThat(icon.res).isEqualTo(com.android.internal.R.drawable.ic_phone)
            assertThat(icon.contentDescription).isNotNull()
        }

    @Test
    @EnableFlags(FLAG_STATUS_BAR_CALL_CHIP_NOTIFICATION_ICON)
    fun chip_zeroStartTime_notifIconFlagOn_iconIsNotifIcon() =
        testScope.runTest {
            val latest by collectLastValue(underTest.chip)

            val notifIcon = mock<StatusBarIconView>()
            repo.setOngoingCallState(inCallModel(startTimeMs = 0, notificationIcon = notifIcon))

            assertThat((latest as OngoingActivityChipModel.Shown).icon)
                .isInstanceOf(OngoingActivityChipModel.ChipIcon.StatusBarView::class.java)
            val actualIcon =
                (((latest as OngoingActivityChipModel.Shown).icon)
                        as OngoingActivityChipModel.ChipIcon.StatusBarView)
                    .impl
            assertThat(actualIcon).isEqualTo(notifIcon)
        }

    @Test
    @EnableFlags(FLAG_STATUS_BAR_CALL_CHIP_NOTIFICATION_ICON)
    fun chip_notifIconFlagOn_butNullNotifIcon_iconIsPhone() =
        testScope.runTest {
            val latest by collectLastValue(underTest.chip)

            repo.setOngoingCallState(inCallModel(startTimeMs = 1000, notificationIcon = null))

            assertThat((latest as OngoingActivityChipModel.Shown).icon)
                .isInstanceOf(OngoingActivityChipModel.ChipIcon.SingleColorIcon::class.java)
            val icon =
                (((latest as OngoingActivityChipModel.Shown).icon)
                        as OngoingActivityChipModel.ChipIcon.SingleColorIcon)
                    .impl as Icon.Resource
            assertThat(icon.res).isEqualTo(com.android.internal.R.drawable.ic_phone)
            assertThat(icon.contentDescription).isNotNull()
        }

    @Test
    fun chip_positiveStartTime_colorsAreThemed() =
        testScope.runTest {
            val latest by collectLastValue(underTest.chip)

            repo.setOngoingCallState(inCallModel(startTimeMs = 1000))

            assertThat((latest as OngoingActivityChipModel.Shown).colors)
                .isEqualTo(ColorsModel.Themed)
        }

    @Test
    fun chip_zeroStartTime_colorsAreThemed() =
        testScope.runTest {
            val latest by collectLastValue(underTest.chip)

            repo.setOngoingCallState(inCallModel(startTimeMs = 0))

            assertThat((latest as OngoingActivityChipModel.Shown).colors)
                .isEqualTo(ColorsModel.Themed)
        }

    @Test
    fun chip_resetsCorrectly() =
        testScope.runTest {
            val latest by collectLastValue(underTest.chip)
            kosmos.fakeSystemClock.setCurrentTimeMillis(3000)
            kosmos.fakeSystemClock.setElapsedRealtime(400_000)

            // Start a call
            repo.setOngoingCallState(inCallModel(startTimeMs = 1000))
            assertThat(latest).isInstanceOf(OngoingActivityChipModel.Shown::class.java)
            assertThat((latest as OngoingActivityChipModel.Shown.Timer).startTimeMs)
                .isEqualTo(398_000)

            // End the call
            repo.setOngoingCallState(OngoingCallModel.NoCall)
            assertThat(latest).isInstanceOf(OngoingActivityChipModel.Hidden::class.java)

            // Let 100_000ms elapse
            kosmos.fakeSystemClock.setCurrentTimeMillis(103_000)
            kosmos.fakeSystemClock.setElapsedRealtime(500_000)

            // Start a new call, which started 1000ms ago
            repo.setOngoingCallState(inCallModel(startTimeMs = 102_000))
            assertThat(latest).isInstanceOf(OngoingActivityChipModel.Shown::class.java)
            assertThat((latest as OngoingActivityChipModel.Shown.Timer).startTimeMs)
                .isEqualTo(499_000)
        }

    @Test
    fun chip_inCall_nullIntent_nullClickListener() =
        testScope.runTest {
            val latest by collectLastValue(underTest.chip)

            repo.setOngoingCallState(inCallModel(startTimeMs = 1000, intent = null))

            assertThat((latest as OngoingActivityChipModel.Shown).onClickListener).isNull()
        }

    @Test
    fun chip_inCall_positiveStartTime_validIntent_clickListenerLaunchesIntent() =
        testScope.runTest {
            val latest by collectLastValue(underTest.chip)

            val intent = mock<PendingIntent>()
            repo.setOngoingCallState(inCallModel(startTimeMs = 1000, intent = intent))
            val clickListener = (latest as OngoingActivityChipModel.Shown).onClickListener
            assertThat(clickListener).isNotNull()

            clickListener!!.onClick(chipView)

            verify(kosmos.activityStarter).postStartActivityDismissingKeyguard(intent, null)
        }

    @Test
    fun chip_inCall_zeroStartTime_validIntent_clickListenerLaunchesIntent() =
        testScope.runTest {
            val latest by collectLastValue(underTest.chip)

            val intent = mock<PendingIntent>()
            repo.setOngoingCallState(inCallModel(startTimeMs = 0, intent = intent))
            val clickListener = (latest as OngoingActivityChipModel.Shown).onClickListener
            assertThat(clickListener).isNotNull()

            clickListener!!.onClick(chipView)

            verify(kosmos.activityStarter).postStartActivityDismissingKeyguard(intent, null)
        }
}
