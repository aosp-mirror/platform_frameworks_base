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

import android.content.DialogInterface
import android.content.packageManager
import android.content.res.Configuration
import android.content.res.mainResources
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.platform.test.annotations.DisableFlags
import android.platform.test.annotations.EnableFlags
import android.view.View
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.animation.Expandable
import com.android.systemui.common.ui.data.repository.fakeConfigurationRepository
import com.android.systemui.display.data.repository.displayStateRepository
import com.android.systemui.kosmos.Kosmos
import com.android.systemui.kosmos.collectLastValue
import com.android.systemui.kosmos.runCurrent
import com.android.systemui.kosmos.runTest
import com.android.systemui.kosmos.testScope
import com.android.systemui.kosmos.useUnconfinedTestDispatcher
import com.android.systemui.mediaprojection.data.model.MediaProjectionState
import com.android.systemui.mediaprojection.data.repository.fakeMediaProjectionRepository
import com.android.systemui.mediaprojection.taskswitcher.FakeActivityTaskManager.Companion.createTask
import com.android.systemui.res.R
import com.android.systemui.screenrecord.data.model.ScreenRecordModel
import com.android.systemui.screenrecord.data.repository.screenRecordRepository
import com.android.systemui.statusbar.chips.call.ui.viewmodel.CallChipViewModelTest.Companion.createStatusBarIconViewOrNull
import com.android.systemui.statusbar.chips.mediaprojection.domain.interactor.MediaProjectionChipInteractorTest.Companion.NORMAL_PACKAGE
import com.android.systemui.statusbar.chips.mediaprojection.domain.interactor.MediaProjectionChipInteractorTest.Companion.setUpPackageManagerForMediaProjection
import com.android.systemui.statusbar.chips.notification.domain.interactor.statusBarNotificationChipsInteractor
import com.android.systemui.statusbar.chips.notification.shared.StatusBarNotifChips
import com.android.systemui.statusbar.chips.notification.ui.viewmodel.NotifChipsViewModelTest.Companion.assertIsNotifChip
import com.android.systemui.statusbar.chips.ui.model.MultipleOngoingActivityChipsModel
import com.android.systemui.statusbar.chips.ui.model.MultipleOngoingActivityChipsModelLegacy
import com.android.systemui.statusbar.chips.ui.model.OngoingActivityChipModel
import com.android.systemui.statusbar.chips.ui.view.ChipBackgroundContainer
import com.android.systemui.statusbar.chips.ui.viewmodel.OngoingActivityChipsViewModelTest.Companion.assertIsCallChip
import com.android.systemui.statusbar.chips.ui.viewmodel.OngoingActivityChipsViewModelTest.Companion.assertIsScreenRecordChip
import com.android.systemui.statusbar.chips.ui.viewmodel.OngoingActivityChipsViewModelTest.Companion.assertIsShareToAppChip
import com.android.systemui.statusbar.chips.ui.viewmodel.OngoingActivityChipsViewModelTest.Companion.getStopActionFromDialog
import com.android.systemui.statusbar.core.StatusBarRootModernization
import com.android.systemui.statusbar.notification.data.model.activeNotificationModel
import com.android.systemui.statusbar.notification.data.repository.ActiveNotificationsStore
import com.android.systemui.statusbar.notification.data.repository.activeNotificationListRepository
import com.android.systemui.statusbar.notification.promoted.shared.model.PromotedNotificationContentModel
import com.android.systemui.statusbar.notification.shared.ActiveNotificationModel
import com.android.systemui.statusbar.notification.shared.CallType
import com.android.systemui.statusbar.phone.SystemUIDialog
import com.android.systemui.statusbar.phone.ongoingcall.StatusBarChipsModernization
import com.android.systemui.statusbar.phone.ongoingcall.data.repository.ongoingCallRepository
import com.android.systemui.statusbar.phone.ongoingcall.shared.model.OngoingCallModel
import com.android.systemui.statusbar.phone.ongoingcall.shared.model.inCallModel
import com.android.systemui.testKosmos
import com.android.systemui.util.time.fakeSystemClock
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import org.junit.Before
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

// TODO(b/273205603): add tests for Active chips with hidden=true once actually used.
/** Tests for [OngoingActivityChipsViewModel] when the [StatusBarNotifChips] flag is enabled. */
@SmallTest
@RunWith(AndroidJUnit4::class)
@EnableFlags(StatusBarNotifChips.FLAG_NAME)
class OngoingActivityChipsWithNotifsViewModelTest : SysuiTestCase() {
    private val kosmos = testKosmos().useUnconfinedTestDispatcher()
    private val systemClock = kosmos.fakeSystemClock

    private val screenRecordState = kosmos.screenRecordRepository.screenRecordState
    private val mediaProjectionState = kosmos.fakeMediaProjectionRepository.mediaProjectionState
    private val callRepo = kosmos.ongoingCallRepository
    private val activeNotificationListRepository = kosmos.activeNotificationListRepository

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
    private val mockExpandable: Expandable =
        mock<Expandable>().apply { whenever(dialogTransitionController(any())).thenReturn(mock()) }

    private val Kosmos.underTest by Kosmos.Fixture { ongoingActivityChipsViewModel }

    @Before
    fun setUp() {
        setUpPackageManagerForMediaProjection(kosmos)
        kosmos.statusBarNotificationChipsInteractor.start()
        val icon =
            BitmapDrawable(
                context.resources,
                Bitmap.createBitmap(/* width= */ 100, /* height= */ 100, Bitmap.Config.ARGB_8888),
            )
        whenever(kosmos.packageManager.getApplicationIcon(any<String>())).thenReturn(icon)
    }

    // Even though the `primaryChip` flow isn't used when the notifs flag is on, still test that the
    // flow has the right behavior to verify that we don't break any existing functionality.

    @Test
    fun primaryChip_allHidden_hidden() =
        kosmos.runTest {
            screenRecordState.value = ScreenRecordModel.DoingNothing
            mediaProjectionState.value = MediaProjectionState.NotProjecting
            callRepo.setOngoingCallState(OngoingCallModel.NoCall)

            val latest by collectLastValue(underTest.primaryChip)

            assertThat(latest).isInstanceOf(OngoingActivityChipModel.Inactive::class.java)
        }

    @DisableFlags(StatusBarRootModernization.FLAG_NAME, StatusBarChipsModernization.FLAG_NAME)
    @Test
    fun chipsLegacy_allHidden_bothPrimaryAndSecondaryHidden() =
        kosmos.runTest {
            screenRecordState.value = ScreenRecordModel.DoingNothing
            mediaProjectionState.value = MediaProjectionState.NotProjecting
            callRepo.setOngoingCallState(OngoingCallModel.NoCall)

            val latest by collectLastValue(underTest.chipsLegacy)
            val unused by collectLastValue(underTest.chips)

            assertThat(latest!!.primary).isInstanceOf(OngoingActivityChipModel.Inactive::class.java)
            assertThat(latest!!.secondary)
                .isInstanceOf(OngoingActivityChipModel.Inactive::class.java)
            assertThat(unused).isEqualTo(MultipleOngoingActivityChipsModel())
        }

    @EnableFlags(StatusBarRootModernization.FLAG_NAME, StatusBarChipsModernization.FLAG_NAME)
    @Test
    fun chips_allInactive() =
        kosmos.runTest {
            screenRecordState.value = ScreenRecordModel.DoingNothing
            mediaProjectionState.value = MediaProjectionState.NotProjecting
            setNotifs(emptyList())

            val latest by collectLastValue(underTest.chips)
            val unused by collectLastValue(underTest.chipsLegacy)

            assertThat(latest!!.active).isEmpty()
            assertThat(latest!!.overflow).isEmpty()
            assertThat(latest!!.inactive.size).isEqualTo(4)
            assertThat(unused).isEqualTo(MultipleOngoingActivityChipsModelLegacy())
        }

    @Test
    fun primaryChip_screenRecordShow_restHidden_screenRecordShown() =
        kosmos.runTest {
            screenRecordState.value = ScreenRecordModel.Recording
            mediaProjectionState.value = MediaProjectionState.NotProjecting
            callRepo.setOngoingCallState(OngoingCallModel.NoCall)

            val latest by collectLastValue(underTest.primaryChip)

            assertIsScreenRecordChip(latest)
        }

    @DisableFlags(StatusBarRootModernization.FLAG_NAME, StatusBarChipsModernization.FLAG_NAME)
    @Test
    fun chipsLegacy_screenRecordShow_restHidden_primaryIsScreenRecordSecondaryIsHidden() =
        kosmos.runTest {
            screenRecordState.value = ScreenRecordModel.Recording
            mediaProjectionState.value = MediaProjectionState.NotProjecting
            callRepo.setOngoingCallState(OngoingCallModel.NoCall)

            val latest by collectLastValue(underTest.chipsLegacy)
            val unused by collectLastValue(underTest.chips)

            assertIsScreenRecordChip(latest!!.primary)
            assertThat(latest!!.secondary)
                .isInstanceOf(OngoingActivityChipModel.Inactive::class.java)
            assertThat(unused).isEqualTo(MultipleOngoingActivityChipsModel())
        }

    @EnableFlags(StatusBarRootModernization.FLAG_NAME, StatusBarChipsModernization.FLAG_NAME)
    @Test
    fun chips_screenRecordActive_restInactive() =
        kosmos.runTest {
            screenRecordState.value = ScreenRecordModel.Recording
            mediaProjectionState.value = MediaProjectionState.NotProjecting
            setNotifs(emptyList())

            val latest by collectLastValue(underTest.chips)
            val unused by collectLastValue(underTest.chipsLegacy)

            assertThat(latest!!.active.size).isEqualTo(1)
            assertIsScreenRecordChip(latest!!.active[0])
            assertThat(latest!!.overflow).isEmpty()
            assertThat(latest!!.inactive.size).isEqualTo(3)
            assertThat(unused).isEqualTo(MultipleOngoingActivityChipsModelLegacy())
        }

    @Test
    fun primaryChip_screenRecordShowAndCallShow_screenRecordShown() =
        kosmos.runTest {
            screenRecordState.value = ScreenRecordModel.Recording
            callRepo.setOngoingCallState(inCallModel(startTimeMs = 34))

            val latest by collectLastValue(underTest.primaryChip)

            assertIsScreenRecordChip(latest)
        }

    @DisableFlags(StatusBarRootModernization.FLAG_NAME, StatusBarChipsModernization.FLAG_NAME)
    @Test
    fun chipsLegacy_screenRecordShowAndCallShow_primaryIsScreenRecordSecondaryIsCall() =
        kosmos.runTest {
            val callNotificationKey = "call"
            screenRecordState.value = ScreenRecordModel.Recording
            callRepo.setOngoingCallState(
                inCallModel(startTimeMs = 34, notificationKey = callNotificationKey)
            )

            val latest by collectLastValue(underTest.chipsLegacy)
            val unused by collectLastValue(underTest.chips)

            assertIsScreenRecordChip(latest!!.primary)
            assertIsCallChip(latest!!.secondary, callNotificationKey)
            assertThat(unused).isEqualTo(MultipleOngoingActivityChipsModel())
        }

    @EnableFlags(StatusBarRootModernization.FLAG_NAME, StatusBarChipsModernization.FLAG_NAME)
    @Test
    fun chips_screenRecordAndCallActive_inThatOrder() =
        kosmos.runTest {
            val callNotificationKey = "call"
            screenRecordState.value = ScreenRecordModel.Recording
            setNotifs(
                listOf(
                    activeNotificationModel(
                        key = "call",
                        statusBarChipIcon = createStatusBarIconViewOrNull(),
                        callType = CallType.Ongoing,
                        whenTime = 499,
                    )
                )
            )

            val latest by collectLastValue(underTest.chips)
            val unused by collectLastValue(underTest.chipsLegacy)

            assertThat(latest!!.active.size).isEqualTo(2)
            assertIsScreenRecordChip(latest!!.active[0])
            assertIsCallChip(latest!!.active[1], callNotificationKey)
            assertThat(latest!!.overflow).isEmpty()
            assertThat(latest!!.inactive.size).isEqualTo(2)
            assertThat(unused).isEqualTo(MultipleOngoingActivityChipsModelLegacy())
        }

    @DisableFlags(StatusBarRootModernization.FLAG_NAME, StatusBarChipsModernization.FLAG_NAME)
    @Test
    fun chipsLegacy_oneChip_notSquished() =
        kosmos.runTest {
            callRepo.setOngoingCallState(inCallModel(startTimeMs = 34, notificationKey = "call"))

            val latest by collectLastValue(underTest.chipsLegacy)

            // The call chip isn't squished (squished chips would be icon only)
            assertThat(latest!!.primary)
                .isInstanceOf(OngoingActivityChipModel.Active.Timer::class.java)
        }

    @EnableFlags(StatusBarRootModernization.FLAG_NAME, StatusBarChipsModernization.FLAG_NAME)
    @Test
    fun chips_oneChip_notSquished() =
        kosmos.runTest {
            val callNotificationKey = "call"
            setNotifs(
                listOf(
                    activeNotificationModel(
                        key = callNotificationKey,
                        statusBarChipIcon = createStatusBarIconViewOrNull(),
                        callType = CallType.Ongoing,
                        whenTime = 499,
                    )
                )
            )

            val latest by collectLastValue(underTest.chips)

            // The call chip isn't squished (squished chips would be icon only)
            assertThat(latest!!.active[0])
                .isInstanceOf(OngoingActivityChipModel.Active.Timer::class.java)
        }

    @DisableFlags(StatusBarChipsModernization.FLAG_NAME, StatusBarRootModernization.FLAG_NAME)
    @Test
    fun chipsLegacy_twoTimerChips_isSmallPortrait_andChipsModernizationDisabled_bothSquished() =
        kosmos.runTest {
            screenRecordState.value = ScreenRecordModel.Recording
            callRepo.setOngoingCallState(inCallModel(startTimeMs = 34, notificationKey = "call"))

            val latest by collectLastValue(underTest.chipsLegacy)

            // Squished chips are icon only
            assertThat(latest!!.primary)
                .isInstanceOf(OngoingActivityChipModel.Active.IconOnly::class.java)
            assertThat(latest!!.secondary)
                .isInstanceOf(OngoingActivityChipModel.Active.IconOnly::class.java)
        }

    @DisableFlags(StatusBarChipsModernization.FLAG_NAME, StatusBarRootModernization.FLAG_NAME)
    @Test
    fun chipsLegacy_countdownChipAndTimerChip_countdownNotSquished_butTimerSquished() =
        kosmos.runTest {
            screenRecordState.value = ScreenRecordModel.Starting(millisUntilStarted = 2000)
            callRepo.setOngoingCallState(inCallModel(startTimeMs = 34, notificationKey = "call"))

            val latest by collectLastValue(underTest.chipsLegacy)

            // The screen record countdown isn't squished to icon-only
            assertThat(latest!!.primary)
                .isInstanceOf(OngoingActivityChipModel.Active.Countdown::class.java)
            // But the call chip *is* squished
            assertThat(latest!!.secondary)
                .isInstanceOf(OngoingActivityChipModel.Active.IconOnly::class.java)
        }

    @DisableFlags(StatusBarChipsModernization.FLAG_NAME, StatusBarRootModernization.FLAG_NAME)
    @Test
    fun chipsLegacy_numberOfChipsChanges_chipsGetSquishedAndUnsquished() =
        kosmos.runTest {
            val latest by collectLastValue(underTest.chipsLegacy)

            // WHEN there's only one chip
            screenRecordState.value = ScreenRecordModel.Recording
            callRepo.setOngoingCallState(OngoingCallModel.NoCall)

            // The screen record isn't squished because it's the only one
            assertThat(latest!!.primary)
                .isInstanceOf(OngoingActivityChipModel.Active.Timer::class.java)
            assertThat(latest!!.secondary)
                .isInstanceOf(OngoingActivityChipModel.Inactive::class.java)

            // WHEN there's 2 chips
            callRepo.setOngoingCallState(inCallModel(startTimeMs = 34, notificationKey = "call"))

            // THEN they both become squished
            assertThat(latest!!.primary)
                .isInstanceOf(OngoingActivityChipModel.Active.IconOnly::class.java)
            // But the call chip *is* squished
            assertThat(latest!!.secondary)
                .isInstanceOf(OngoingActivityChipModel.Active.IconOnly::class.java)

            // WHEN we go back down to 1 chip
            screenRecordState.value = ScreenRecordModel.DoingNothing

            // THEN the remaining chip unsquishes
            assertThat(latest!!.primary)
                .isInstanceOf(OngoingActivityChipModel.Active.Timer::class.java)
            assertThat(latest!!.secondary)
                .isInstanceOf(OngoingActivityChipModel.Inactive::class.java)
        }

    @DisableFlags(StatusBarChipsModernization.FLAG_NAME, StatusBarRootModernization.FLAG_NAME)
    @Test
    fun chipsLegacy_twoChips_isLandscape_notSquished() =
        kosmos.runTest {
            screenRecordState.value = ScreenRecordModel.Recording
            callRepo.setOngoingCallState(inCallModel(startTimeMs = 34, notificationKey = "call"))

            // WHEN we're in landscape
            val config =
                Configuration(kosmos.mainResources.configuration).apply {
                    orientation = Configuration.ORIENTATION_LANDSCAPE
                }
            kosmos.fakeConfigurationRepository.onConfigurationChange(config)

            val latest by collectLastValue(underTest.chipsLegacy)

            // THEN the chips aren't squished (squished chips would be icon only)
            assertThat(latest!!.primary)
                .isInstanceOf(OngoingActivityChipModel.Active.Timer::class.java)
            assertThat(latest!!.secondary)
                .isInstanceOf(OngoingActivityChipModel.Active.Timer::class.java)
        }

    @DisableFlags(StatusBarChipsModernization.FLAG_NAME, StatusBarRootModernization.FLAG_NAME)
    @Test
    fun chipsLegacy_twoChips_isLargeScreen_notSquished() =
        kosmos.runTest {
            screenRecordState.value = ScreenRecordModel.Recording
            callRepo.setOngoingCallState(inCallModel(startTimeMs = 34, notificationKey = "call"))

            // WHEN we're on a large screen
            kosmos.displayStateRepository.setIsLargeScreen(true)

            val latest by collectLastValue(underTest.chipsLegacy)

            // THEN the chips aren't squished (squished chips would be icon only)
            assertThat(latest!!.primary)
                .isInstanceOf(OngoingActivityChipModel.Active.Timer::class.java)
            assertThat(latest!!.secondary)
                .isInstanceOf(OngoingActivityChipModel.Active.Timer::class.java)
        }

    @Test
    @EnableFlags(StatusBarChipsModernization.FLAG_NAME, StatusBarRootModernization.FLAG_NAME)
    fun chips_twoChips_chipsModernizationEnabled_notSquished() =
        kosmos.runTest {
            screenRecordState.value = ScreenRecordModel.Recording
            setNotifs(
                listOf(
                    activeNotificationModel(
                        key = "call",
                        statusBarChipIcon = createStatusBarIconViewOrNull(),
                        callType = CallType.Ongoing,
                        whenTime = 499,
                    )
                )
            )

            val latest by collectLastValue(underTest.chips)

            // Squished chips would be icon only
            assertThat(latest!!.active[0])
                .isInstanceOf(OngoingActivityChipModel.Active.Timer::class.java)
            assertThat(latest!!.active[1])
                .isInstanceOf(OngoingActivityChipModel.Active.Timer::class.java)
        }

    @Test
    fun primaryChip_screenRecordShowAndShareToAppShow_screenRecordShown() =
        kosmos.runTest {
            screenRecordState.value = ScreenRecordModel.Recording
            mediaProjectionState.value =
                MediaProjectionState.Projecting.EntireScreen(NORMAL_PACKAGE)
            callRepo.setOngoingCallState(OngoingCallModel.NoCall)

            val latest by collectLastValue(underTest.primaryChip)

            assertIsScreenRecordChip(latest)
        }

    @DisableFlags(StatusBarRootModernization.FLAG_NAME, StatusBarChipsModernization.FLAG_NAME)
    @Test
    fun chipsLegacy_screenRecordShowAndShareToAppShow_primaryIsScreenRecordSecondaryIsHidden() =
        kosmos.runTest {
            screenRecordState.value = ScreenRecordModel.Recording
            mediaProjectionState.value =
                MediaProjectionState.Projecting.EntireScreen(NORMAL_PACKAGE)
            callRepo.setOngoingCallState(OngoingCallModel.NoCall)

            val latest by collectLastValue(underTest.chipsLegacy)
            val unused by collectLastValue(underTest.chips)

            assertIsScreenRecordChip(latest!!.primary)
            // Even though share-to-app is active, we suppress it because this share-to-app is
            // represented by screen record being active. See b/296461748.
            assertThat(latest!!.secondary)
                .isInstanceOf(OngoingActivityChipModel.Inactive::class.java)
            assertThat(unused).isEqualTo(MultipleOngoingActivityChipsModel())
        }

    @EnableFlags(StatusBarRootModernization.FLAG_NAME, StatusBarChipsModernization.FLAG_NAME)
    @Test
    fun chips_screenRecordAndShareToApp_screenRecordIsActiveShareToAppIsInOverflow() =
        kosmos.runTest {
            screenRecordState.value = ScreenRecordModel.Recording
            mediaProjectionState.value =
                MediaProjectionState.Projecting.EntireScreen(NORMAL_PACKAGE)
            setNotifs(emptyList())

            val latest by collectLastValue(underTest.chips)
            val unused by collectLastValue(underTest.chipsLegacy)

            assertThat(latest!!.active.size).isEqualTo(1)
            assertIsScreenRecordChip(latest!!.active[0])
            // Even though share-to-app is active, we suppress it because this share-to-app is
            // represented by screen record being active. See b/296461748.
            assertThat(latest!!.overflow.size).isEqualTo(1)
            assertIsShareToAppChip(latest!!.overflow[0])
            assertThat(latest!!.inactive.size).isEqualTo(2)
            assertThat(unused).isEqualTo(MultipleOngoingActivityChipsModelLegacy())
        }

    @Test
    fun primaryChip_shareToAppShowAndCallShow_shareToAppShown() =
        kosmos.runTest {
            screenRecordState.value = ScreenRecordModel.DoingNothing
            mediaProjectionState.value =
                MediaProjectionState.Projecting.EntireScreen(NORMAL_PACKAGE)
            callRepo.setOngoingCallState(inCallModel(startTimeMs = 34))

            val latest by collectLastValue(underTest.primaryChip)

            assertIsShareToAppChip(latest)
        }

    @DisableFlags(StatusBarRootModernization.FLAG_NAME, StatusBarChipsModernization.FLAG_NAME)
    @Test
    fun chipsLegacy_shareToAppShowAndCallShow_primaryIsShareToAppSecondaryIsCall() =
        kosmos.runTest {
            val callNotificationKey = "call"
            screenRecordState.value = ScreenRecordModel.DoingNothing
            mediaProjectionState.value =
                MediaProjectionState.Projecting.EntireScreen(NORMAL_PACKAGE)
            callRepo.setOngoingCallState(
                inCallModel(startTimeMs = 34, notificationKey = callNotificationKey)
            )

            val latest by collectLastValue(underTest.chipsLegacy)
            val unused by collectLastValue(underTest.chips)

            assertIsShareToAppChip(latest!!.primary)
            assertIsCallChip(latest!!.secondary, callNotificationKey)
            assertThat(unused).isEqualTo(MultipleOngoingActivityChipsModel())
        }

    @EnableFlags(StatusBarRootModernization.FLAG_NAME, StatusBarChipsModernization.FLAG_NAME)
    @Test
    fun chips_shareToAppAndCallActive() =
        kosmos.runTest {
            val callNotificationKey = "call"
            screenRecordState.value = ScreenRecordModel.DoingNothing
            mediaProjectionState.value =
                MediaProjectionState.Projecting.EntireScreen(NORMAL_PACKAGE)
            setNotifs(
                listOf(
                    activeNotificationModel(
                        key = callNotificationKey,
                        statusBarChipIcon = createStatusBarIconViewOrNull(),
                        callType = CallType.Ongoing,
                        whenTime = 499,
                    )
                )
            )

            val latest by collectLastValue(underTest.chips)
            val unused by collectLastValue(underTest.chipsLegacy)

            assertThat(latest!!.active.size).isEqualTo(2)
            assertIsShareToAppChip(latest!!.active[0])
            assertIsCallChip(latest!!.active[1], callNotificationKey)
            assertThat(latest!!.overflow).isEmpty()
            assertThat(latest!!.inactive.size).isEqualTo(2)
            assertThat(unused).isEqualTo(MultipleOngoingActivityChipsModelLegacy())
        }

    @Test
    fun primaryChip_onlyCallShown_callShown() =
        kosmos.runTest {
            screenRecordState.value = ScreenRecordModel.DoingNothing
            // MediaProjection covers both share-to-app and cast-to-other-device
            mediaProjectionState.value = MediaProjectionState.NotProjecting

            val callNotificationKey = "call"
            callRepo.setOngoingCallState(
                inCallModel(startTimeMs = 34, notificationKey = callNotificationKey)
            )

            val latest by collectLastValue(underTest.primaryChip)

            assertIsCallChip(latest, callNotificationKey)
        }

    @DisableFlags(StatusBarRootModernization.FLAG_NAME, StatusBarChipsModernization.FLAG_NAME)
    @Test
    fun chipsLegacy_onlyCallShown_primaryIsCallSecondaryIsHidden() =
        kosmos.runTest {
            val callNotificationKey = "call"
            screenRecordState.value = ScreenRecordModel.DoingNothing
            // MediaProjection covers both share-to-app and cast-to-other-device
            mediaProjectionState.value = MediaProjectionState.NotProjecting

            callRepo.setOngoingCallState(
                inCallModel(startTimeMs = 34, notificationKey = callNotificationKey)
            )

            val latest by collectLastValue(underTest.chipsLegacy)
            val unused by collectLastValue(underTest.chips)

            assertIsCallChip(latest!!.primary, callNotificationKey)
            assertThat(latest!!.secondary)
                .isInstanceOf(OngoingActivityChipModel.Inactive::class.java)
            assertThat(unused).isEqualTo(MultipleOngoingActivityChipsModel())
        }

    @EnableFlags(StatusBarRootModernization.FLAG_NAME, StatusBarChipsModernization.FLAG_NAME)
    @Test
    fun chips_callActive_restInactive() =
        kosmos.runTest {
            val callNotificationKey = "call"
            screenRecordState.value = ScreenRecordModel.DoingNothing
            // MediaProjection covers both share-to-app and cast-to-other-device
            mediaProjectionState.value = MediaProjectionState.NotProjecting
            setNotifs(
                listOf(
                    activeNotificationModel(
                        key = callNotificationKey,
                        statusBarChipIcon = createStatusBarIconViewOrNull(),
                        callType = CallType.Ongoing,
                        whenTime = 499,
                    )
                )
            )

            val latest by collectLastValue(underTest.chips)
            val unused by collectLastValue(underTest.chipsLegacy)

            assertThat(latest!!.active.size).isEqualTo(1)
            assertIsCallChip(latest!!.active[0], callNotificationKey)
            assertThat(latest!!.overflow).isEmpty()
            assertThat(latest!!.inactive.size).isEqualTo(3)
            assertThat(unused).isEqualTo(MultipleOngoingActivityChipsModelLegacy())
        }

    @DisableFlags(StatusBarRootModernization.FLAG_NAME, StatusBarChipsModernization.FLAG_NAME)
    @Test
    fun chipsLegacy_singlePromotedNotif_primaryIsNotifSecondaryIsHidden() =
        kosmos.runTest {
            val latest by collectLastValue(underTest.chipsLegacy)
            val unused by collectLastValue(underTest.chips)

            val icon = createStatusBarIconViewOrNull()
            setNotifs(
                listOf(
                    activeNotificationModel(
                        key = "notif",
                        statusBarChipIcon = icon,
                        promotedContent = PromotedNotificationContentModel.Builder("notif").build(),
                    )
                )
            )

            assertIsNotifChip(latest!!.primary, context, icon, "notif")
            assertThat(latest!!.secondary)
                .isInstanceOf(OngoingActivityChipModel.Inactive::class.java)
            assertThat(unused).isEqualTo(MultipleOngoingActivityChipsModel())
        }

    @EnableFlags(StatusBarRootModernization.FLAG_NAME, StatusBarChipsModernization.FLAG_NAME)
    @Test
    fun chips_singlePromotedNotif() =
        kosmos.runTest {
            val latest by collectLastValue(underTest.chips)
            val unused by collectLastValue(underTest.chipsLegacy)

            val icon = createStatusBarIconViewOrNull()
            setNotifs(
                listOf(
                    activeNotificationModel(
                        key = "notif",
                        statusBarChipIcon = icon,
                        promotedContent = PromotedNotificationContentModel.Builder("notif").build(),
                    )
                )
            )

            assertThat(latest!!.active.size).isEqualTo(1)
            assertIsNotifChip(latest!!.active[0], context, icon, "notif")
            assertThat(latest!!.overflow).isEmpty()
            assertThat(latest!!.inactive.size).isEqualTo(4)
            assertThat(unused).isEqualTo(MultipleOngoingActivityChipsModelLegacy())
        }

    @DisableFlags(StatusBarRootModernization.FLAG_NAME, StatusBarChipsModernization.FLAG_NAME)
    @Test
    fun chipsLegacy_twoPromotedNotifs_primaryAndSecondaryAreNotifsInOrder() =
        kosmos.runTest {
            val latest by collectLastValue(underTest.chipsLegacy)
            val unused by collectLastValue(underTest.chips)

            val firstIcon = createStatusBarIconViewOrNull()
            val secondIcon = createStatusBarIconViewOrNull()
            setNotifs(
                listOf(
                    activeNotificationModel(
                        key = "firstNotif",
                        statusBarChipIcon = firstIcon,
                        promotedContent =
                            PromotedNotificationContentModel.Builder("firstNotif").build(),
                    ),
                    activeNotificationModel(
                        key = "secondNotif",
                        statusBarChipIcon = secondIcon,
                        promotedContent =
                            PromotedNotificationContentModel.Builder("secondNotif").build(),
                    ),
                )
            )

            assertIsNotifChip(latest!!.primary, context, firstIcon, "firstNotif")
            assertIsNotifChip(latest!!.secondary, context, secondIcon, "secondNotif")
            assertThat(unused).isEqualTo(MultipleOngoingActivityChipsModel())
        }

    @EnableFlags(StatusBarRootModernization.FLAG_NAME, StatusBarChipsModernization.FLAG_NAME)
    @Test
    fun chips_twoPromotedNotifs_bothActiveInOrder() =
        kosmos.runTest {
            val latest by collectLastValue(underTest.chips)
            val unused by collectLastValue(underTest.chipsLegacy)

            val firstIcon = createStatusBarIconViewOrNull()
            val secondIcon = createStatusBarIconViewOrNull()
            setNotifs(
                listOf(
                    activeNotificationModel(
                        key = "firstNotif",
                        statusBarChipIcon = firstIcon,
                        promotedContent =
                            PromotedNotificationContentModel.Builder("firstNotif").build(),
                    ),
                    activeNotificationModel(
                        key = "secondNotif",
                        statusBarChipIcon = secondIcon,
                        promotedContent =
                            PromotedNotificationContentModel.Builder("secondNotif").build(),
                    ),
                )
            )

            assertThat(latest!!.active.size).isEqualTo(2)
            assertIsNotifChip(latest!!.active[0], context, firstIcon, "firstNotif")
            assertIsNotifChip(latest!!.active[1], context, secondIcon, "secondNotif")
            assertThat(latest!!.overflow).isEmpty()
            assertThat(latest!!.inactive.size).isEqualTo(4)
            assertThat(unused).isEqualTo(MultipleOngoingActivityChipsModelLegacy())
        }

    @DisableFlags(StatusBarRootModernization.FLAG_NAME, StatusBarChipsModernization.FLAG_NAME)
    @Test
    fun chipsLegacy_threePromotedNotifs_topTwoShown() =
        kosmos.runTest {
            val latest by collectLastValue(underTest.chipsLegacy)
            val unused by collectLastValue(underTest.chips)

            val firstIcon = createStatusBarIconViewOrNull()
            val secondIcon = createStatusBarIconViewOrNull()
            val thirdIcon = createStatusBarIconViewOrNull()
            setNotifs(
                listOf(
                    activeNotificationModel(
                        key = "firstNotif",
                        statusBarChipIcon = firstIcon,
                        promotedContent =
                            PromotedNotificationContentModel.Builder("firstNotif").build(),
                    ),
                    activeNotificationModel(
                        key = "secondNotif",
                        statusBarChipIcon = secondIcon,
                        promotedContent =
                            PromotedNotificationContentModel.Builder("secondNotif").build(),
                    ),
                    activeNotificationModel(
                        key = "thirdNotif",
                        statusBarChipIcon = thirdIcon,
                        promotedContent =
                            PromotedNotificationContentModel.Builder("thirdNotif").build(),
                    ),
                )
            )

            assertIsNotifChip(latest!!.primary, context, firstIcon, "firstNotif")
            assertIsNotifChip(latest!!.secondary, context, secondIcon, "secondNotif")
            assertThat(unused).isEqualTo(MultipleOngoingActivityChipsModel())
        }

    @EnableFlags(StatusBarRootModernization.FLAG_NAME, StatusBarChipsModernization.FLAG_NAME)
    @Test
    fun chips_threePromotedNotifs_topTwoActiveThirdInOverflow() =
        kosmos.runTest {
            val latest by collectLastValue(underTest.chips)
            val unused by collectLastValue(underTest.chipsLegacy)

            val firstIcon = createStatusBarIconViewOrNull()
            val secondIcon = createStatusBarIconViewOrNull()
            val thirdIcon = createStatusBarIconViewOrNull()
            setNotifs(
                listOf(
                    activeNotificationModel(
                        key = "firstNotif",
                        statusBarChipIcon = firstIcon,
                        promotedContent =
                            PromotedNotificationContentModel.Builder("firstNotif").build(),
                    ),
                    activeNotificationModel(
                        key = "secondNotif",
                        statusBarChipIcon = secondIcon,
                        promotedContent =
                            PromotedNotificationContentModel.Builder("secondNotif").build(),
                    ),
                    activeNotificationModel(
                        key = "thirdNotif",
                        statusBarChipIcon = thirdIcon,
                        promotedContent =
                            PromotedNotificationContentModel.Builder("thirdNotif").build(),
                    ),
                )
            )

            assertThat(latest!!.active.size).isEqualTo(2)
            assertIsNotifChip(latest!!.active[0], context, firstIcon, "firstNotif")
            assertIsNotifChip(latest!!.active[1], context, secondIcon, "secondNotif")
            assertThat(latest!!.overflow.size).isEqualTo(1)
            assertIsNotifChip(latest!!.overflow[0], context, thirdIcon, "thirdNotif")
            assertThat(latest!!.inactive.size).isEqualTo(4)
            assertThat(unused).isEqualTo(MultipleOngoingActivityChipsModelLegacy())
        }

    @DisableFlags(StatusBarRootModernization.FLAG_NAME, StatusBarChipsModernization.FLAG_NAME)
    @Test
    fun chipsLegacy_callAndPromotedNotifs_primaryIsCallSecondaryIsNotif() =
        kosmos.runTest {
            val latest by collectLastValue(underTest.chipsLegacy)
            val unused by collectLastValue(underTest.chips)

            val callNotificationKey = "call"
            callRepo.setOngoingCallState(
                inCallModel(startTimeMs = 34, notificationKey = callNotificationKey)
            )

            val firstIcon = createStatusBarIconViewOrNull()
            setNotifs(
                listOf(
                    activeNotificationModel(
                        key = "firstNotif",
                        statusBarChipIcon = firstIcon,
                        promotedContent =
                            PromotedNotificationContentModel.Builder("firstNotif").build(),
                    ),
                    activeNotificationModel(
                        key = "secondNotif",
                        statusBarChipIcon = createStatusBarIconViewOrNull(),
                        promotedContent =
                            PromotedNotificationContentModel.Builder("secondNotif").build(),
                    ),
                )
            )

            assertIsCallChip(latest!!.primary, callNotificationKey)
            assertIsNotifChip(latest!!.secondary, context, firstIcon, "firstNotif")
            assertThat(unused).isEqualTo(MultipleOngoingActivityChipsModel())
        }

    @EnableFlags(StatusBarRootModernization.FLAG_NAME, StatusBarChipsModernization.FLAG_NAME)
    @Test
    fun chips_callAndPromotedNotifs_callAndFirstNotifActiveSecondNotifInOverflow() =
        kosmos.runTest {
            val latest by collectLastValue(underTest.chips)
            val unused by collectLastValue(underTest.chipsLegacy)

            val callNotificationKey = "call"
            val firstIcon = createStatusBarIconViewOrNull()
            val secondIcon = createStatusBarIconViewOrNull()
            setNotifs(
                listOf(
                    activeNotificationModel(
                        key = callNotificationKey,
                        whenTime = 499,
                        callType = CallType.Ongoing,
                    ),
                    activeNotificationModel(
                        key = "firstNotif",
                        statusBarChipIcon = firstIcon,
                        promotedContent =
                            PromotedNotificationContentModel.Builder("firstNotif").build(),
                    ),
                    activeNotificationModel(
                        key = "secondNotif",
                        statusBarChipIcon = secondIcon,
                        promotedContent =
                            PromotedNotificationContentModel.Builder("secondNotif").build(),
                    ),
                )
            )

            assertThat(latest!!.active.size).isEqualTo(2)
            assertIsCallChip(latest!!.active[0], callNotificationKey)
            assertIsNotifChip(latest!!.active[1], context, firstIcon, "firstNotif")
            assertThat(latest!!.overflow.size).isEqualTo(1)
            assertIsNotifChip(latest!!.overflow[0], context, secondIcon, "secondNotif")
            assertThat(latest!!.inactive.size).isEqualTo(3)
            assertThat(unused).isEqualTo(MultipleOngoingActivityChipsModelLegacy())
        }

    @DisableFlags(StatusBarRootModernization.FLAG_NAME, StatusBarChipsModernization.FLAG_NAME)
    @Test
    fun chipsLegacy_screenRecordAndCallAndPromotedNotifs_notifsNotShown() =
        kosmos.runTest {
            val callNotificationKey = "call"
            val latest by collectLastValue(underTest.chipsLegacy)
            val unused by collectLastValue(underTest.chips)

            callRepo.setOngoingCallState(
                inCallModel(startTimeMs = 34, notificationKey = callNotificationKey)
            )
            screenRecordState.value = ScreenRecordModel.Recording
            setNotifs(
                listOf(
                    activeNotificationModel(
                        key = "notif",
                        statusBarChipIcon = createStatusBarIconViewOrNull(),
                        promotedContent = PromotedNotificationContentModel.Builder("notif").build(),
                    )
                )
            )

            assertIsScreenRecordChip(latest!!.primary)
            assertIsCallChip(latest!!.secondary, callNotificationKey)
            assertThat(unused).isEqualTo(MultipleOngoingActivityChipsModel())
        }

    @EnableFlags(StatusBarRootModernization.FLAG_NAME, StatusBarChipsModernization.FLAG_NAME)
    @Test
    fun chips_screenRecordAndCallAndPromotedNotif_notifInOverflow() =
        kosmos.runTest {
            val latest by collectLastValue(underTest.chips)
            val unused by collectLastValue(underTest.chipsLegacy)

            val callNotificationKey = "call"
            val notifIcon = createStatusBarIconViewOrNull()
            screenRecordState.value = ScreenRecordModel.Recording
            setNotifs(
                listOf(
                    activeNotificationModel(
                        key = callNotificationKey,
                        whenTime = 499,
                        callType = CallType.Ongoing,
                    ),
                    activeNotificationModel(
                        key = "notif",
                        statusBarChipIcon = notifIcon,
                        promotedContent = PromotedNotificationContentModel.Builder("notif").build(),
                    ),
                )
            )

            assertThat(latest!!.active.size).isEqualTo(2)
            assertIsScreenRecordChip(latest!!.active[0])
            assertIsCallChip(latest!!.active[1], callNotificationKey)
            assertThat(latest!!.overflow.size).isEqualTo(1)
            assertIsNotifChip(latest!!.overflow[0], context, notifIcon, "notif")
            assertThat(latest!!.inactive.size).isEqualTo(2)
            assertThat(unused).isEqualTo(MultipleOngoingActivityChipsModelLegacy())
        }

    @Test
    fun primaryChip_higherPriorityChipAdded_lowerPriorityChipReplaced() =
        kosmos.runTest {
            val callNotificationKey = "call"
            // Start with just the lowest priority chip shown
            val notifIcon = createStatusBarIconViewOrNull()
            setNotifs(
                listOf(
                    activeNotificationModel(
                        key = "notif",
                        statusBarChipIcon = notifIcon,
                        promotedContent = PromotedNotificationContentModel.Builder("notif").build(),
                    )
                )
            )
            // And everything else hidden
            callRepo.setOngoingCallState(OngoingCallModel.NoCall)
            mediaProjectionState.value = MediaProjectionState.NotProjecting
            screenRecordState.value = ScreenRecordModel.DoingNothing

            val latest by collectLastValue(underTest.primaryChip)

            assertIsNotifChip(latest, context, notifIcon, "notif")

            // WHEN the higher priority call chip is added
            callRepo.setOngoingCallState(
                inCallModel(startTimeMs = 34, notificationKey = callNotificationKey)
            )

            // THEN the higher priority call chip is used
            assertIsCallChip(latest, callNotificationKey)

            // WHEN the higher priority media projection chip is added
            mediaProjectionState.value =
                MediaProjectionState.Projecting.SingleTask(
                    NORMAL_PACKAGE,
                    hostDeviceName = null,
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
    fun primaryChip_highestPriorityChipRemoved_showsNextPriorityChip() =
        kosmos.runTest {
            val callNotificationKey = "call"
            // WHEN all chips are active
            screenRecordState.value = ScreenRecordModel.Recording
            mediaProjectionState.value =
                MediaProjectionState.Projecting.EntireScreen(NORMAL_PACKAGE)
            callRepo.setOngoingCallState(
                inCallModel(startTimeMs = 34, notificationKey = callNotificationKey)
            )
            val notifIcon = createStatusBarIconViewOrNull()
            setNotifs(
                listOf(
                    activeNotificationModel(
                        key = "notif",
                        statusBarChipIcon = notifIcon,
                        promotedContent = PromotedNotificationContentModel.Builder("notif").build(),
                    )
                )
            )

            val latest by collectLastValue(underTest.primaryChip)

            // THEN the highest priority screen record is used
            assertIsScreenRecordChip(latest)

            // WHEN the higher priority screen record is removed
            screenRecordState.value = ScreenRecordModel.DoingNothing

            // THEN the lower priority media projection is used
            assertIsShareToAppChip(latest)

            // WHEN the higher priority media projection is removed
            mediaProjectionState.value = MediaProjectionState.NotProjecting

            // THEN the lower priority call is used
            assertIsCallChip(latest, callNotificationKey)

            // WHEN the higher priority call is removed
            callRepo.setOngoingCallState(OngoingCallModel.NoCall)

            // THEN the lower priority notif is used
            assertIsNotifChip(latest, context, notifIcon, "notif")
        }

    @DisableFlags(StatusBarRootModernization.FLAG_NAME, StatusBarChipsModernization.FLAG_NAME)
    @Test
    fun chipsLegacy_movesChipsAroundAccordingToPriority() =
        kosmos.runTest {
            val callNotificationKey = "call"
            // Start with just the lowest priority chip shown
            val notifIcon = createStatusBarIconViewOrNull()
            setNotifs(
                listOf(
                    activeNotificationModel(
                        key = "notif",
                        statusBarChipIcon = notifIcon,
                        promotedContent = PromotedNotificationContentModel.Builder("notif").build(),
                    )
                )
            )
            // And everything else hidden
            callRepo.setOngoingCallState(OngoingCallModel.NoCall)
            mediaProjectionState.value = MediaProjectionState.NotProjecting
            screenRecordState.value = ScreenRecordModel.DoingNothing

            val latest by collectLastValue(underTest.chipsLegacy)
            val unused by collectLastValue(underTest.chips)

            assertIsNotifChip(latest!!.primary, context, notifIcon, "notif")
            assertThat(latest!!.secondary)
                .isInstanceOf(OngoingActivityChipModel.Inactive::class.java)
            assertThat(unused).isEqualTo(MultipleOngoingActivityChipsModel())

            // WHEN the higher priority call chip is added
            callRepo.setOngoingCallState(
                inCallModel(startTimeMs = 34, notificationKey = callNotificationKey)
            )

            // THEN the higher priority call chip is used as primary and notif is demoted to
            // secondary
            assertIsCallChip(latest!!.primary, callNotificationKey)
            assertIsNotifChip(latest!!.secondary, context, notifIcon, "notif")
            assertThat(unused).isEqualTo(MultipleOngoingActivityChipsModel())

            // WHEN the higher priority media projection chip is added
            mediaProjectionState.value =
                MediaProjectionState.Projecting.SingleTask(
                    NORMAL_PACKAGE,
                    hostDeviceName = null,
                    createTask(taskId = 1),
                )

            // THEN the higher priority media projection chip is used as primary and call is demoted
            // to secondary (and notif is dropped altogether)
            assertIsShareToAppChip(latest!!.primary)
            assertIsCallChip(latest!!.secondary, callNotificationKey)
            assertThat(unused).isEqualTo(MultipleOngoingActivityChipsModel())

            // WHEN the higher priority screen record chip is added
            screenRecordState.value = ScreenRecordModel.Recording

            // THEN the higher priority screen record chip is used
            assertIsScreenRecordChip(latest!!.primary)
            assertThat(unused).isEqualTo(MultipleOngoingActivityChipsModel())

            // WHEN screen record and call is dropped
            screenRecordState.value = ScreenRecordModel.DoingNothing
            callRepo.setOngoingCallState(OngoingCallModel.NoCall)

            // THEN media projection and notif remain
            assertIsShareToAppChip(latest!!.primary)
            assertIsNotifChip(latest!!.secondary, context, notifIcon, "notif")
            assertThat(unused).isEqualTo(MultipleOngoingActivityChipsModel())

            // WHEN media projection is dropped
            mediaProjectionState.value = MediaProjectionState.NotProjecting

            // THEN notif is promoted to primary
            assertIsNotifChip(latest!!.primary, context, notifIcon, "notif")
            assertThat(latest!!.secondary)
                .isInstanceOf(OngoingActivityChipModel.Inactive::class.java)
            assertThat(unused).isEqualTo(MultipleOngoingActivityChipsModel())
        }

    @EnableFlags(StatusBarRootModernization.FLAG_NAME, StatusBarChipsModernization.FLAG_NAME)
    @Test
    fun chips_movesChipsAroundAccordingToPriority() =
        kosmos.runTest {
            val callNotificationKey = "call"
            // Start with just the lowest priority chip active
            val notifIcon = createStatusBarIconViewOrNull()
            setNotifs(
                listOf(
                    activeNotificationModel(
                        key = "notif",
                        statusBarChipIcon = notifIcon,
                        promotedContent = PromotedNotificationContentModel.Builder("notif").build(),
                    )
                )
            )
            // And everything else hidden
            mediaProjectionState.value = MediaProjectionState.NotProjecting
            screenRecordState.value = ScreenRecordModel.DoingNothing

            val latest by collectLastValue(underTest.chips)
            val unused by collectLastValue(underTest.chipsLegacy)

            assertThat(latest!!.active.size).isEqualTo(1)
            assertIsNotifChip(latest!!.active[0], context, notifIcon, "notif")
            assertThat(latest!!.overflow).isEmpty()
            assertThat(latest!!.inactive.size).isEqualTo(4)
            assertThat(unused).isEqualTo(MultipleOngoingActivityChipsModelLegacy())

            // WHEN the higher priority call chip is added
            setNotifs(
                listOf(
                    activeNotificationModel(
                        key = callNotificationKey,
                        statusBarChipIcon = createStatusBarIconViewOrNull(),
                        callType = CallType.Ongoing,
                        whenTime = 499,
                    ),
                    activeNotificationModel(
                        key = "notif",
                        statusBarChipIcon = notifIcon,
                        promotedContent = PromotedNotificationContentModel.Builder("notif").build(),
                    ),
                )
            )

            // THEN the higher priority call chip and notif are active in that order
            assertThat(latest!!.active.size).isEqualTo(2)
            assertIsCallChip(latest!!.active[0], callNotificationKey)
            assertIsNotifChip(latest!!.active[1], context, notifIcon, "notif")
            assertThat(latest!!.overflow).isEmpty()
            assertThat(latest!!.inactive.size).isEqualTo(3)
            assertThat(unused).isEqualTo(MultipleOngoingActivityChipsModelLegacy())

            // WHEN the higher priority media projection chip is added
            mediaProjectionState.value =
                MediaProjectionState.Projecting.SingleTask(
                    NORMAL_PACKAGE,
                    hostDeviceName = null,
                    createTask(taskId = 1),
                )

            // THEN the higher priority media projection chip and call are active in that order, and
            // notif is demoted to overflow
            assertThat(latest!!.active.size).isEqualTo(2)
            assertIsShareToAppChip(latest!!.active[0])
            assertIsCallChip(latest!!.active[1], callNotificationKey)
            assertThat(latest!!.overflow.size).isEqualTo(1)
            assertIsNotifChip(latest!!.overflow[0], context, notifIcon, "notif")
            assertThat(latest!!.inactive.size).isEqualTo(2)
            assertThat(unused).isEqualTo(MultipleOngoingActivityChipsModelLegacy())

            // WHEN the higher priority screen record chip is added
            screenRecordState.value = ScreenRecordModel.Recording

            // THEN the higher priority screen record chip and call are active in that order, and
            // media projection and notif are demoted in overflow
            assertThat(latest!!.active.size).isEqualTo(2)
            assertIsScreenRecordChip(latest!!.active[0])
            assertIsCallChip(latest!!.active[1], callNotificationKey)
            assertThat(latest!!.overflow.size).isEqualTo(2)
            assertIsShareToAppChip(latest!!.overflow[0])
            assertIsNotifChip(latest!!.overflow[1], context, notifIcon, "notif")
            assertThat(latest!!.inactive.size).isEqualTo(1)
            assertThat(unused).isEqualTo(MultipleOngoingActivityChipsModelLegacy())

            // WHEN screen record and call is dropped
            screenRecordState.value = ScreenRecordModel.DoingNothing
            setNotifs(
                listOf(
                    activeNotificationModel(
                        key = "notif",
                        statusBarChipIcon = notifIcon,
                        promotedContent = PromotedNotificationContentModel.Builder("notif").build(),
                    )
                )
            )

            // THEN media projection and notif remain
            assertThat(latest!!.active.size).isEqualTo(2)
            assertIsShareToAppChip(latest!!.active[0])
            assertIsNotifChip(latest!!.active[1], context, notifIcon, "notif")
            assertThat(latest!!.overflow).isEmpty()
            assertThat(latest!!.inactive.size).isEqualTo(3)
            assertThat(unused).isEqualTo(MultipleOngoingActivityChipsModelLegacy())

            // WHEN media projection is dropped
            mediaProjectionState.value = MediaProjectionState.NotProjecting

            // THEN only notif is active
            assertThat(latest!!.active.size).isEqualTo(1)
            assertIsNotifChip(latest!!.active[0], context, notifIcon, "notif")
            assertThat(latest!!.overflow).isEmpty()
            assertThat(latest!!.inactive.size).isEqualTo(4)
            assertThat(unused).isEqualTo(MultipleOngoingActivityChipsModelLegacy())
        }

    /** Regression test for b/347726238. */
    @Test
    fun primaryChip_timerDoesNotResetAfterSubscribersRestart() =
        kosmos.runTest {
            var latest: OngoingActivityChipModel? = null

            val job1 = underTest.primaryChip.onEach { latest = it }.launchIn(testScope)

            // Start a chip with a timer
            systemClock.setElapsedRealtime(1234)
            screenRecordState.value = ScreenRecordModel.Recording

            runCurrent()

            assertThat((latest as OngoingActivityChipModel.Active.Timer).startTimeMs)
                .isEqualTo(1234)

            // Stop subscribing to the chip flow
            job1.cancel()

            // Let time pass
            systemClock.setElapsedRealtime(5678)

            // WHEN we re-subscribe to the chip flow
            val job2 = underTest.primaryChip.onEach { latest = it }.launchIn(testScope)

            runCurrent()

            // THEN the old start time is still used
            assertThat((latest as OngoingActivityChipModel.Active.Timer).startTimeMs)
                .isEqualTo(1234)

            job2.cancel()
        }

    /** Regression test for b/347726238. */
    @DisableFlags(StatusBarRootModernization.FLAG_NAME, StatusBarChipsModernization.FLAG_NAME)
    @Test
    fun chipsLegacy_timerDoesNotResetAfterSubscribersRestart() =
        kosmos.runTest {
            var latest: MultipleOngoingActivityChipsModelLegacy? = null

            val job1 = underTest.chipsLegacy.onEach { latest = it }.launchIn(testScope)

            // Start a chip with a timer
            systemClock.setElapsedRealtime(1234)
            screenRecordState.value = ScreenRecordModel.Recording

            runCurrent()

            val primaryChip = latest!!.primary as OngoingActivityChipModel.Active.Timer
            assertThat(primaryChip.startTimeMs).isEqualTo(1234)

            // Stop subscribing to the chip flow
            job1.cancel()

            // Let time pass
            systemClock.setElapsedRealtime(5678)

            // WHEN we re-subscribe to the chip flow
            val job2 = underTest.chipsLegacy.onEach { latest = it }.launchIn(testScope)

            runCurrent()

            // THEN the old start time is still used
            val newPrimaryChip = latest!!.primary as OngoingActivityChipModel.Active.Timer
            assertThat(newPrimaryChip.startTimeMs).isEqualTo(1234)

            job2.cancel()
        }

    /** Regression test for b/347726238. */
    @EnableFlags(StatusBarRootModernization.FLAG_NAME, StatusBarChipsModernization.FLAG_NAME)
    @Test
    fun chips_timerDoesNotResetAfterSubscribersRestart() =
        kosmos.runTest {
            var latest: MultipleOngoingActivityChipsModel? = null

            val job1 = underTest.chips.onEach { latest = it }.launchIn(testScope)

            // Start a chip with a timer
            systemClock.setElapsedRealtime(1234)
            screenRecordState.value = ScreenRecordModel.Recording

            runCurrent()

            val primaryChip = latest!!.active[0] as OngoingActivityChipModel.Active.Timer
            assertThat(primaryChip.startTimeMs).isEqualTo(1234)

            // Stop subscribing to the chip flow
            job1.cancel()

            // Let time pass
            systemClock.setElapsedRealtime(5678)

            // WHEN we re-subscribe to the chip flow
            val job2 = underTest.chips.onEach { latest = it }.launchIn(testScope)

            runCurrent()

            // THEN the old start time is still used
            val newPrimaryChip = latest!!.active[0] as OngoingActivityChipModel.Active.Timer
            assertThat(newPrimaryChip.startTimeMs).isEqualTo(1234)

            job2.cancel()
        }

    @Test
    @Ignore("b/364653005") // We'll need to re-do the animation story when we implement RON chips
    fun primaryChip_screenRecordStoppedViaDialog_chipHiddenWithoutAnimation() =
        kosmos.runTest {
            screenRecordState.value = ScreenRecordModel.Recording
            mediaProjectionState.value = MediaProjectionState.NotProjecting
            callRepo.setOngoingCallState(OngoingCallModel.NoCall)

            val latest by collectLastValue(underTest.primaryChip)

            assertIsScreenRecordChip(latest)

            // WHEN screen record gets stopped via dialog
            val dialogStopAction =
                getStopActionFromDialog(
                    latest,
                    chipView,
                    mockExpandable,
                    mockSystemUIDialog,
                    kosmos,
                )
            dialogStopAction.onClick(mock<DialogInterface>(), 0)

            // THEN the chip is immediately hidden with no animation
            assertThat(latest).isEqualTo(OngoingActivityChipModel.Inactive(shouldAnimate = false))
        }

    @Test
    fun primaryChip_projectionStoppedViaDialog_chipHiddenWithoutAnimation() =
        kosmos.runTest {
            mediaProjectionState.value =
                MediaProjectionState.Projecting.EntireScreen(NORMAL_PACKAGE)
            screenRecordState.value = ScreenRecordModel.DoingNothing
            callRepo.setOngoingCallState(OngoingCallModel.NoCall)

            val latest by collectLastValue(underTest.primaryChip)

            assertIsShareToAppChip(latest)

            // WHEN media projection gets stopped via dialog
            val dialogStopAction =
                getStopActionFromDialog(
                    latest,
                    chipView,
                    mockExpandable,
                    mockSystemUIDialog,
                    kosmos,
                )
            dialogStopAction.onClick(mock<DialogInterface>(), 0)

            // THEN the chip is immediately hidden with no animation
            assertThat(latest).isEqualTo(OngoingActivityChipModel.Inactive(shouldAnimate = false))
        }

    private fun setNotifs(notifs: List<ActiveNotificationModel>) {
        activeNotificationListRepository.activeNotifications.value =
            ActiveNotificationsStore.Builder()
                .apply { notifs.forEach { addIndividualNotif(it) } }
                .build()
    }
}
