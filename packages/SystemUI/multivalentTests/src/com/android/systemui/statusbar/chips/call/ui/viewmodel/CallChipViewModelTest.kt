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
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.animation.Expandable
import com.android.systemui.common.shared.model.ContentDescription.Companion.loadContentDescription
import com.android.systemui.common.shared.model.Icon
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.kosmos.testScope
import com.android.systemui.plugins.activityStarter
import com.android.systemui.res.R
import com.android.systemui.statusbar.StatusBarIconView
import com.android.systemui.statusbar.chips.notification.shared.StatusBarNotifChips
import com.android.systemui.statusbar.chips.ui.model.ColorsModel
import com.android.systemui.statusbar.chips.ui.model.OngoingActivityChipModel
import com.android.systemui.statusbar.chips.ui.view.ChipBackgroundContainer
import com.android.systemui.statusbar.core.StatusBarConnectedDisplays
import com.android.systemui.statusbar.core.StatusBarRootModernization
import com.android.systemui.statusbar.notification.data.model.activeNotificationModel
import com.android.systemui.statusbar.notification.data.repository.ActiveNotificationListRepository
import com.android.systemui.statusbar.notification.data.repository.ActiveNotificationsStore
import com.android.systemui.statusbar.notification.data.repository.activeNotificationListRepository
import com.android.systemui.statusbar.notification.promoted.shared.model.PromotedNotificationContentModel
import com.android.systemui.statusbar.notification.shared.CallType
import com.android.systemui.statusbar.phone.ongoingcall.StatusBarChipsModernization
import com.android.systemui.statusbar.phone.ongoingcall.data.repository.ongoingCallRepository
import com.android.systemui.statusbar.phone.ongoingcall.shared.model.OngoingCallModel
import com.android.systemui.statusbar.phone.ongoingcall.shared.model.inCallModel
import com.android.systemui.testKosmos
import com.android.systemui.util.time.fakeSystemClock
import com.google.common.truth.Truth.assertThat
import kotlin.test.Test
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

@SmallTest
@RunWith(AndroidJUnit4::class)
class CallChipViewModelTest : SysuiTestCase() {
    private val kosmos = testKosmos()
    private val notificationListRepository = kosmos.activeNotificationListRepository
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
    private val mockExpandable: Expandable =
        mock<Expandable>().apply { whenever(dialogTransitionController(any())).thenReturn(mock()) }

    private val underTest by lazy { kosmos.callChipViewModel }

    @Test
    fun chip_noCall_isHidden() =
        testScope.runTest {
            val latest by collectLastValue(underTest.chip)

            repo.setOngoingCallState(OngoingCallModel.NoCall)

            assertThat(latest).isInstanceOf(OngoingActivityChipModel.Inactive::class.java)
        }

    @Test
    fun chip_inCall_zeroStartTime_isShownAsIconOnly() =
        testScope.runTest {
            val latest by collectLastValue(underTest.chip)

            repo.setOngoingCallState(inCallModel(startTimeMs = 0))

            assertThat(latest).isInstanceOf(OngoingActivityChipModel.Active.IconOnly::class.java)
        }

    @Test
    fun chip_inCall_negativeStartTime_isShownAsIconOnly() =
        testScope.runTest {
            val latest by collectLastValue(underTest.chip)

            repo.setOngoingCallState(inCallModel(startTimeMs = -2))

            assertThat(latest).isInstanceOf(OngoingActivityChipModel.Active.IconOnly::class.java)
        }

    @Test
    fun chip_inCall_positiveStartTime_isShownAsTimer() =
        testScope.runTest {
            val latest by collectLastValue(underTest.chip)

            repo.setOngoingCallState(inCallModel(startTimeMs = 345))

            assertThat(latest).isInstanceOf(OngoingActivityChipModel.Active.Timer::class.java)
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
            assertThat((latest as OngoingActivityChipModel.Active.Timer).startTimeMs)
                .isEqualTo(398_000)
        }

    @Test
    @EnableFlags(StatusBarConnectedDisplays.FLAG_NAME)
    fun chip_positiveStartTime_connectedDisplaysFlagOn_iconIsNotifIcon() =
        testScope.runTest {
            val latest by collectLastValue(underTest.chip)

            val notifKey = "testNotifKey"
            repo.setOngoingCallState(
                inCallModel(startTimeMs = 1000, notificationIcon = null, notificationKey = notifKey)
            )

            assertThat((latest as OngoingActivityChipModel.Active).icon)
                .isInstanceOf(
                    OngoingActivityChipModel.ChipIcon.StatusBarNotificationIcon::class.java
                )
            val actualNotifKey =
                (((latest as OngoingActivityChipModel.Active).icon)
                        as OngoingActivityChipModel.ChipIcon.StatusBarNotificationIcon)
                    .notificationKey
            assertThat(actualNotifKey).isEqualTo(notifKey)
        }

    @Test
    @DisableFlags(StatusBarConnectedDisplays.FLAG_NAME)
    fun chip_zeroStartTime_cdFlagOff_iconIsNotifIcon_withContentDescription() =
        testScope.runTest {
            val latest by collectLastValue(underTest.chip)

            val notifIcon = createStatusBarIconViewOrNull()
            repo.setOngoingCallState(
                inCallModel(
                    startTimeMs = 0,
                    notificationIcon = notifIcon,
                    appName = "Fake app name",
                )
            )

            assertThat((latest as OngoingActivityChipModel.Active).icon)
                .isInstanceOf(OngoingActivityChipModel.ChipIcon.StatusBarView::class.java)
            val actualIcon =
                (latest as OngoingActivityChipModel.Active).icon
                    as OngoingActivityChipModel.ChipIcon.StatusBarView
            assertThat(actualIcon.impl).isEqualTo(notifIcon)
            assertThat(actualIcon.contentDescription.loadContentDescription(context))
                .contains("Ongoing call")
            assertThat(actualIcon.contentDescription.loadContentDescription(context))
                .contains("Fake app name")
        }

    @Test
    @EnableFlags(StatusBarConnectedDisplays.FLAG_NAME)
    fun chip_zeroStartTime_cdFlagOn_iconIsNotifKeyIcon_withContentDescription() =
        testScope.runTest {
            val latest by collectLastValue(underTest.chip)

            repo.setOngoingCallState(
                inCallModel(
                    startTimeMs = 0,
                    notificationIcon = createStatusBarIconViewOrNull(),
                    notificationKey = "notifKey",
                    appName = "Fake app name",
                )
            )

            assertThat((latest as OngoingActivityChipModel.Active).icon)
                .isInstanceOf(
                    OngoingActivityChipModel.ChipIcon.StatusBarNotificationIcon::class.java
                )
            val actualIcon =
                (latest as OngoingActivityChipModel.Active).icon
                    as OngoingActivityChipModel.ChipIcon.StatusBarNotificationIcon
            assertThat(actualIcon.notificationKey).isEqualTo("notifKey")
            assertThat(actualIcon.contentDescription.loadContentDescription(context))
                .contains("Ongoing call")
            assertThat(actualIcon.contentDescription.loadContentDescription(context))
                .contains("Fake app name")
        }

    @Test
    @DisableFlags(StatusBarConnectedDisplays.FLAG_NAME)
    fun chip_notifIconFlagOn_butNullNotifIcon_cdFlagOff_iconIsPhone() =
        testScope.runTest {
            val latest by collectLastValue(underTest.chip)

            repo.setOngoingCallState(inCallModel(startTimeMs = 1000, notificationIcon = null))

            assertThat((latest as OngoingActivityChipModel.Active).icon)
                .isInstanceOf(OngoingActivityChipModel.ChipIcon.SingleColorIcon::class.java)
            val icon =
                (((latest as OngoingActivityChipModel.Active).icon)
                        as OngoingActivityChipModel.ChipIcon.SingleColorIcon)
                    .impl as Icon.Resource
            assertThat(icon.res).isEqualTo(com.android.internal.R.drawable.ic_phone)
            assertThat(icon.contentDescription).isNotNull()
        }

    @Test
    @EnableFlags(StatusBarConnectedDisplays.FLAG_NAME)
    fun chip_notifIconFlagOn_butNullNotifIcon_cdFlagOn_iconIsNotifKeyIcon_withContentDescription() =
        testScope.runTest {
            val latest by collectLastValue(underTest.chip)

            repo.setOngoingCallState(
                inCallModel(
                    startTimeMs = 1000,
                    notificationIcon = null,
                    notificationKey = "notifKey",
                    appName = "Fake app name",
                )
            )

            assertThat((latest as OngoingActivityChipModel.Active).icon)
                .isInstanceOf(
                    OngoingActivityChipModel.ChipIcon.StatusBarNotificationIcon::class.java
                )
            val actualIcon =
                (latest as OngoingActivityChipModel.Active).icon
                    as OngoingActivityChipModel.ChipIcon.StatusBarNotificationIcon
            assertThat(actualIcon.notificationKey).isEqualTo("notifKey")
            assertThat(actualIcon.contentDescription.loadContentDescription(context))
                .contains("Ongoing call")
            assertThat(actualIcon.contentDescription.loadContentDescription(context))
                .contains("Fake app name")
        }

    @Test
    fun chip_positiveStartTime_notPromoted_colorsAreThemed() =
        testScope.runTest {
            val latest by collectLastValue(underTest.chip)

            repo.setOngoingCallState(inCallModel(startTimeMs = 1000, promotedContent = null))

            assertThat((latest as OngoingActivityChipModel.Active).colors)
                .isEqualTo(ColorsModel.Themed)
        }

    @Test
    fun chip_zeroStartTime_notPromoted_colorsAreThemed() =
        testScope.runTest {
            val latest by collectLastValue(underTest.chip)

            repo.setOngoingCallState(inCallModel(startTimeMs = 0, promotedContent = null))

            assertThat((latest as OngoingActivityChipModel.Active).colors)
                .isEqualTo(ColorsModel.Themed)
        }

    @Test
    @DisableFlags(StatusBarNotifChips.FLAG_NAME)
    fun chip_positiveStartTime_promoted_notifChipsFlagOff_colorsAreThemed() =
        testScope.runTest {
            val latest by collectLastValue(underTest.chip)

            repo.setOngoingCallState(
                inCallModel(startTimeMs = 1000, promotedContent = PROMOTED_CONTENT_WITH_COLOR)
            )

            assertThat((latest as OngoingActivityChipModel.Active).colors)
                .isEqualTo(ColorsModel.Themed)
        }

    @Test
    @DisableFlags(StatusBarNotifChips.FLAG_NAME)
    fun chip_zeroStartTime_promoted_notifChipsFlagOff_colorsAreThemed() =
        testScope.runTest {
            val latest by collectLastValue(underTest.chip)

            repo.setOngoingCallState(
                inCallModel(startTimeMs = 0, promotedContent = PROMOTED_CONTENT_WITH_COLOR)
            )

            assertThat((latest as OngoingActivityChipModel.Active).colors)
                .isEqualTo(ColorsModel.Themed)
        }

    @Test
    @EnableFlags(StatusBarNotifChips.FLAG_NAME)
    fun chip_positiveStartTime_promoted_notifChipsFlagOn_colorsAreCustom() =
        testScope.runTest {
            val latest by collectLastValue(underTest.chip)

            repo.setOngoingCallState(
                inCallModel(startTimeMs = 1000, promotedContent = PROMOTED_CONTENT_WITH_COLOR)
            )

            assertThat((latest as OngoingActivityChipModel.Active).colors)
                .isEqualTo(
                    ColorsModel.Custom(
                        backgroundColorInt = PROMOTED_BACKGROUND_COLOR,
                        primaryTextColorInt = PROMOTED_PRIMARY_TEXT_COLOR,
                    )
                )
        }

    @Test
    @EnableFlags(StatusBarNotifChips.FLAG_NAME)
    fun chip_zeroStartTime_promoted_notifChipsFlagOff_colorsAreCustom() =
        testScope.runTest {
            val latest by collectLastValue(underTest.chip)

            repo.setOngoingCallState(
                inCallModel(startTimeMs = 0, promotedContent = PROMOTED_CONTENT_WITH_COLOR)
            )

            assertThat((latest as OngoingActivityChipModel.Active).colors)
                .isEqualTo(
                    ColorsModel.Custom(
                        backgroundColorInt = PROMOTED_BACKGROUND_COLOR,
                        primaryTextColorInt = PROMOTED_PRIMARY_TEXT_COLOR,
                    )
                )
        }

    @Test
    fun chip_resetsCorrectly() =
        testScope.runTest {
            val latest by collectLastValue(underTest.chip)
            kosmos.fakeSystemClock.setCurrentTimeMillis(3000)
            kosmos.fakeSystemClock.setElapsedRealtime(400_000)

            // Start a call
            repo.setOngoingCallState(inCallModel(startTimeMs = 1000))
            assertThat(latest).isInstanceOf(OngoingActivityChipModel.Active::class.java)
            assertThat((latest as OngoingActivityChipModel.Active.Timer).startTimeMs)
                .isEqualTo(398_000)

            // End the call
            repo.setOngoingCallState(OngoingCallModel.NoCall)
            assertThat(latest).isInstanceOf(OngoingActivityChipModel.Inactive::class.java)

            // Let 100_000ms elapse
            kosmos.fakeSystemClock.setCurrentTimeMillis(103_000)
            kosmos.fakeSystemClock.setElapsedRealtime(500_000)

            // Start a new call, which started 1000ms ago
            repo.setOngoingCallState(inCallModel(startTimeMs = 102_000))
            assertThat(latest).isInstanceOf(OngoingActivityChipModel.Active::class.java)
            assertThat((latest as OngoingActivityChipModel.Active.Timer).startTimeMs)
                .isEqualTo(499_000)
        }

    @Test
    @DisableFlags(StatusBarChipsModernization.FLAG_NAME)
    fun chip_inCall_nullIntent_nullClickListener() =
        testScope.runTest {
            val latest by collectLastValue(underTest.chip)

            repo.setOngoingCallState(inCallModel(startTimeMs = 1000, intent = null))

            assertThat((latest as OngoingActivityChipModel.Active).onClickListenerLegacy).isNull()
        }

    @Test
    @DisableFlags(StatusBarChipsModernization.FLAG_NAME)
    fun chip_inCall_positiveStartTime_validIntent_clickListenerLaunchesIntent() =
        testScope.runTest {
            val latest by collectLastValue(underTest.chip)

            val pendingIntent = mock<PendingIntent>()
            repo.setOngoingCallState(inCallModel(startTimeMs = 1000, intent = pendingIntent))
            val clickListener = (latest as OngoingActivityChipModel.Active).onClickListenerLegacy
            assertThat(clickListener).isNotNull()

            clickListener!!.onClick(chipView)

            // Ensure that the SysUI didn't modify the notification's intent by verifying it
            // directly matches the `PendingIntent` set -- see b/212467440.
            verify(kosmos.activityStarter).postStartActivityDismissingKeyguard(pendingIntent, null)
        }

    @Test
    @DisableFlags(StatusBarChipsModernization.FLAG_NAME)
    fun chip_inCall_zeroStartTime_validIntent_clickListenerLaunchesIntent() =
        testScope.runTest {
            val latest by collectLastValue(underTest.chip)

            val pendingIntent = mock<PendingIntent>()
            repo.setOngoingCallState(inCallModel(startTimeMs = 0, intent = pendingIntent))
            val clickListener = (latest as OngoingActivityChipModel.Active).onClickListenerLegacy

            assertThat(clickListener).isNotNull()

            clickListener!!.onClick(chipView)

            // Ensure that the SysUI didn't modify the notification's intent by verifying it
            // directly matches the `PendingIntent` set -- see b/212467440.
            verify(kosmos.activityStarter).postStartActivityDismissingKeyguard(pendingIntent, null)
        }

    @Test
    @EnableFlags(StatusBarRootModernization.FLAG_NAME, StatusBarChipsModernization.FLAG_NAME)
    fun chip_inCall_nullIntent_noneClickBehavior() =
        testScope.runTest {
            val latest by collectLastValue(underTest.chip)

            postOngoingCallNotification(
                repository = notificationListRepository,
                startTimeMs = 1000L,
                intent = null,
            )

            assertThat((latest as OngoingActivityChipModel.Active).clickBehavior)
                .isInstanceOf(OngoingActivityChipModel.ClickBehavior.None::class.java)
        }

    @Test
    @EnableFlags(StatusBarRootModernization.FLAG_NAME, StatusBarChipsModernization.FLAG_NAME)
    fun chip_inCall_positiveStartTime_validIntent_clickBehaviorLaunchesIntent() =
        testScope.runTest {
            val latest by collectLastValue(underTest.chip)

            val pendingIntent = mock<PendingIntent>()
            postOngoingCallNotification(
                repository = notificationListRepository,
                startTimeMs = 1000L,
                intent = pendingIntent,
            )

            val clickBehavior = (latest as OngoingActivityChipModel.Active).clickBehavior
            assertThat(clickBehavior)
                .isInstanceOf(OngoingActivityChipModel.ClickBehavior.ExpandAction::class.java)
            (clickBehavior as OngoingActivityChipModel.ClickBehavior.ExpandAction).onClick(
                mockExpandable
            )

            // Ensure that the SysUI didn't modify the notification's intent by verifying it
            // directly matches the `PendingIntent` set -- see b/212467440.
            verify(kosmos.activityStarter).postStartActivityDismissingKeyguard(pendingIntent, null)
        }

    @Test
    @EnableFlags(StatusBarRootModernization.FLAG_NAME, StatusBarChipsModernization.FLAG_NAME)
    fun chip_inCall_zeroStartTime_validIntent_clickBehaviorLaunchesIntent() =
        testScope.runTest {
            val latest by collectLastValue(underTest.chip)

            val pendingIntent = mock<PendingIntent>()
            postOngoingCallNotification(
                repository = notificationListRepository,
                startTimeMs = 0L,
                intent = pendingIntent,
            )

            val clickBehavior = (latest as OngoingActivityChipModel.Active).clickBehavior
            assertThat(clickBehavior)
                .isInstanceOf(OngoingActivityChipModel.ClickBehavior.ExpandAction::class.java)
            (clickBehavior as OngoingActivityChipModel.ClickBehavior.ExpandAction).onClick(
                mockExpandable
            )

            // Ensure that the SysUI didn't modify the notification's intent by verifying it
            // directly matches the `PendingIntent` set -- see b/212467440.
            verify(kosmos.activityStarter).postStartActivityDismissingKeyguard(pendingIntent, null)
        }

    companion object {
        fun createStatusBarIconViewOrNull(): StatusBarIconView? =
            if (StatusBarConnectedDisplays.isEnabled) {
                null
            } else {
                mock<StatusBarIconView>()
            }

        fun postOngoingCallNotification(
            repository: ActiveNotificationListRepository,
            startTimeMs: Long,
            intent: PendingIntent?,
        ) {
            repository.activeNotifications.value =
                ActiveNotificationsStore.Builder()
                    .apply {
                        addIndividualNotif(
                            activeNotificationModel(
                                key = "notif1",
                                whenTime = startTimeMs,
                                callType = CallType.Ongoing,
                                statusBarChipIcon = null,
                                contentIntent = intent,
                            )
                        )
                    }
                    .build()
        }

        private val PROMOTED_CONTENT_WITH_COLOR =
            PromotedNotificationContentModel.Builder("notif")
                .apply {
                    this.colors =
                        PromotedNotificationContentModel.Colors(
                            backgroundColor = PROMOTED_BACKGROUND_COLOR,
                            primaryTextColor = PROMOTED_PRIMARY_TEXT_COLOR,
                        )
                }
                .build()

        private const val PROMOTED_BACKGROUND_COLOR = 65
        private const val PROMOTED_PRIMARY_TEXT_COLOR = 98
    }
}
