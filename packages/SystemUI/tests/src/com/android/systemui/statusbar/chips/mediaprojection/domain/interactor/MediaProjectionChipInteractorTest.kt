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

package com.android.systemui.statusbar.chips.mediaprojection.domain.interactor

import android.Manifest
import android.content.Intent
import android.content.packageManager
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.view.View
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.animation.mockDialogTransitionAnimator
import com.android.systemui.common.shared.model.Icon
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.kosmos.Kosmos
import com.android.systemui.kosmos.testCase
import com.android.systemui.kosmos.testScope
import com.android.systemui.mediaprojection.data.model.MediaProjectionState
import com.android.systemui.mediaprojection.data.repository.fakeMediaProjectionRepository
import com.android.systemui.mediaprojection.taskswitcher.FakeActivityTaskManager.Companion.createTask
import com.android.systemui.res.R
import com.android.systemui.statusbar.chips.domain.model.OngoingActivityChipModel
import com.android.systemui.statusbar.chips.mediaprojection.ui.view.EndCastToOtherDeviceDialogDelegate
import com.android.systemui.statusbar.chips.mediaprojection.ui.view.EndShareToAppDialogDelegate
import com.android.systemui.statusbar.chips.ui.view.ChipBackgroundContainer
import com.android.systemui.statusbar.phone.SystemUIDialog
import com.android.systemui.statusbar.phone.mockSystemUIDialogFactory
import com.android.systemui.util.time.fakeSystemClock
import com.google.common.truth.Truth.assertThat
import kotlin.test.Test
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.mockito.ArgumentMatchers.anyBoolean
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.Mockito.doAnswer
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

@SmallTest
class MediaProjectionChipInteractorTest : SysuiTestCase() {
    private val kosmos = Kosmos().also { it.testCase = this }
    private val testScope = kosmos.testScope
    private val mediaProjectionRepo = kosmos.fakeMediaProjectionRepository
    private val systemClock = kosmos.fakeSystemClock

    private val mockCastDialog = mock<SystemUIDialog>()
    private val mockShareDialog = mock<SystemUIDialog>()

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

    @Before
    fun setUp() {
        setUpPackageManagerForMediaProjection(kosmos)

        whenever(kosmos.mockSystemUIDialogFactory.create(any<EndCastToOtherDeviceDialogDelegate>()))
            .thenReturn(mockCastDialog)
        whenever(kosmos.mockSystemUIDialogFactory.create(any<EndShareToAppDialogDelegate>()))
            .thenReturn(mockShareDialog)
    }

    private val underTest = kosmos.mediaProjectionChipInteractor

    @Test
    fun chip_notProjectingState_isHidden() =
        testScope.runTest {
            val latest by collectLastValue(underTest.chip)

            mediaProjectionRepo.mediaProjectionState.value = MediaProjectionState.NotProjecting

            assertThat(latest).isInstanceOf(OngoingActivityChipModel.Hidden::class.java)
        }

    @Test
    fun chip_singleTaskState_otherDevicesPackage_castToOtherDeviceChipShown() =
        testScope.runTest {
            val latest by collectLastValue(underTest.chip)

            mediaProjectionRepo.mediaProjectionState.value =
                MediaProjectionState.Projecting.SingleTask(
                    CAST_TO_OTHER_DEVICES_PACKAGE,
                    createTask(taskId = 1)
                )

            assertThat(latest).isInstanceOf(OngoingActivityChipModel.Shown::class.java)
            val icon = (latest as OngoingActivityChipModel.Shown).icon
            assertThat((icon as Icon.Resource).res).isEqualTo(R.drawable.ic_cast_connected)
        }

    @Test
    fun chip_entireScreenState_otherDevicesPackage_castToOtherDeviceChipShown() =
        testScope.runTest {
            val latest by collectLastValue(underTest.chip)

            mediaProjectionRepo.mediaProjectionState.value =
                MediaProjectionState.Projecting.EntireScreen(CAST_TO_OTHER_DEVICES_PACKAGE)

            assertThat(latest).isInstanceOf(OngoingActivityChipModel.Shown::class.java)
            val icon = (latest as OngoingActivityChipModel.Shown).icon
            assertThat((icon as Icon.Resource).res).isEqualTo(R.drawable.ic_cast_connected)
        }

    @Test
    fun chip_singleTaskState_normalPackage_shareToAppChipShown() =
        testScope.runTest {
            val latest by collectLastValue(underTest.chip)

            mediaProjectionRepo.mediaProjectionState.value =
                MediaProjectionState.Projecting.SingleTask(NORMAL_PACKAGE, createTask(taskId = 1))

            assertThat(latest).isInstanceOf(OngoingActivityChipModel.Shown::class.java)
            val icon = (latest as OngoingActivityChipModel.Shown).icon
            assertThat((icon as Icon.Resource).res).isEqualTo(R.drawable.ic_screenshot_share)
        }

    @Test
    fun chip_entireScreenState_normalPackage_shareToAppChipShown() =
        testScope.runTest {
            val latest by collectLastValue(underTest.chip)

            mediaProjectionRepo.mediaProjectionState.value =
                MediaProjectionState.Projecting.EntireScreen(NORMAL_PACKAGE)

            assertThat(latest).isInstanceOf(OngoingActivityChipModel.Shown::class.java)
            val icon = (latest as OngoingActivityChipModel.Shown).icon
            assertThat((icon as Icon.Resource).res).isEqualTo(R.drawable.ic_screenshot_share)
        }

    @Test
    fun chip_timeResetsOnEachNewShare() =
        testScope.runTest {
            val latest by collectLastValue(underTest.chip)

            systemClock.setElapsedRealtime(1234)
            mediaProjectionRepo.mediaProjectionState.value =
                MediaProjectionState.Projecting.EntireScreen(NORMAL_PACKAGE)

            assertThat(latest).isInstanceOf(OngoingActivityChipModel.Shown::class.java)
            assertThat((latest as OngoingActivityChipModel.Shown).startTimeMs).isEqualTo(1234)

            mediaProjectionRepo.mediaProjectionState.value = MediaProjectionState.NotProjecting
            assertThat(latest).isInstanceOf(OngoingActivityChipModel.Hidden::class.java)

            systemClock.setElapsedRealtime(5678)
            mediaProjectionRepo.mediaProjectionState.value =
                MediaProjectionState.Projecting.SingleTask(
                    CAST_TO_OTHER_DEVICES_PACKAGE,
                    createTask(taskId = 1)
                )

            assertThat(latest).isInstanceOf(OngoingActivityChipModel.Shown::class.java)
            assertThat((latest as OngoingActivityChipModel.Shown).startTimeMs).isEqualTo(5678)
        }

    @Test
    fun chip_castToOtherDevice_entireScreen_clickListenerShowsCastDialog() =
        testScope.runTest {
            val latest by collectLastValue(underTest.chip)
            mediaProjectionRepo.mediaProjectionState.value =
                MediaProjectionState.Projecting.EntireScreen(CAST_TO_OTHER_DEVICES_PACKAGE)

            val clickListener = ((latest as OngoingActivityChipModel.Shown).onClickListener)

            // Dialogs must be created on the main thread
            context.mainExecutor.execute {
                clickListener.onClick(chipView)
                verify(kosmos.mockDialogTransitionAnimator)
                    .showFromView(
                        eq(mockCastDialog),
                        eq(chipBackgroundView),
                        eq(null),
                        anyBoolean(),
                    )
            }
        }

    @Test
    fun chip_castToOtherDevice_singleTask_clickListenerShowsCastDialog() =
        testScope.runTest {
            val latest by collectLastValue(underTest.chip)

            mediaProjectionRepo.mediaProjectionState.value =
                MediaProjectionState.Projecting.SingleTask(
                    CAST_TO_OTHER_DEVICES_PACKAGE,
                    createTask(taskId = 1)
                )

            val clickListener = ((latest as OngoingActivityChipModel.Shown).onClickListener)

            // Dialogs must be created on the main thread
            context.mainExecutor.execute {
                clickListener.onClick(chipView)
                verify(kosmos.mockDialogTransitionAnimator)
                    .showFromView(
                        eq(mockCastDialog),
                        eq(chipBackgroundView),
                        eq(null),
                        anyBoolean(),
                    )
            }
        }

    @Test
    fun chip_shareToApp_entireScreen_clickListenerShowsShareDialog() =
        testScope.runTest {
            val latest by collectLastValue(underTest.chip)
            mediaProjectionRepo.mediaProjectionState.value =
                MediaProjectionState.Projecting.EntireScreen(NORMAL_PACKAGE)

            val clickListener = ((latest as OngoingActivityChipModel.Shown).onClickListener)

            // Dialogs must be created on the main thread
            context.mainExecutor.execute {
                clickListener.onClick(chipView)
                verify(kosmos.mockDialogTransitionAnimator)
                    .showFromView(
                        eq(mockShareDialog),
                        eq(chipBackgroundView),
                        eq(null),
                        anyBoolean(),
                    )
            }
        }

    @Test
    fun chip_shareToApp_singleTask_clickListenerShowsShareDialog() =
        testScope.runTest {
            val latest by collectLastValue(underTest.chip)
            mediaProjectionRepo.mediaProjectionState.value =
                MediaProjectionState.Projecting.SingleTask(NORMAL_PACKAGE, createTask(taskId = 1))

            val clickListener = ((latest as OngoingActivityChipModel.Shown).onClickListener)

            // Dialogs must be created on the main thread
            context.mainExecutor.execute {
                clickListener.onClick(chipView)
                verify(kosmos.mockDialogTransitionAnimator)
                    .showFromView(
                        eq(mockShareDialog),
                        eq(chipBackgroundView),
                        eq(null),
                        anyBoolean(),
                    )
            }
        }

    companion object {
        const val CAST_TO_OTHER_DEVICES_PACKAGE = "other.devices.package"
        const val NORMAL_PACKAGE = "some.normal.package"

        /**
         * Sets up [kosmos.packageManager] so that [CAST_TO_OTHER_DEVICES_PACKAGE] is marked as a
         * package that casts to other devices, and [NORMAL_PACKAGE] is *not* marked as casting to
         * other devices.
         */
        fun setUpPackageManagerForMediaProjection(kosmos: Kosmos) {
            kosmos.packageManager.apply {
                whenever(
                        this.checkPermission(
                            Manifest.permission.REMOTE_DISPLAY_PROVIDER,
                            CAST_TO_OTHER_DEVICES_PACKAGE
                        )
                    )
                    .thenReturn(PackageManager.PERMISSION_GRANTED)
                whenever(
                        this.checkPermission(
                            Manifest.permission.REMOTE_DISPLAY_PROVIDER,
                            NORMAL_PACKAGE
                        )
                    )
                    .thenReturn(PackageManager.PERMISSION_DENIED)

                doAnswer {
                        // See Utils.isHeadlessRemoteDisplayProvider
                        if (
                            (it.arguments[0] as Intent).`package` == CAST_TO_OTHER_DEVICES_PACKAGE
                        ) {
                            emptyList()
                        } else {
                            listOf(mock<ResolveInfo>())
                        }
                    }
                    .whenever(this)
                    .queryIntentActivities(any(), anyInt())
            }
        }
    }
}
