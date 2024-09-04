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
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.platform.test.annotations.EnableFlags
import android.view.View
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.Flags.FLAG_STATUS_BAR_RON_CHIPS
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.kosmos.testScope
import com.android.systemui.mediaprojection.data.model.MediaProjectionState
import com.android.systemui.mediaprojection.data.repository.fakeMediaProjectionRepository
import com.android.systemui.mediaprojection.taskswitcher.FakeActivityTaskManager.Companion.createTask
import com.android.systemui.res.R
import com.android.systemui.screenrecord.data.model.ScreenRecordModel
import com.android.systemui.screenrecord.data.repository.screenRecordRepository
import com.android.systemui.statusbar.chips.mediaprojection.domain.interactor.MediaProjectionChipInteractorTest.Companion.NORMAL_PACKAGE
import com.android.systemui.statusbar.chips.mediaprojection.domain.interactor.MediaProjectionChipInteractorTest.Companion.setUpPackageManagerForMediaProjection
import com.android.systemui.statusbar.chips.ron.demo.ui.viewmodel.DemoRonChipViewModelTest.Companion.addDemoRonChip
import com.android.systemui.statusbar.chips.ron.demo.ui.viewmodel.demoRonChipViewModel
import com.android.systemui.statusbar.chips.ui.model.MultipleOngoingActivityChipsModel
import com.android.systemui.statusbar.chips.ui.model.OngoingActivityChipModel
import com.android.systemui.statusbar.chips.ui.view.ChipBackgroundContainer
import com.android.systemui.statusbar.chips.ui.viewmodel.OngoingActivityChipsViewModelTest.Companion.assertIsCallChip
import com.android.systemui.statusbar.chips.ui.viewmodel.OngoingActivityChipsViewModelTest.Companion.assertIsScreenRecordChip
import com.android.systemui.statusbar.chips.ui.viewmodel.OngoingActivityChipsViewModelTest.Companion.assertIsShareToAppChip
import com.android.systemui.statusbar.chips.ui.viewmodel.OngoingActivityChipsViewModelTest.Companion.getStopActionFromDialog
import com.android.systemui.statusbar.commandline.commandRegistry
import com.android.systemui.statusbar.phone.SystemUIDialog
import com.android.systemui.statusbar.phone.ongoingcall.data.repository.ongoingCallRepository
import com.android.systemui.statusbar.phone.ongoingcall.shared.model.OngoingCallModel
import com.android.systemui.statusbar.phone.ongoingcall.shared.model.inCallModel
import com.android.systemui.testKosmos
import com.android.systemui.util.time.fakeSystemClock
import com.google.common.truth.Truth.assertThat
import java.io.PrintWriter
import java.io.StringWriter
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/**
 * Tests for [OngoingActivityChipsViewModel] when the [FLAG_STATUS_BAR_RON_CHIPS] flag is enabled.
 */
@SmallTest
@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalCoroutinesApi::class)
@EnableFlags(FLAG_STATUS_BAR_RON_CHIPS)
class OngoingActivityChipsWithRonsViewModelTest : SysuiTestCase() {
    private val kosmos = testKosmos()
    private val testScope = kosmos.testScope
    private val systemClock = kosmos.fakeSystemClock
    private val commandRegistry = kosmos.commandRegistry

    private val screenRecordState = kosmos.screenRecordRepository.screenRecordState
    private val mediaProjectionState = kosmos.fakeMediaProjectionRepository.mediaProjectionState
    private val callRepo = kosmos.ongoingCallRepository

    private val pw = PrintWriter(StringWriter())

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

    private val underTest = kosmos.ongoingActivityChipsViewModel

    @Before
    fun setUp() {
        setUpPackageManagerForMediaProjection(kosmos)
        kosmos.demoRonChipViewModel.start()
        val icon =
            BitmapDrawable(
                context.resources,
                Bitmap.createBitmap(/* width= */ 100, /* height= */ 100, Bitmap.Config.ARGB_8888)
            )
        whenever(kosmos.packageManager.getApplicationIcon(any<String>())).thenReturn(icon)
    }

    // Even though the `primaryChip` flow isn't used when the RONs flag is on, still test that the
    // flow has the right behavior to verify that we don't break any existing functionality.

    @Test
    fun primaryChip_allHidden_hidden() =
        testScope.runTest {
            screenRecordState.value = ScreenRecordModel.DoingNothing
            mediaProjectionState.value = MediaProjectionState.NotProjecting
            callRepo.setOngoingCallState(OngoingCallModel.NoCall)

            val latest by collectLastValue(underTest.primaryChip)

            assertThat(latest).isInstanceOf(OngoingActivityChipModel.Hidden::class.java)
        }

    @Test
    fun chips_allHidden_bothPrimaryAndSecondaryHidden() =
        testScope.runTest {
            screenRecordState.value = ScreenRecordModel.DoingNothing
            mediaProjectionState.value = MediaProjectionState.NotProjecting
            callRepo.setOngoingCallState(OngoingCallModel.NoCall)

            val latest by collectLastValue(underTest.chips)

            assertThat(latest!!.primary).isInstanceOf(OngoingActivityChipModel.Hidden::class.java)
            assertThat(latest!!.secondary).isInstanceOf(OngoingActivityChipModel.Hidden::class.java)
        }

    @Test
    fun primaryChip_screenRecordShow_restHidden_screenRecordShown() =
        testScope.runTest {
            screenRecordState.value = ScreenRecordModel.Recording
            mediaProjectionState.value = MediaProjectionState.NotProjecting
            callRepo.setOngoingCallState(OngoingCallModel.NoCall)

            val latest by collectLastValue(underTest.primaryChip)

            assertIsScreenRecordChip(latest)
        }

    @Test
    fun chips_screenRecordShow_restHidden_primaryIsScreenRecordSecondaryIsHidden() =
        testScope.runTest {
            screenRecordState.value = ScreenRecordModel.Recording
            mediaProjectionState.value = MediaProjectionState.NotProjecting
            callRepo.setOngoingCallState(OngoingCallModel.NoCall)

            val latest by collectLastValue(underTest.chips)

            assertIsScreenRecordChip(latest!!.primary)
            assertThat(latest!!.secondary).isInstanceOf(OngoingActivityChipModel.Hidden::class.java)
        }

    @Test
    fun primaryChip_screenRecordShowAndCallShow_screenRecordShown() =
        testScope.runTest {
            screenRecordState.value = ScreenRecordModel.Recording
            callRepo.setOngoingCallState(inCallModel(startTimeMs = 34))

            val latest by collectLastValue(underTest.primaryChip)

            assertIsScreenRecordChip(latest)
        }

    @Test
    fun chips_screenRecordShowAndCallShow_primaryIsScreenRecordSecondaryIsCall() =
        testScope.runTest {
            screenRecordState.value = ScreenRecordModel.Recording
            callRepo.setOngoingCallState(inCallModel(startTimeMs = 34))

            val latest by collectLastValue(underTest.chips)

            assertIsScreenRecordChip(latest!!.primary)
            assertIsCallChip(latest!!.secondary)
        }

    @Test
    fun primaryChip_screenRecordShowAndShareToAppShow_screenRecordShown() =
        testScope.runTest {
            screenRecordState.value = ScreenRecordModel.Recording
            mediaProjectionState.value =
                MediaProjectionState.Projecting.EntireScreen(NORMAL_PACKAGE)
            callRepo.setOngoingCallState(OngoingCallModel.NoCall)

            val latest by collectLastValue(underTest.primaryChip)

            assertIsScreenRecordChip(latest)
        }

    @Test
    fun chips_screenRecordShowAndShareToAppShow_primaryIsScreenRecordSecondaryIsHidden() =
        testScope.runTest {
            screenRecordState.value = ScreenRecordModel.Recording
            mediaProjectionState.value =
                MediaProjectionState.Projecting.EntireScreen(NORMAL_PACKAGE)
            callRepo.setOngoingCallState(OngoingCallModel.NoCall)

            val latest by collectLastValue(underTest.chips)

            assertIsScreenRecordChip(latest!!.primary)
            // Even though share-to-app is active, we suppress it because this share-to-app is
            // represented by screen record being active. See b/296461748.
            assertThat(latest!!.secondary).isInstanceOf(OngoingActivityChipModel.Hidden::class.java)
        }

    @Test
    fun primaryChip_shareToAppShowAndCallShow_shareToAppShown() =
        testScope.runTest {
            screenRecordState.value = ScreenRecordModel.DoingNothing
            mediaProjectionState.value =
                MediaProjectionState.Projecting.EntireScreen(NORMAL_PACKAGE)
            callRepo.setOngoingCallState(inCallModel(startTimeMs = 34))

            val latest by collectLastValue(underTest.primaryChip)

            assertIsShareToAppChip(latest)
        }

    @Test
    fun chips_shareToAppShowAndCallShow_primaryIsShareToAppSecondaryIsCall() =
        testScope.runTest {
            screenRecordState.value = ScreenRecordModel.DoingNothing
            mediaProjectionState.value =
                MediaProjectionState.Projecting.EntireScreen(NORMAL_PACKAGE)
            callRepo.setOngoingCallState(inCallModel(startTimeMs = 34))

            val latest by collectLastValue(underTest.chips)

            assertIsShareToAppChip(latest!!.primary)
            assertIsCallChip(latest!!.secondary)
        }

    @Test
    fun chips_threeActiveChips_topTwoShown() =
        testScope.runTest {
            screenRecordState.value = ScreenRecordModel.Recording
            callRepo.setOngoingCallState(inCallModel(startTimeMs = 34))
            addDemoRonChip(commandRegistry, pw)

            val latest by collectLastValue(underTest.chips)

            assertIsScreenRecordChip(latest!!.primary)
            assertIsCallChip(latest!!.secondary)
            // Demo RON chip is dropped
        }

    @Test
    fun primaryChip_onlyCallShown_callShown() =
        testScope.runTest {
            screenRecordState.value = ScreenRecordModel.DoingNothing
            // MediaProjection covers both share-to-app and cast-to-other-device
            mediaProjectionState.value = MediaProjectionState.NotProjecting

            callRepo.setOngoingCallState(inCallModel(startTimeMs = 34))

            val latest by collectLastValue(underTest.primaryChip)

            assertIsCallChip(latest)
        }

    @Test
    fun chips_onlyCallShown_primaryIsCallSecondaryIsHidden() =
        testScope.runTest {
            screenRecordState.value = ScreenRecordModel.DoingNothing
            // MediaProjection covers both share-to-app and cast-to-other-device
            mediaProjectionState.value = MediaProjectionState.NotProjecting

            callRepo.setOngoingCallState(inCallModel(startTimeMs = 34))

            val latest by collectLastValue(underTest.chips)

            assertIsCallChip(latest!!.primary)
            assertThat(latest!!.secondary).isInstanceOf(OngoingActivityChipModel.Hidden::class.java)
        }

    @Test
    fun primaryChip_higherPriorityChipAdded_lowerPriorityChipReplaced() =
        testScope.runTest {
            // Start with just the lowest priority chip shown
            addDemoRonChip(commandRegistry, pw)
            // And everything else hidden
            callRepo.setOngoingCallState(OngoingCallModel.NoCall)
            mediaProjectionState.value = MediaProjectionState.NotProjecting
            screenRecordState.value = ScreenRecordModel.DoingNothing

            val latest by collectLastValue(underTest.primaryChip)

            assertIsDemoRonChip(latest)

            // WHEN the higher priority call chip is added
            callRepo.setOngoingCallState(inCallModel(startTimeMs = 34))

            // THEN the higher priority call chip is used
            assertIsCallChip(latest)

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
        testScope.runTest {
            // WHEN all chips are active
            screenRecordState.value = ScreenRecordModel.Recording
            mediaProjectionState.value =
                MediaProjectionState.Projecting.EntireScreen(NORMAL_PACKAGE)
            callRepo.setOngoingCallState(inCallModel(startTimeMs = 34))
            addDemoRonChip(commandRegistry, pw)

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
            assertIsCallChip(latest)

            // WHEN the higher priority call is removed
            callRepo.setOngoingCallState(OngoingCallModel.NoCall)

            // THEN the lower priority demo RON is used
            assertIsDemoRonChip(latest)
        }

    @Test
    fun chips_movesChipsAroundAccordingToPriority() =
        testScope.runTest {
            // Start with just the lowest priority chip shown
            addDemoRonChip(commandRegistry, pw)
            // And everything else hidden
            callRepo.setOngoingCallState(OngoingCallModel.NoCall)
            mediaProjectionState.value = MediaProjectionState.NotProjecting
            screenRecordState.value = ScreenRecordModel.DoingNothing

            val latest by collectLastValue(underTest.chips)

            assertIsDemoRonChip(latest!!.primary)
            assertThat(latest!!.secondary).isInstanceOf(OngoingActivityChipModel.Hidden::class.java)

            // WHEN the higher priority call chip is added
            callRepo.setOngoingCallState(inCallModel(startTimeMs = 34))

            // THEN the higher priority call chip is used as primary and demo ron is demoted to
            // secondary
            assertIsCallChip(latest!!.primary)
            assertIsDemoRonChip(latest!!.secondary)

            // WHEN the higher priority media projection chip is added
            mediaProjectionState.value =
                MediaProjectionState.Projecting.SingleTask(
                    NORMAL_PACKAGE,
                    hostDeviceName = null,
                    createTask(taskId = 1),
                )

            // THEN the higher priority media projection chip is used as primary and call is demoted
            // to secondary (and demo RON is dropped altogether)
            assertIsShareToAppChip(latest!!.primary)
            assertIsCallChip(latest!!.secondary)

            // WHEN the higher priority screen record chip is added
            screenRecordState.value = ScreenRecordModel.Recording

            // THEN the higher priority screen record chip is used
            assertIsScreenRecordChip(latest!!.primary)

            // WHEN screen record and call is dropped
            screenRecordState.value = ScreenRecordModel.DoingNothing
            callRepo.setOngoingCallState(OngoingCallModel.NoCall)

            // THEN media projection and demo RON remain
            assertIsShareToAppChip(latest!!.primary)
            assertIsDemoRonChip(latest!!.secondary)

            // WHEN media projection is dropped
            mediaProjectionState.value = MediaProjectionState.NotProjecting

            // THEN demo RON is promoted to primary
            assertIsDemoRonChip(latest!!.primary)
            assertThat(latest!!.secondary).isInstanceOf(OngoingActivityChipModel.Hidden::class.java)
        }

    /** Regression test for b/347726238. */
    @Test
    fun primaryChip_timerDoesNotResetAfterSubscribersRestart() =
        testScope.runTest {
            var latest: OngoingActivityChipModel? = null

            val job1 = underTest.primaryChip.onEach { latest = it }.launchIn(this)

            // Start a chip with a timer
            systemClock.setElapsedRealtime(1234)
            screenRecordState.value = ScreenRecordModel.Recording

            runCurrent()

            assertThat((latest as OngoingActivityChipModel.Shown.Timer).startTimeMs).isEqualTo(1234)

            // Stop subscribing to the chip flow
            job1.cancel()

            // Let time pass
            systemClock.setElapsedRealtime(5678)

            // WHEN we re-subscribe to the chip flow
            val job2 = underTest.primaryChip.onEach { latest = it }.launchIn(this)

            runCurrent()

            // THEN the old start time is still used
            assertThat((latest as OngoingActivityChipModel.Shown.Timer).startTimeMs).isEqualTo(1234)

            job2.cancel()
        }

    /** Regression test for b/347726238. */
    @Test
    fun chips_timerDoesNotResetAfterSubscribersRestart() =
        testScope.runTest {
            var latest: MultipleOngoingActivityChipsModel? = null

            val job1 = underTest.chips.onEach { latest = it }.launchIn(this)

            // Start a chip with a timer
            systemClock.setElapsedRealtime(1234)
            screenRecordState.value = ScreenRecordModel.Recording

            runCurrent()

            val primaryChip = latest!!.primary as OngoingActivityChipModel.Shown.Timer
            assertThat(primaryChip.startTimeMs).isEqualTo(1234)

            // Stop subscribing to the chip flow
            job1.cancel()

            // Let time pass
            systemClock.setElapsedRealtime(5678)

            // WHEN we re-subscribe to the chip flow
            val job2 = underTest.chips.onEach { latest = it }.launchIn(this)

            runCurrent()

            // THEN the old start time is still used
            val newPrimaryChip = latest!!.primary as OngoingActivityChipModel.Shown.Timer
            assertThat(newPrimaryChip.startTimeMs).isEqualTo(1234)

            job2.cancel()
        }

    @Test
    fun primaryChip_screenRecordStoppedViaDialog_chipHiddenWithoutAnimation() =
        testScope.runTest {
            screenRecordState.value = ScreenRecordModel.Recording
            mediaProjectionState.value = MediaProjectionState.NotProjecting
            callRepo.setOngoingCallState(OngoingCallModel.NoCall)

            val latest by collectLastValue(underTest.primaryChip)

            assertIsScreenRecordChip(latest)

            // WHEN screen record gets stopped via dialog
            val dialogStopAction =
                getStopActionFromDialog(latest, chipView, mockSystemUIDialog, kosmos)
            dialogStopAction.onClick(mock<DialogInterface>(), 0)

            // THEN the chip is immediately hidden with no animation
            assertThat(latest).isEqualTo(OngoingActivityChipModel.Hidden(shouldAnimate = false))
        }

    @Test
    fun primaryChip_projectionStoppedViaDialog_chipHiddenWithoutAnimation() =
        testScope.runTest {
            mediaProjectionState.value =
                MediaProjectionState.Projecting.EntireScreen(NORMAL_PACKAGE)
            screenRecordState.value = ScreenRecordModel.DoingNothing
            callRepo.setOngoingCallState(OngoingCallModel.NoCall)

            val latest by collectLastValue(underTest.primaryChip)

            assertIsShareToAppChip(latest)

            // WHEN media projection gets stopped via dialog
            val dialogStopAction =
                getStopActionFromDialog(latest, chipView, mockSystemUIDialog, kosmos)
            dialogStopAction.onClick(mock<DialogInterface>(), 0)

            // THEN the chip is immediately hidden with no animation
            assertThat(latest).isEqualTo(OngoingActivityChipModel.Hidden(shouldAnimate = false))
        }

    private fun assertIsDemoRonChip(latest: OngoingActivityChipModel?) {
        assertThat(latest).isInstanceOf(OngoingActivityChipModel.Shown::class.java)
        assertThat((latest as OngoingActivityChipModel.Shown).icon)
            .isInstanceOf(OngoingActivityChipModel.ChipIcon.FullColorAppIcon::class.java)
    }
}
