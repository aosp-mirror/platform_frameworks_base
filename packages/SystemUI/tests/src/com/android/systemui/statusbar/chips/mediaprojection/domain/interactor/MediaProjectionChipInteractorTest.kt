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
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.kosmos.Kosmos
import com.android.systemui.kosmos.testCase
import com.android.systemui.kosmos.testScope
import com.android.systemui.mediaprojection.data.model.MediaProjectionState
import com.android.systemui.mediaprojection.data.repository.fakeMediaProjectionRepository
import com.android.systemui.mediaprojection.taskswitcher.FakeActivityTaskManager.Companion.createTask
import com.android.systemui.statusbar.chips.mediaprojection.domain.model.ProjectionChipModel
import com.google.common.truth.Truth.assertThat
import kotlin.test.Test
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.Mockito.doAnswer
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

@SmallTest
class MediaProjectionChipInteractorTest : SysuiTestCase() {
    private val kosmos = Kosmos().also { it.testCase = this }
    private val testScope = kosmos.testScope
    private val mediaProjectionRepo = kosmos.fakeMediaProjectionRepository

    @Before
    fun setUp() {
        setUpPackageManagerForMediaProjection(kosmos)
    }

    private val underTest = kosmos.mediaProjectionChipInteractor

    @Test
    fun projection_notProjectingState_isNotProjecting() =
        testScope.runTest {
            val latest by collectLastValue(underTest.projection)

            mediaProjectionRepo.mediaProjectionState.value = MediaProjectionState.NotProjecting

            assertThat(latest).isInstanceOf(ProjectionChipModel.NotProjecting::class.java)
        }

    @Test
    fun projection_singleTaskState_otherDevicesPackage_isCastToOtherDeviceType() =
        testScope.runTest {
            val latest by collectLastValue(underTest.projection)

            mediaProjectionRepo.mediaProjectionState.value =
                MediaProjectionState.Projecting.SingleTask(
                    CAST_TO_OTHER_DEVICES_PACKAGE,
                    createTask(taskId = 1)
                )

            assertThat(latest).isInstanceOf(ProjectionChipModel.Projecting::class.java)
            assertThat((latest as ProjectionChipModel.Projecting).type)
                .isEqualTo(ProjectionChipModel.Type.CAST_TO_OTHER_DEVICE)
        }

    @Test
    fun projection_entireScreenState_otherDevicesPackage_isCastToOtherDeviceChipType() =
        testScope.runTest {
            val latest by collectLastValue(underTest.projection)

            mediaProjectionRepo.mediaProjectionState.value =
                MediaProjectionState.Projecting.EntireScreen(
                    CAST_TO_OTHER_DEVICES_PACKAGE,
                )

            assertThat(latest).isInstanceOf(ProjectionChipModel.Projecting::class.java)
            assertThat((latest as ProjectionChipModel.Projecting).type)
                .isEqualTo(ProjectionChipModel.Type.CAST_TO_OTHER_DEVICE)
        }

    @Test
    fun projection_singleTaskState_normalPackage_isShareToAppChipType() =
        testScope.runTest {
            val latest by collectLastValue(underTest.projection)

            mediaProjectionRepo.mediaProjectionState.value =
                MediaProjectionState.Projecting.SingleTask(NORMAL_PACKAGE, createTask(taskId = 1))

            assertThat(latest).isInstanceOf(ProjectionChipModel.Projecting::class.java)
            assertThat((latest as ProjectionChipModel.Projecting).type)
                .isEqualTo(ProjectionChipModel.Type.SHARE_TO_APP)
        }

    @Test
    fun projection_entireScreenState_normalPackage_isShareToAppChipType() =
        testScope.runTest {
            val latest by collectLastValue(underTest.projection)

            mediaProjectionRepo.mediaProjectionState.value =
                MediaProjectionState.Projecting.EntireScreen(NORMAL_PACKAGE)

            assertThat(latest).isInstanceOf(ProjectionChipModel.Projecting::class.java)
            assertThat((latest as ProjectionChipModel.Projecting).type)
                .isEqualTo(ProjectionChipModel.Type.SHARE_TO_APP)
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
