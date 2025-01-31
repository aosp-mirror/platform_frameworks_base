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

package com.android.systemui.statusbar.chips.sharetoapp.ui.viewmodel

import android.content.DialogInterface
import android.platform.test.annotations.DisableFlags
import android.platform.test.annotations.EnableFlags
import android.view.View
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.internal.jank.Cuj
import com.android.systemui.Flags.FLAG_STATUS_BAR_SHOW_AUDIO_ONLY_PROJECTION_CHIP
import com.android.systemui.SysuiTestCase
import com.android.systemui.animation.DialogCuj
import com.android.systemui.animation.Expandable
import com.android.systemui.animation.mockDialogTransitionAnimator
import com.android.systemui.common.shared.model.ContentDescription
import com.android.systemui.common.shared.model.Icon
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.kosmos.collectLastValue
import com.android.systemui.kosmos.runTest
import com.android.systemui.kosmos.testScope
import com.android.systemui.kosmos.useUnconfinedTestDispatcher
import com.android.systemui.mediaprojection.data.model.MediaProjectionState
import com.android.systemui.mediaprojection.data.repository.fakeMediaProjectionRepository
import com.android.systemui.mediaprojection.taskswitcher.FakeActivityTaskManager.Companion.createTask
import com.android.systemui.res.R
import com.android.systemui.statusbar.chips.mediaprojection.domain.interactor.MediaProjectionChipInteractorTest.Companion.CAST_TO_OTHER_DEVICES_PACKAGE
import com.android.systemui.statusbar.chips.mediaprojection.domain.interactor.MediaProjectionChipInteractorTest.Companion.NORMAL_PACKAGE
import com.android.systemui.statusbar.chips.mediaprojection.domain.interactor.MediaProjectionChipInteractorTest.Companion.setUpPackageManagerForMediaProjection
import com.android.systemui.statusbar.chips.mediaprojection.domain.model.MediaProjectionStopDialogModel
import com.android.systemui.statusbar.chips.sharetoapp.ui.view.EndGenericShareToAppDialogDelegate
import com.android.systemui.statusbar.chips.sharetoapp.ui.view.EndShareScreenToAppDialogDelegate
import com.android.systemui.statusbar.chips.ui.model.ColorsModel
import com.android.systemui.statusbar.chips.ui.model.OngoingActivityChipModel
import com.android.systemui.statusbar.chips.ui.view.ChipBackgroundContainer
import com.android.systemui.statusbar.chips.ui.viewmodel.OngoingActivityChipsViewModelTest.Companion.getStopActionFromDialog
import com.android.systemui.statusbar.core.StatusBarRootModernization
import com.android.systemui.statusbar.phone.SystemUIDialog
import com.android.systemui.statusbar.phone.mockSystemUIDialogFactory
import com.android.systemui.statusbar.phone.ongoingcall.StatusBarChipsModernization
import com.android.systemui.testKosmos
import com.android.systemui.util.time.fakeSystemClock
import com.google.common.truth.Truth.assertThat
import kotlin.test.Test
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.anyBoolean
import org.mockito.Mockito.times
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

@SmallTest
@RunWith(AndroidJUnit4::class)
class ShareToAppChipViewModelTest : SysuiTestCase() {
    private val kosmos = testKosmos().useUnconfinedTestDispatcher()
    private val testScope = kosmos.testScope
    private val mediaProjectionRepo = kosmos.fakeMediaProjectionRepository
    private val systemClock = kosmos.fakeSystemClock

    private val mockScreenShareDialog = mock<SystemUIDialog>()
    private val mockGenericShareDialog = mock<SystemUIDialog>()
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

    private val underTest = kosmos.shareToAppChipViewModel
    private val mockDialog = mock<SystemUIDialog>()

    @Before
    fun setUp() {
        underTest.start()
        setUpPackageManagerForMediaProjection(kosmos)

        whenever(kosmos.mockSystemUIDialogFactory.create(any<EndShareScreenToAppDialogDelegate>()))
            .thenReturn(mockScreenShareDialog)
        whenever(kosmos.mockSystemUIDialogFactory.create(any<EndGenericShareToAppDialogDelegate>()))
            .thenReturn(mockGenericShareDialog)
    }

    @Test
    @EnableFlags(com.android.media.projection.flags.Flags.FLAG_SHOW_STOP_DIALOG_POST_CALL_END)
    fun chip_flagEnabled_projectionStartedDuringCallAndActivePostCallEventEmitted_chipHidden() =
        kosmos.runTest {
            val latestChip by collectLastValue(underTest.chip)

            // Set mediaProjectionState to Projecting
            mediaProjectionRepo.mediaProjectionState.value =
                MediaProjectionState.Projecting.EntireScreen(NORMAL_PACKAGE)

            // Verify the chip is initially shown
            assertThat(latestChip).isInstanceOf(OngoingActivityChipModel.Shown::class.java)

            fakeMediaProjectionRepository.emitProjectionStartedDuringCallAndActivePostCallEvent()

            // Verify the chip is hidden
            assertThat(latestChip).isInstanceOf(OngoingActivityChipModel.Hidden::class.java)
        }

    @Test
    @DisableFlags(com.android.media.projection.flags.Flags.FLAG_SHOW_STOP_DIALOG_POST_CALL_END)
    fun chip_flagDisabled_projectionStartedDuringCallAndActivePostCallEventEmitted_chipRemainsVisible() =
        kosmos.runTest {
            val latestChip by collectLastValue(underTest.chip)

            // Set mediaProjectionState to Projecting
            mediaProjectionRepo.mediaProjectionState.value =
                MediaProjectionState.Projecting.EntireScreen(NORMAL_PACKAGE)

            // Verify the chip is initially shown
            assertThat(latestChip).isInstanceOf(OngoingActivityChipModel.Shown::class.java)

            fakeMediaProjectionRepository.emitProjectionStartedDuringCallAndActivePostCallEvent()

            // Chip is still shown
            assertThat(latestChip).isInstanceOf(OngoingActivityChipModel.Shown::class.java)
        }

    @Test
    @EnableFlags(com.android.media.projection.flags.Flags.FLAG_SHOW_STOP_DIALOG_POST_CALL_END)
    fun stopDialog_flagEnabled_initialState_isHidden() =
        kosmos.runTest {
            val latest by collectLastValue(underTest.stopDialogToShow)

            assertThat(latest).isEqualTo(MediaProjectionStopDialogModel.Hidden)
        }

    @Test
    @DisableFlags(com.android.media.projection.flags.Flags.FLAG_SHOW_STOP_DIALOG_POST_CALL_END)
    fun stopDialog_flagDisabled_projectionStartedDuringCallAndActivePostCallEventEmitted_dialogRemainsHidden() =
        kosmos.runTest {
            val latestStopDialogModel by collectLastValue(underTest.stopDialogToShow)

            // Set mediaProjectionRepo state to Projecting
            mediaProjectionRepo.mediaProjectionState.value =
                MediaProjectionState.Projecting.EntireScreen(NORMAL_PACKAGE)

            fakeMediaProjectionRepository.emitProjectionStartedDuringCallAndActivePostCallEvent()

            // Verify that no dialog is shown
            assertThat(latestStopDialogModel).isEqualTo(MediaProjectionStopDialogModel.Hidden)
        }

    @Test
    @EnableFlags(com.android.media.projection.flags.Flags.FLAG_SHOW_STOP_DIALOG_POST_CALL_END)
    fun stopDialog_notProjectingState_flagEnabled_remainsHidden() =
        kosmos.runTest {
            val latest by collectLastValue(underTest.stopDialogToShow)

            // Set the state to not projecting
            mediaProjectionRepo.mediaProjectionState.value = MediaProjectionState.NotProjecting

            fakeMediaProjectionRepository.emitProjectionStartedDuringCallAndActivePostCallEvent()

            // Verify that the dialog remains hidden
            assertThat(latest).isEqualTo(MediaProjectionStopDialogModel.Hidden)
        }

    @Test
    @EnableFlags(
        com.android.media.projection.flags.Flags.FLAG_SHOW_STOP_DIALOG_POST_CALL_END,
        FLAG_STATUS_BAR_SHOW_AUDIO_ONLY_PROJECTION_CHIP,
    )
    fun stopDialog_projectingAudio_flagEnabled_eventEmitted_showsGenericStopDialog() =
        kosmos.runTest {
            val latest by collectLastValue(underTest.stopDialogToShow)

            // Set the state to projecting audio
            mediaProjectionRepo.mediaProjectionState.value =
                MediaProjectionState.Projecting.NoScreen(NORMAL_PACKAGE)

            fakeMediaProjectionRepository.emitProjectionStartedDuringCallAndActivePostCallEvent()

            // Verify that the generic dialog is shown
            assertThat(latest).isInstanceOf(MediaProjectionStopDialogModel.Shown::class.java)
            val dialogDelegate = (latest as MediaProjectionStopDialogModel.Shown).dialogDelegate
            assertThat(dialogDelegate).isInstanceOf(EndGenericShareToAppDialogDelegate::class.java)
        }

    @Test
    @EnableFlags(com.android.media.projection.flags.Flags.FLAG_SHOW_STOP_DIALOG_POST_CALL_END)
    fun stopDialog_projectingEntireScreen_flagEnabled_eventEmitted_showsShareScreenToAppStopDialog() =
        kosmos.runTest {
            val latest by collectLastValue(underTest.stopDialogToShow)

            // Set the state to projecting the entire screen
            mediaProjectionRepo.mediaProjectionState.value =
                MediaProjectionState.Projecting.EntireScreen(NORMAL_PACKAGE)

            assertThat(latest).isInstanceOf(MediaProjectionStopDialogModel.Hidden::class.java)

            fakeMediaProjectionRepository.emitProjectionStartedDuringCallAndActivePostCallEvent()

            // Verify that the dialog is shown
            assertThat(latest).isInstanceOf(MediaProjectionStopDialogModel.Shown::class.java)
            val dialogDelegate = (latest as MediaProjectionStopDialogModel.Shown).dialogDelegate
            assertThat(dialogDelegate).isInstanceOf(EndShareScreenToAppDialogDelegate::class.java)
        }

    @Test
    @EnableFlags(com.android.media.projection.flags.Flags.FLAG_SHOW_STOP_DIALOG_POST_CALL_END)
    fun stopDialog_projectingEntireScreen_eventEmitted_hasCancelBehaviour() =
        kosmos.runTest {
            val latestDialogModel by collectLastValue(underTest.stopDialogToShow)

            mediaProjectionRepo.mediaProjectionState.value =
                MediaProjectionState.Projecting.EntireScreen(NORMAL_PACKAGE)

            fakeMediaProjectionRepository.emitProjectionStartedDuringCallAndActivePostCallEvent()

            // Verify that the dialog is shown
            assertThat(latestDialogModel)
                .isInstanceOf(MediaProjectionStopDialogModel.Shown::class.java)

            val dialogModel = latestDialogModel as MediaProjectionStopDialogModel.Shown

            whenever(dialogModel.dialogDelegate.createDialog()).thenReturn(mockDialog)

            dialogModel.createAndShowDialog()

            // Verify dialog is shown
            verify(mockDialog).show()

            // Verify dialog is hidden when dialog is cancelled
            argumentCaptor<DialogInterface.OnCancelListener>().apply {
                verify(mockDialog).setOnCancelListener(capture())
                firstValue.onCancel(mockDialog)
            }
            assertThat(underTest.stopDialogToShow.value)
                .isEqualTo(MediaProjectionStopDialogModel.Hidden)

            verify(mockDialog, times(1)).setOnCancelListener(any())
        }

    @Test
    @EnableFlags(com.android.media.projection.flags.Flags.FLAG_SHOW_STOP_DIALOG_POST_CALL_END)
    fun stopDialog_projectingEntireScreen_eventEmitted_hasDismissBehaviour() =
        kosmos.runTest {
            val latestDialogModel by collectLastValue(underTest.stopDialogToShow)

            mediaProjectionRepo.mediaProjectionState.value =
                MediaProjectionState.Projecting.EntireScreen(NORMAL_PACKAGE)

            fakeMediaProjectionRepository.emitProjectionStartedDuringCallAndActivePostCallEvent()

            // Verify that the dialog is shown
            assertThat(latestDialogModel)
                .isInstanceOf(MediaProjectionStopDialogModel.Shown::class.java)

            val dialogModel = latestDialogModel as MediaProjectionStopDialogModel.Shown

            whenever(dialogModel.dialogDelegate.createDialog()).thenReturn(mockDialog)

            // Simulate showing the dialog
            dialogModel.createAndShowDialog()

            // Verify dialog is shown
            verify(mockDialog).show()

            // Verify dialog is hidden when dialog is dismissed
            argumentCaptor<DialogInterface.OnDismissListener>().apply {
                verify(mockDialog).setOnDismissListener(capture())
                firstValue.onDismiss(mockDialog)
            }
            assertThat(underTest.stopDialogToShow.value)
                .isEqualTo(MediaProjectionStopDialogModel.Hidden)

            verify(mockDialog, times(1)).setOnDismissListener(any())
        }

    @Test
    @EnableFlags(com.android.media.projection.flags.Flags.FLAG_SHOW_STOP_DIALOG_POST_CALL_END)
    fun stopDialog_flagEnabled_eventEmitted_dialogCannotBeDismissedByTouchOutside() =
        kosmos.runTest {
            val latestDialogModel by collectLastValue(underTest.stopDialogToShow)

            mediaProjectionRepo.mediaProjectionState.value =
                MediaProjectionState.Projecting.EntireScreen(NORMAL_PACKAGE)

            fakeMediaProjectionRepository.emitProjectionStartedDuringCallAndActivePostCallEvent()

            // Verify that the dialog is shown
            assertThat(latestDialogModel)
                .isInstanceOf(MediaProjectionStopDialogModel.Shown::class.java)

            val dialogModel = latestDialogModel as MediaProjectionStopDialogModel.Shown

            whenever(dialogModel.dialogDelegate.createDialog()).thenReturn(mockDialog)

            dialogModel.createAndShowDialog()

            verify(mockDialog).show()

            // Verify that setCanceledOnTouchOutside(false) is called
            verify(mockDialog).setCanceledOnTouchOutside(false)
        }

    @Test
    fun chip_notProjectingState_isHidden() =
        testScope.runTest {
            val latest by collectLastValue(underTest.chip)

            mediaProjectionRepo.mediaProjectionState.value = MediaProjectionState.NotProjecting

            assertThat(latest).isInstanceOf(OngoingActivityChipModel.Hidden::class.java)
        }

    @Test
    @EnableFlags(FLAG_STATUS_BAR_SHOW_AUDIO_ONLY_PROJECTION_CHIP)
    fun chip_noScreenState_otherDevicesPackage_isHidden() =
        testScope.runTest {
            val latest by collectLastValue(underTest.chip)

            mediaProjectionRepo.mediaProjectionState.value =
                MediaProjectionState.Projecting.NoScreen(
                    CAST_TO_OTHER_DEVICES_PACKAGE,
                    hostDeviceName = null,
                )

            assertThat(latest).isInstanceOf(OngoingActivityChipModel.Hidden::class.java)
        }

    @Test
    fun chip_singleTaskState_otherDevicesPackage_isHidden() =
        testScope.runTest {
            val latest by collectLastValue(underTest.chip)

            mediaProjectionRepo.mediaProjectionState.value =
                MediaProjectionState.Projecting.SingleTask(
                    CAST_TO_OTHER_DEVICES_PACKAGE,
                    hostDeviceName = null,
                    createTask(taskId = 1),
                )

            assertThat(latest).isInstanceOf(OngoingActivityChipModel.Hidden::class.java)
        }

    @Test
    fun chip_entireScreenState_otherDevicesPackage_isHidden() =
        testScope.runTest {
            val latest by collectLastValue(underTest.chip)

            mediaProjectionRepo.mediaProjectionState.value =
                MediaProjectionState.Projecting.EntireScreen(CAST_TO_OTHER_DEVICES_PACKAGE)

            assertThat(latest).isInstanceOf(OngoingActivityChipModel.Hidden::class.java)
        }

    @Test
    @EnableFlags(FLAG_STATUS_BAR_SHOW_AUDIO_ONLY_PROJECTION_CHIP)
    fun chip_noScreenState_normalPackage_isShownAsIconOnly() =
        testScope.runTest {
            val latest by collectLastValue(underTest.chip)

            mediaProjectionRepo.mediaProjectionState.value =
                MediaProjectionState.Projecting.NoScreen(NORMAL_PACKAGE, hostDeviceName = null)

            assertThat(latest).isInstanceOf(OngoingActivityChipModel.Shown.IconOnly::class.java)
            val icon =
                (((latest as OngoingActivityChipModel.Shown).icon)
                        as OngoingActivityChipModel.ChipIcon.SingleColorIcon)
                    .impl as Icon.Resource
            assertThat(icon.res).isEqualTo(R.drawable.ic_present_to_all)
            // This content description is just generic "Sharing content", not "Sharing screen"
            assertThat((icon.contentDescription as ContentDescription.Resource).res)
                .isEqualTo(R.string.share_to_app_chip_accessibility_label_generic)
        }

    @Test
    fun chip_singleTaskState_normalPackage_isShownAsTimer() =
        testScope.runTest {
            val latest by collectLastValue(underTest.chip)

            mediaProjectionRepo.mediaProjectionState.value =
                MediaProjectionState.Projecting.SingleTask(
                    NORMAL_PACKAGE,
                    hostDeviceName = null,
                    createTask(taskId = 1),
                )

            assertThat(latest).isInstanceOf(OngoingActivityChipModel.Shown.Timer::class.java)
            val icon =
                (((latest as OngoingActivityChipModel.Shown).icon)
                        as OngoingActivityChipModel.ChipIcon.SingleColorIcon)
                    .impl as Icon.Resource
            assertThat(icon.res).isEqualTo(R.drawable.ic_present_to_all)
            assertThat(icon.contentDescription).isNotNull()
        }

    @Test
    fun chip_entireScreenState_normalPackage_isShownAsTimer() =
        testScope.runTest {
            val latest by collectLastValue(underTest.chip)

            mediaProjectionRepo.mediaProjectionState.value =
                MediaProjectionState.Projecting.EntireScreen(NORMAL_PACKAGE)

            assertThat(latest).isInstanceOf(OngoingActivityChipModel.Shown.Timer::class.java)
            val icon =
                (((latest as OngoingActivityChipModel.Shown).icon)
                        as OngoingActivityChipModel.ChipIcon.SingleColorIcon)
                    .impl as Icon.Resource
            assertThat(icon.res).isEqualTo(R.drawable.ic_present_to_all)
            assertThat(icon.contentDescription).isNotNull()
        }

    @Test
    fun chip_shareStoppedFromDialog_chipImmediatelyHidden() =
        testScope.runTest {
            val latest by collectLastValue(underTest.chip)

            mediaProjectionRepo.mediaProjectionState.value =
                MediaProjectionState.Projecting.EntireScreen(NORMAL_PACKAGE)

            assertThat(latest).isInstanceOf(OngoingActivityChipModel.Shown::class.java)

            // WHEN the stop action on the dialog is clicked
            val dialogStopAction =
                getStopActionFromDialog(
                    latest,
                    chipView,
                    mockExpandable,
                    mockScreenShareDialog,
                    kosmos,
                )
            dialogStopAction.onClick(mock<DialogInterface>(), 0)

            // THEN the chip is immediately hidden...
            assertThat(latest).isInstanceOf(OngoingActivityChipModel.Hidden::class.java)
            // ...even though the repo still says it's projecting
            assertThat(mediaProjectionRepo.mediaProjectionState.value)
                .isInstanceOf(MediaProjectionState.Projecting::class.java)

            // AND we specify no animation
            assertThat((latest as OngoingActivityChipModel.Hidden).shouldAnimate).isFalse()
        }

    @Test
    fun chip_colorsAreRed() =
        testScope.runTest {
            val latest by collectLastValue(underTest.chip)

            mediaProjectionRepo.mediaProjectionState.value =
                MediaProjectionState.Projecting.EntireScreen(NORMAL_PACKAGE)

            assertThat((latest as OngoingActivityChipModel.Shown).colors).isEqualTo(ColorsModel.Red)
        }

    @Test
    fun chip_timeResetsOnEachNewShare() =
        testScope.runTest {
            val latest by collectLastValue(underTest.chip)

            systemClock.setElapsedRealtime(1234)
            mediaProjectionRepo.mediaProjectionState.value =
                MediaProjectionState.Projecting.EntireScreen(NORMAL_PACKAGE)

            assertThat(latest).isInstanceOf(OngoingActivityChipModel.Shown::class.java)
            assertThat((latest as OngoingActivityChipModel.Shown.Timer).startTimeMs).isEqualTo(1234)

            mediaProjectionRepo.mediaProjectionState.value = MediaProjectionState.NotProjecting
            assertThat(latest).isInstanceOf(OngoingActivityChipModel.Hidden::class.java)

            systemClock.setElapsedRealtime(5678)
            mediaProjectionRepo.mediaProjectionState.value =
                MediaProjectionState.Projecting.SingleTask(
                    NORMAL_PACKAGE,
                    hostDeviceName = null,
                    createTask(taskId = 1),
                )

            assertThat(latest).isInstanceOf(OngoingActivityChipModel.Shown::class.java)
            assertThat((latest as OngoingActivityChipModel.Shown.Timer).startTimeMs).isEqualTo(5678)
        }

    @Test
    @EnableFlags(FLAG_STATUS_BAR_SHOW_AUDIO_ONLY_PROJECTION_CHIP)
    @DisableFlags(StatusBarChipsModernization.FLAG_NAME)
    fun chip_noScreen_clickListenerShowsGenericShareDialog() =
        testScope.runTest {
            val latest by collectLastValue(underTest.chip)
            mediaProjectionRepo.mediaProjectionState.value =
                MediaProjectionState.Projecting.NoScreen(NORMAL_PACKAGE)

            val clickListener = ((latest as OngoingActivityChipModel.Shown).onClickListenerLegacy)
            assertThat(clickListener).isNotNull()

            clickListener!!.onClick(chipView)
            verify(kosmos.mockDialogTransitionAnimator)
                .showFromView(
                    eq(mockGenericShareDialog),
                    eq(chipBackgroundView),
                    any(),
                    anyBoolean(),
                )
        }

    @Test
    @DisableFlags(StatusBarChipsModernization.FLAG_NAME)
    fun chip_entireScreen_clickListenerShowsScreenShareDialog() =
        testScope.runTest {
            val latest by collectLastValue(underTest.chip)
            mediaProjectionRepo.mediaProjectionState.value =
                MediaProjectionState.Projecting.EntireScreen(NORMAL_PACKAGE)

            val clickListener = ((latest as OngoingActivityChipModel.Shown).onClickListenerLegacy)
            assertThat(clickListener).isNotNull()

            clickListener!!.onClick(chipView)
            verify(kosmos.mockDialogTransitionAnimator)
                .showFromView(
                    eq(mockScreenShareDialog),
                    eq(chipBackgroundView),
                    any(),
                    anyBoolean(),
                )
        }

    @Test
    @DisableFlags(StatusBarChipsModernization.FLAG_NAME)
    fun chip_singleTask_clickListenerShowsScreenShareDialog() =
        testScope.runTest {
            val latest by collectLastValue(underTest.chip)
            mediaProjectionRepo.mediaProjectionState.value =
                MediaProjectionState.Projecting.SingleTask(
                    NORMAL_PACKAGE,
                    hostDeviceName = null,
                    createTask(taskId = 1),
                )

            val clickListener = ((latest as OngoingActivityChipModel.Shown).onClickListenerLegacy)
            assertThat(clickListener).isNotNull()

            clickListener!!.onClick(chipView)
            verify(kosmos.mockDialogTransitionAnimator)
                .showFromView(
                    eq(mockScreenShareDialog),
                    eq(chipBackgroundView),
                    any(),
                    anyBoolean(),
                )
        }

    @Test
    @DisableFlags(StatusBarChipsModernization.FLAG_NAME)
    fun chip_clickListenerHasCuj() =
        testScope.runTest {
            val latest by collectLastValue(underTest.chip)
            mediaProjectionRepo.mediaProjectionState.value =
                MediaProjectionState.Projecting.SingleTask(
                    NORMAL_PACKAGE,
                    hostDeviceName = null,
                    createTask(taskId = 1),
                )

            val clickListener = ((latest as OngoingActivityChipModel.Shown).onClickListenerLegacy)
            clickListener!!.onClick(chipView)

            val cujCaptor = argumentCaptor<DialogCuj>()
            verify(kosmos.mockDialogTransitionAnimator)
                .showFromView(any(), any(), cujCaptor.capture(), anyBoolean())

            assertThat(cujCaptor.firstValue.cujType)
                .isEqualTo(Cuj.CUJ_STATUS_BAR_LAUNCH_DIALOG_FROM_CHIP)
            assertThat(cujCaptor.firstValue.tag).contains("Share")
        }

    @Test
    @EnableFlags(StatusBarRootModernization.FLAG_NAME, StatusBarChipsModernization.FLAG_NAME)
    fun chip_noScreen_hasClickBehavior() =
        testScope.runTest {
            val latest by collectLastValue(underTest.chip)
            mediaProjectionRepo.mediaProjectionState.value =
                MediaProjectionState.Projecting.NoScreen(NORMAL_PACKAGE)

            assertThat((latest as OngoingActivityChipModel.Shown).clickBehavior)
                .isInstanceOf(OngoingActivityChipModel.ClickBehavior.ExpandAction::class.java)
        }

    @Test
    @EnableFlags(StatusBarRootModernization.FLAG_NAME, StatusBarChipsModernization.FLAG_NAME)
    fun chip_entireScreen_hasClickBehavior() =
        testScope.runTest {
            val latest by collectLastValue(underTest.chip)
            mediaProjectionRepo.mediaProjectionState.value =
                MediaProjectionState.Projecting.EntireScreen(NORMAL_PACKAGE)

            assertThat((latest as OngoingActivityChipModel.Shown).clickBehavior)
                .isInstanceOf(OngoingActivityChipModel.ClickBehavior.ExpandAction::class.java)
        }

    @Test
    @EnableFlags(StatusBarRootModernization.FLAG_NAME, StatusBarChipsModernization.FLAG_NAME)
    fun chip_singleTask_hasClickBehavior() =
        testScope.runTest {
            val latest by collectLastValue(underTest.chip)
            mediaProjectionRepo.mediaProjectionState.value =
                MediaProjectionState.Projecting.SingleTask(
                    NORMAL_PACKAGE,
                    hostDeviceName = null,
                    createTask(taskId = 1),
                )

            assertThat((latest as OngoingActivityChipModel.Shown).clickBehavior)
                .isInstanceOf(OngoingActivityChipModel.ClickBehavior.ExpandAction::class.java)
        }

    @Test
    @EnableFlags(
        FLAG_STATUS_BAR_SHOW_AUDIO_ONLY_PROJECTION_CHIP,
        StatusBarRootModernization.FLAG_NAME,
        StatusBarChipsModernization.FLAG_NAME,
    )
    fun chip_noScreen_clickBehaviorShowsGenericShareDialog() =
        testScope.runTest {
            val latest by collectLastValue(underTest.chip)
            mediaProjectionRepo.mediaProjectionState.value =
                MediaProjectionState.Projecting.NoScreen(NORMAL_PACKAGE)

            val expandAction =
                ((latest as OngoingActivityChipModel.Shown).clickBehavior
                    as OngoingActivityChipModel.ClickBehavior.ExpandAction)
            expandAction.onClick(mockExpandable)
            verify(kosmos.mockDialogTransitionAnimator)
                .show(eq(mockGenericShareDialog), any(), any())
        }

    @Test
    @EnableFlags(StatusBarRootModernization.FLAG_NAME, StatusBarChipsModernization.FLAG_NAME)
    fun chip_entireScreen_clickBehaviorShowsScreenShareDialog() =
        testScope.runTest {
            val latest by collectLastValue(underTest.chip)
            mediaProjectionRepo.mediaProjectionState.value =
                MediaProjectionState.Projecting.EntireScreen(NORMAL_PACKAGE)

            val expandAction =
                ((latest as OngoingActivityChipModel.Shown).clickBehavior
                    as OngoingActivityChipModel.ClickBehavior.ExpandAction)
            expandAction.onClick(mockExpandable)
            verify(kosmos.mockDialogTransitionAnimator)
                .show(eq(mockScreenShareDialog), any(), any())
        }

    @Test
    @EnableFlags(StatusBarRootModernization.FLAG_NAME, StatusBarChipsModernization.FLAG_NAME)
    fun chip_singleTask_clickBehaviorShowsScreenShareDialog() =
        testScope.runTest {
            val latest by collectLastValue(underTest.chip)
            mediaProjectionRepo.mediaProjectionState.value =
                MediaProjectionState.Projecting.SingleTask(
                    NORMAL_PACKAGE,
                    hostDeviceName = null,
                    createTask(taskId = 1),
                )

            val expandAction =
                ((latest as OngoingActivityChipModel.Shown).clickBehavior
                    as OngoingActivityChipModel.ClickBehavior.ExpandAction)
            expandAction.onClick(mockExpandable)

            verify(kosmos.mockDialogTransitionAnimator)
                .show(eq(mockScreenShareDialog), any(), any())
        }
}
