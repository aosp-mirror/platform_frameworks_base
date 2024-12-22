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
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.platform.test.annotations.DisableFlags
import android.view.View
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.Flags.FLAG_STATUS_BAR_RON_CHIPS
import com.android.systemui.SysuiTestCase
import com.android.systemui.common.shared.model.Icon
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.kosmos.Kosmos
import com.android.systemui.kosmos.testCase
import com.android.systemui.kosmos.testScope
import com.android.systemui.mediaprojection.data.model.MediaProjectionState
import com.android.systemui.mediaprojection.data.repository.fakeMediaProjectionRepository
import com.android.systemui.mediaprojection.taskswitcher.FakeActivityTaskManager.Companion.createTask
import com.android.systemui.res.R
import com.android.systemui.screenrecord.data.model.ScreenRecordModel
import com.android.systemui.screenrecord.data.repository.screenRecordRepository
import com.android.systemui.statusbar.chips.mediaprojection.domain.interactor.MediaProjectionChipInteractorTest.Companion.NORMAL_PACKAGE
import com.android.systemui.statusbar.chips.mediaprojection.domain.interactor.MediaProjectionChipInteractorTest.Companion.setUpPackageManagerForMediaProjection
import com.android.systemui.statusbar.chips.ron.demo.ui.viewmodel.demoRonChipViewModel
import com.android.systemui.statusbar.chips.ui.model.OngoingActivityChipModel
import com.android.systemui.statusbar.chips.ui.view.ChipBackgroundContainer
import com.android.systemui.statusbar.phone.SystemUIDialog
import com.android.systemui.statusbar.phone.mockSystemUIDialogFactory
import com.android.systemui.statusbar.phone.ongoingcall.data.repository.ongoingCallRepository
import com.android.systemui.statusbar.phone.ongoingcall.shared.model.OngoingCallModel
import com.android.systemui.statusbar.phone.ongoingcall.shared.model.inCallModel
import com.android.systemui.util.time.fakeSystemClock
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/**
 * Tests for [OngoingActivityChipsViewModel] when the [FLAG_STATUS_BAR_RON_CHIPS] flag is disabled.
 */
@SmallTest
@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalCoroutinesApi::class)
@DisableFlags(FLAG_STATUS_BAR_RON_CHIPS)
class OngoingActivityChipsViewModelTest : SysuiTestCase() {
    private val kosmos = Kosmos().also { it.testCase = this }
    private val testScope = kosmos.testScope
    private val systemClock = kosmos.fakeSystemClock

    private val screenRecordState = kosmos.screenRecordRepository.screenRecordState
    private val mediaProjectionState = kosmos.fakeMediaProjectionRepository.mediaProjectionState
    private val callRepo = kosmos.ongoingCallRepository

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
    fun primaryChip_screenRecordShow_restHidden_screenRecordShown() =
        testScope.runTest {
            screenRecordState.value = ScreenRecordModel.Recording
            mediaProjectionState.value = MediaProjectionState.NotProjecting
            callRepo.setOngoingCallState(OngoingCallModel.NoCall)

            val latest by collectLastValue(underTest.primaryChip)

            assertIsScreenRecordChip(latest)
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
    fun primaryChip_screenRecordAndShareToAppAndCastToOtherHideAndCallShown_callShown() =
        testScope.runTest {
            screenRecordState.value = ScreenRecordModel.DoingNothing
            // MediaProjection covers both share-to-app and cast-to-other-device
            mediaProjectionState.value = MediaProjectionState.NotProjecting

            callRepo.setOngoingCallState(inCallModel(startTimeMs = 34))

            val latest by collectLastValue(underTest.primaryChip)

            assertIsCallChip(latest)
        }

    @Test
    fun primaryChip_higherPriorityChipAdded_lowerPriorityChipReplaced() =
        testScope.runTest {
            // Start with just the lowest priority chip shown
            callRepo.setOngoingCallState(inCallModel(startTimeMs = 34))
            // And everything else hidden
            mediaProjectionState.value = MediaProjectionState.NotProjecting
            screenRecordState.value = ScreenRecordModel.DoingNothing

            val latest by collectLastValue(underTest.primaryChip)

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

    companion object {
        /**
         * Assuming that the click listener in [latest] opens a dialog, this fetches the action
         * associated with the positive button, which we assume is the "Stop sharing" action.
         */
        fun getStopActionFromDialog(
            latest: OngoingActivityChipModel?,
            chipView: View,
            dialog: SystemUIDialog,
            kosmos: Kosmos
        ): DialogInterface.OnClickListener {
            // Capture the action that would get invoked when the user clicks "Stop" on the dialog
            lateinit var dialogStopAction: DialogInterface.OnClickListener
            Mockito.doAnswer {
                    val delegate = it.arguments[0] as SystemUIDialog.Delegate
                    delegate.beforeCreate(dialog, /* savedInstanceState= */ null)

                    val stopActionCaptor = argumentCaptor<DialogInterface.OnClickListener>()
                    verify(dialog).setPositiveButton(any(), stopActionCaptor.capture())
                    dialogStopAction = stopActionCaptor.firstValue

                    return@doAnswer dialog
                }
                .whenever(kosmos.mockSystemUIDialogFactory)
                .create(any<SystemUIDialog.Delegate>())
            whenever(kosmos.packageManager.getApplicationInfo(eq(NORMAL_PACKAGE), any<Int>()))
                .thenThrow(PackageManager.NameNotFoundException())
            // Click the chip so that we open the dialog and we fill in [dialogStopAction]
            val clickListener = ((latest as OngoingActivityChipModel.Shown).onClickListener)
            clickListener!!.onClick(chipView)

            return dialogStopAction
        }

        fun assertIsScreenRecordChip(latest: OngoingActivityChipModel?) {
            assertThat(latest).isInstanceOf(OngoingActivityChipModel.Shown::class.java)
            val icon =
                (((latest as OngoingActivityChipModel.Shown).icon)
                        as OngoingActivityChipModel.ChipIcon.SingleColorIcon)
                    .impl as Icon.Resource
            assertThat(icon.res).isEqualTo(R.drawable.ic_screenrecord)
        }

        fun assertIsShareToAppChip(latest: OngoingActivityChipModel?) {
            assertThat(latest).isInstanceOf(OngoingActivityChipModel.Shown::class.java)
            val icon =
                (((latest as OngoingActivityChipModel.Shown).icon)
                        as OngoingActivityChipModel.ChipIcon.SingleColorIcon)
                    .impl as Icon.Resource
            assertThat(icon.res).isEqualTo(R.drawable.ic_present_to_all)
        }

        fun assertIsCallChip(latest: OngoingActivityChipModel?) {
            assertThat(latest).isInstanceOf(OngoingActivityChipModel.Shown::class.java)
            val icon =
                (((latest as OngoingActivityChipModel.Shown).icon)
                        as OngoingActivityChipModel.ChipIcon.SingleColorIcon)
                    .impl as Icon.Resource
            assertThat(icon.res).isEqualTo(com.android.internal.R.drawable.ic_phone)
        }
    }
}
