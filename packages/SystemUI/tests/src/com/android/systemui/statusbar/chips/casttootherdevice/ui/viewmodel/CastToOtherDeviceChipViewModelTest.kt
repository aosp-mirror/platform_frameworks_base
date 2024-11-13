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

package com.android.systemui.statusbar.chips.casttootherdevice.ui.viewmodel

import android.content.DialogInterface
import android.view.View
import androidx.test.filters.SmallTest
import com.android.internal.jank.Cuj
import com.android.systemui.SysuiTestCase
import com.android.systemui.animation.DialogCuj
import com.android.systemui.animation.mockDialogTransitionAnimator
import com.android.systemui.common.shared.model.ContentDescription
import com.android.systemui.common.shared.model.Icon
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.kosmos.Kosmos
import com.android.systemui.kosmos.testCase
import com.android.systemui.kosmos.testScope
import com.android.systemui.mediaprojection.data.model.MediaProjectionState
import com.android.systemui.mediaprojection.data.repository.fakeMediaProjectionRepository
import com.android.systemui.mediaprojection.taskswitcher.FakeActivityTaskManager.Companion.createTask
import com.android.systemui.mediarouter.data.repository.fakeMediaRouterRepository
import com.android.systemui.res.R
import com.android.systemui.statusbar.chips.casttootherdevice.ui.view.EndCastScreenToOtherDeviceDialogDelegate
import com.android.systemui.statusbar.chips.casttootherdevice.ui.view.EndGenericCastToOtherDeviceDialogDelegate
import com.android.systemui.statusbar.chips.mediaprojection.domain.interactor.MediaProjectionChipInteractorTest.Companion.CAST_TO_OTHER_DEVICES_PACKAGE
import com.android.systemui.statusbar.chips.mediaprojection.domain.interactor.MediaProjectionChipInteractorTest.Companion.NORMAL_PACKAGE
import com.android.systemui.statusbar.chips.mediaprojection.domain.interactor.MediaProjectionChipInteractorTest.Companion.setUpPackageManagerForMediaProjection
import com.android.systemui.statusbar.chips.ui.model.ColorsModel
import com.android.systemui.statusbar.chips.ui.model.OngoingActivityChipModel
import com.android.systemui.statusbar.chips.ui.view.ChipBackgroundContainer
import com.android.systemui.statusbar.chips.ui.viewmodel.OngoingActivityChipsViewModelTest.Companion.getStopActionFromDialog
import com.android.systemui.statusbar.phone.SystemUIDialog
import com.android.systemui.statusbar.phone.mockSystemUIDialogFactory
import com.android.systemui.statusbar.policy.CastDevice
import com.android.systemui.util.time.fakeSystemClock
import com.google.common.truth.Truth.assertThat
import kotlin.test.Test
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.mockito.ArgumentMatchers.anyBoolean
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

@SmallTest
class CastToOtherDeviceChipViewModelTest : SysuiTestCase() {
    private val kosmos = Kosmos().also { it.testCase = this }
    private val testScope = kosmos.testScope
    private val mediaProjectionRepo = kosmos.fakeMediaProjectionRepository
    private val mediaRouterRepo = kosmos.fakeMediaRouterRepository
    private val systemClock = kosmos.fakeSystemClock

    private val mockScreenCastDialog = mock<SystemUIDialog>()
    private val mockGenericCastDialog = mock<SystemUIDialog>()
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

    private val underTest = kosmos.castToOtherDeviceChipViewModel

    @Before
    fun setUp() {
        setUpPackageManagerForMediaProjection(kosmos)

        whenever(
                kosmos.mockSystemUIDialogFactory.create(
                    any<EndCastScreenToOtherDeviceDialogDelegate>()
                )
            )
            .thenReturn(mockScreenCastDialog)
        whenever(
                kosmos.mockSystemUIDialogFactory.create(
                    any<EndGenericCastToOtherDeviceDialogDelegate>()
                )
            )
            .thenReturn(mockGenericCastDialog)
    }

    @Test
    fun chip_notProjectingState_isHidden() =
        testScope.runTest {
            val latest by collectLastValue(underTest.chip)
            mediaRouterRepo.castDevices.value = emptyList()

            mediaProjectionRepo.mediaProjectionState.value = MediaProjectionState.NotProjecting

            assertThat(latest).isInstanceOf(OngoingActivityChipModel.Hidden::class.java)
        }

    @Test
    fun chip_projectionIsSingleTaskState_otherDevicesPackage_isShownAsTimer_forScreen() =
        testScope.runTest {
            val latest by collectLastValue(underTest.chip)
            mediaRouterRepo.castDevices.value = emptyList()

            mediaProjectionRepo.mediaProjectionState.value =
                MediaProjectionState.Projecting.SingleTask(
                    CAST_TO_OTHER_DEVICES_PACKAGE,
                    hostDeviceName = null,
                    createTask(taskId = 1),
                )

            assertThat(latest).isInstanceOf(OngoingActivityChipModel.Shown.Timer::class.java)
            val icon =
                (((latest as OngoingActivityChipModel.Shown).icon)
                        as OngoingActivityChipModel.ChipIcon.SingleColorIcon)
                    .impl as Icon.Resource
            assertThat(icon.res).isEqualTo(R.drawable.ic_cast_connected)
            assertThat((icon.contentDescription as ContentDescription.Resource).res)
                .isEqualTo(R.string.cast_screen_to_other_device_chip_accessibility_label)
        }

    @Test
    fun chip_projectionIsEntireScreenState_otherDevicesPackage_isShownAsTimer_forScreen() =
        testScope.runTest {
            val latest by collectLastValue(underTest.chip)
            mediaRouterRepo.castDevices.value = emptyList()

            mediaProjectionRepo.mediaProjectionState.value =
                MediaProjectionState.Projecting.EntireScreen(CAST_TO_OTHER_DEVICES_PACKAGE)

            assertThat(latest).isInstanceOf(OngoingActivityChipModel.Shown.Timer::class.java)
            val icon =
                (((latest as OngoingActivityChipModel.Shown).icon)
                        as OngoingActivityChipModel.ChipIcon.SingleColorIcon)
                    .impl as Icon.Resource
            assertThat(icon.res).isEqualTo(R.drawable.ic_cast_connected)
            assertThat((icon.contentDescription as ContentDescription.Resource).res)
                .isEqualTo(R.string.cast_screen_to_other_device_chip_accessibility_label)
        }

    @Test
    fun chip_routerStateDoingNothing_isHidden() =
        testScope.runTest {
            val latest by collectLastValue(underTest.chip)
            mediaProjectionRepo.mediaProjectionState.value = MediaProjectionState.NotProjecting

            mediaRouterRepo.castDevices.value = emptyList()

            assertThat(latest).isInstanceOf(OngoingActivityChipModel.Hidden::class.java)
        }

    @Test
    fun chip_routerStateCasting_isShownAsGenericIconOnly() =
        testScope.runTest {
            val latest by collectLastValue(underTest.chip)
            mediaProjectionRepo.mediaProjectionState.value = MediaProjectionState.NotProjecting

            mediaRouterRepo.castDevices.value =
                listOf(
                    CastDevice(
                        state = CastDevice.CastState.Connected,
                        id = "id",
                        name = "name",
                        description = "desc",
                        origin = CastDevice.CastOrigin.MediaRouter,
                    )
                )

            assertThat(latest).isInstanceOf(OngoingActivityChipModel.Shown.IconOnly::class.java)
            val icon =
                (((latest as OngoingActivityChipModel.Shown).icon)
                        as OngoingActivityChipModel.ChipIcon.SingleColorIcon)
                    .impl as Icon.Resource
            assertThat(icon.res).isEqualTo(R.drawable.ic_cast_connected)
            // This content description is just generic "Casting", not "Casting screen"
            assertThat((icon.contentDescription as ContentDescription.Resource).res)
                .isEqualTo(R.string.accessibility_casting)
        }

    @Test
    fun chip_projectingAndRouterCasting_projectionInfoShown() =
        testScope.runTest {
            val latest by collectLastValue(underTest.chip)

            mediaProjectionRepo.mediaProjectionState.value =
                MediaProjectionState.Projecting.EntireScreen(CAST_TO_OTHER_DEVICES_PACKAGE)
            mediaRouterRepo.castDevices.value =
                listOf(
                    CastDevice(
                        state = CastDevice.CastState.Connected,
                        id = "id",
                        name = "name",
                        description = "desc",
                        origin = CastDevice.CastOrigin.MediaRouter,
                    )
                )

            // Only the projection info will show a timer
            assertThat(latest).isInstanceOf(OngoingActivityChipModel.Shown.Timer::class.java)
            val icon =
                (((latest as OngoingActivityChipModel.Shown).icon)
                        as OngoingActivityChipModel.ChipIcon.SingleColorIcon)
                    .impl as Icon.Resource
            assertThat(icon.res).isEqualTo(R.drawable.ic_cast_connected)
            // MediaProjection == screen casting, so this content description reflects that we're
            // using the MediaProjection information.
            assertThat((icon.contentDescription as ContentDescription.Resource).res)
                .isEqualTo(R.string.cast_screen_to_other_device_chip_accessibility_label)
        }

    @Test
    fun chip_projectionStoppedFromDialog_chipImmediatelyHidden() =
        testScope.runTest {
            val latest by collectLastValue(underTest.chip)

            mediaProjectionRepo.mediaProjectionState.value =
                MediaProjectionState.Projecting.EntireScreen(CAST_TO_OTHER_DEVICES_PACKAGE)

            assertThat(latest).isInstanceOf(OngoingActivityChipModel.Shown::class.java)

            // WHEN the stop action on the dialog is clicked
            val dialogStopAction =
                getStopActionFromDialog(latest, chipView, mockScreenCastDialog, kosmos)
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
    fun chip_routeStoppedFromDialog_chipImmediatelyHidden() =
        testScope.runTest {
            val latest by collectLastValue(underTest.chip)

            mediaRouterRepo.castDevices.value =
                listOf(
                    CastDevice(
                        state = CastDevice.CastState.Connected,
                        id = "id",
                        name = "name",
                        description = "desc",
                        origin = CastDevice.CastOrigin.MediaRouter,
                    )
                )

            assertThat(latest).isInstanceOf(OngoingActivityChipModel.Shown::class.java)

            // WHEN the stop action on the dialog is clicked
            val dialogStopAction =
                getStopActionFromDialog(latest, chipView, mockGenericCastDialog, kosmos)
            dialogStopAction.onClick(mock<DialogInterface>(), 0)

            // THEN the chip is immediately hidden...
            assertThat(latest).isInstanceOf(OngoingActivityChipModel.Hidden::class.java)
            // ...even though the repo still says it's projecting
            assertThat(mediaRouterRepo.castDevices.value).isNotEmpty()

            // AND we specify no animation
            assertThat((latest as OngoingActivityChipModel.Hidden).shouldAnimate).isFalse()
        }

    @Test
    fun chip_colorsAreRed() =
        testScope.runTest {
            val latest by collectLastValue(underTest.chip)

            mediaProjectionRepo.mediaProjectionState.value =
                MediaProjectionState.Projecting.EntireScreen(CAST_TO_OTHER_DEVICES_PACKAGE)

            assertThat((latest as OngoingActivityChipModel.Shown).colors).isEqualTo(ColorsModel.Red)
        }

    @Test
    fun chip_projectionIsSingleTaskState_normalPackage_isHidden() =
        testScope.runTest {
            val latest by collectLastValue(underTest.chip)

            mediaProjectionRepo.mediaProjectionState.value =
                MediaProjectionState.Projecting.SingleTask(
                    NORMAL_PACKAGE,
                    hostDeviceName = null,
                    createTask(taskId = 1),
                )

            assertThat(latest).isInstanceOf(OngoingActivityChipModel.Hidden::class.java)
        }

    @Test
    fun chip_projectionIsEntireScreenState_normalPackage_isHidden() =
        testScope.runTest {
            val latest by collectLastValue(underTest.chip)

            mediaProjectionRepo.mediaProjectionState.value =
                MediaProjectionState.Projecting.EntireScreen(NORMAL_PACKAGE)

            assertThat(latest).isInstanceOf(OngoingActivityChipModel.Hidden::class.java)
        }

    @Test
    fun chip_projectionOnly_timeResetsOnEachNewShare() =
        testScope.runTest {
            val latest by collectLastValue(underTest.chip)

            systemClock.setElapsedRealtime(1234)
            mediaProjectionRepo.mediaProjectionState.value =
                MediaProjectionState.Projecting.EntireScreen(CAST_TO_OTHER_DEVICES_PACKAGE)

            assertThat(latest).isInstanceOf(OngoingActivityChipModel.Shown::class.java)
            assertThat((latest as OngoingActivityChipModel.Shown.Timer).startTimeMs).isEqualTo(1234)

            mediaProjectionRepo.mediaProjectionState.value = MediaProjectionState.NotProjecting
            assertThat(latest).isInstanceOf(OngoingActivityChipModel.Hidden::class.java)

            systemClock.setElapsedRealtime(5678)
            mediaProjectionRepo.mediaProjectionState.value =
                MediaProjectionState.Projecting.SingleTask(
                    CAST_TO_OTHER_DEVICES_PACKAGE,
                    hostDeviceName = null,
                    createTask(taskId = 1),
                )

            assertThat(latest).isInstanceOf(OngoingActivityChipModel.Shown::class.java)
            assertThat((latest as OngoingActivityChipModel.Shown.Timer).startTimeMs).isEqualTo(5678)
        }

    @Test
    fun chip_routerInfoThenProjectionInfo_switchesToTimer() =
        testScope.runTest {
            val latest by collectLastValue(underTest.chip)

            // First, set only MediaRouter to have information and verify we just show the icon
            systemClock.setElapsedRealtime(1234)
            mediaProjectionRepo.mediaProjectionState.value = MediaProjectionState.NotProjecting
            mediaRouterRepo.castDevices.value =
                listOf(
                    CastDevice(
                        state = CastDevice.CastState.Connected,
                        id = "id",
                        name = "name",
                        description = "desc",
                        origin = CastDevice.CastOrigin.MediaRouter,
                    )
                )

            assertThat(latest).isInstanceOf(OngoingActivityChipModel.Shown.IconOnly::class.java)

            // Later, set MediaProjection to also have information
            systemClock.setElapsedRealtime(5678)
            mediaProjectionRepo.mediaProjectionState.value =
                MediaProjectionState.Projecting.EntireScreen(CAST_TO_OTHER_DEVICES_PACKAGE)

            // Verify the new time is used
            assertThat(latest).isInstanceOf(OngoingActivityChipModel.Shown::class.java)
            assertThat((latest as OngoingActivityChipModel.Shown.Timer).startTimeMs).isEqualTo(5678)
        }

    @Test
    fun chip_projectionStateEntireScreen_clickListenerShowsScreenCastDialog() =
        testScope.runTest {
            val latest by collectLastValue(underTest.chip)
            mediaProjectionRepo.mediaProjectionState.value =
                MediaProjectionState.Projecting.EntireScreen(CAST_TO_OTHER_DEVICES_PACKAGE)

            val clickListener = ((latest as OngoingActivityChipModel.Shown).onClickListener)
            assertThat(clickListener).isNotNull()

            clickListener!!.onClick(chipView)
            verify(kosmos.mockDialogTransitionAnimator)
                .showFromView(
                    eq(mockScreenCastDialog),
                    eq(chipBackgroundView),
                    any(),
                    anyBoolean(),
                )
        }

    @Test
    fun chip_projectionStateSingleTask_clickListenerShowsScreenCastDialog() =
        testScope.runTest {
            val latest by collectLastValue(underTest.chip)

            mediaProjectionRepo.mediaProjectionState.value =
                MediaProjectionState.Projecting.SingleTask(
                    CAST_TO_OTHER_DEVICES_PACKAGE,
                    hostDeviceName = null,
                    createTask(taskId = 1),
                )

            val clickListener = ((latest as OngoingActivityChipModel.Shown).onClickListener)
            assertThat(clickListener).isNotNull()

            clickListener!!.onClick(chipView)
            verify(kosmos.mockDialogTransitionAnimator)
                .showFromView(
                    eq(mockScreenCastDialog),
                    eq(chipBackgroundView),
                    any(),
                    anyBoolean(),
                )
        }

    @Test
    fun chip_routerStateCasting_clickListenerShowsGenericCastDialog() =
        testScope.runTest {
            val latest by collectLastValue(underTest.chip)

            mediaRouterRepo.castDevices.value =
                listOf(
                    CastDevice(
                        state = CastDevice.CastState.Connected,
                        id = "id",
                        name = "name",
                        description = "desc",
                        origin = CastDevice.CastOrigin.MediaRouter,
                    )
                )

            val clickListener = ((latest as OngoingActivityChipModel.Shown).onClickListener)
            assertThat(clickListener).isNotNull()

            clickListener!!.onClick(chipView)
            verify(kosmos.mockDialogTransitionAnimator)
                .showFromView(
                    eq(mockGenericCastDialog),
                    eq(chipBackgroundView),
                    any(),
                    anyBoolean(),
                )
        }

    @Test
    fun chip_projectionStateCasting_clickListenerHasCuj() =
        testScope.runTest {
            val latest by collectLastValue(underTest.chip)
            mediaProjectionRepo.mediaProjectionState.value =
                MediaProjectionState.Projecting.EntireScreen(CAST_TO_OTHER_DEVICES_PACKAGE)

            val clickListener = ((latest as OngoingActivityChipModel.Shown).onClickListener)
            clickListener!!.onClick(chipView)

            val cujCaptor = argumentCaptor<DialogCuj>()
            verify(kosmos.mockDialogTransitionAnimator)
                .showFromView(
                    any(),
                    any(),
                    cujCaptor.capture(),
                    anyBoolean(),
                )

            assertThat(cujCaptor.firstValue.cujType)
                .isEqualTo(Cuj.CUJ_STATUS_BAR_LAUNCH_DIALOG_FROM_CHIP)
            assertThat(cujCaptor.firstValue.tag).contains("Cast")
        }

    @Test
    fun chip_routerStateCasting_clickListenerHasCuj() =
        testScope.runTest {
            val latest by collectLastValue(underTest.chip)

            mediaRouterRepo.castDevices.value =
                listOf(
                    CastDevice(
                        state = CastDevice.CastState.Connected,
                        id = "id",
                        name = "name",
                        description = "desc",
                        origin = CastDevice.CastOrigin.MediaRouter,
                    )
                )

            val clickListener = ((latest as OngoingActivityChipModel.Shown).onClickListener)
            clickListener!!.onClick(chipView)

            val cujCaptor = argumentCaptor<DialogCuj>()
            verify(kosmos.mockDialogTransitionAnimator)
                .showFromView(
                    any(),
                    any(),
                    cujCaptor.capture(),
                    anyBoolean(),
                )

            assertThat(cujCaptor.firstValue.cujType)
                .isEqualTo(Cuj.CUJ_STATUS_BAR_LAUNCH_DIALOG_FROM_CHIP)
            assertThat(cujCaptor.firstValue.tag).contains("Cast")
        }
}
