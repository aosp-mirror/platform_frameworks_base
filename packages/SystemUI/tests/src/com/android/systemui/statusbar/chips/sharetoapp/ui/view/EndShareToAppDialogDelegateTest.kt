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

package com.android.systemui.statusbar.chips.sharetoapp.ui.view

import android.content.ComponentName
import android.content.DialogInterface
import android.content.Intent
import android.content.applicationContext
import android.content.packageManager
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.kosmos.Kosmos
import com.android.systemui.kosmos.testCase
import com.android.systemui.kosmos.testScope
import com.android.systemui.mediaprojection.data.model.MediaProjectionState
import com.android.systemui.mediaprojection.data.repository.fakeMediaProjectionRepository
import com.android.systemui.mediaprojection.taskswitcher.FakeActivityTaskManager.Companion.createTask
import com.android.systemui.res.R
import com.android.systemui.statusbar.chips.mediaprojection.domain.interactor.mediaProjectionChipInteractor
import com.android.systemui.statusbar.chips.mediaprojection.domain.model.ProjectionChipModel
import com.android.systemui.statusbar.chips.mediaprojection.ui.view.endMediaProjectionDialogHelper
import com.android.systemui.statusbar.phone.SystemUIDialog
import com.google.common.truth.Truth.assertThat
import kotlin.test.Test
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

@SmallTest
@OptIn(ExperimentalCoroutinesApi::class)
class EndShareToAppDialogDelegateTest : SysuiTestCase() {
    private val kosmos = Kosmos().also { it.testCase = this }
    private val sysuiDialog = mock<SystemUIDialog>()
    private lateinit var underTest: EndShareToAppDialogDelegate

    @Test
    fun icon() {
        createAndSetDelegate(SINGLE_TASK)

        underTest.beforeCreate(sysuiDialog, /* savedInstanceState= */ null)

        verify(sysuiDialog).setIcon(R.drawable.ic_present_to_all)
    }

    @Test
    fun title() {
        createAndSetDelegate(ENTIRE_SCREEN)
        whenever(kosmos.packageManager.getApplicationInfo(eq(HOST_PACKAGE), any<Int>()))
            .thenThrow(PackageManager.NameNotFoundException())

        underTest.beforeCreate(sysuiDialog, /* savedInstanceState= */ null)

        verify(sysuiDialog).setTitle(R.string.share_to_app_stop_dialog_title)
    }

    @Test
    fun message_entireScreen_unknownHostPackage() {
        createAndSetDelegate(ENTIRE_SCREEN)
        whenever(kosmos.packageManager.getApplicationInfo(eq(HOST_PACKAGE), any<Int>()))
            .thenThrow(PackageManager.NameNotFoundException())

        underTest.beforeCreate(sysuiDialog, /* savedInstanceState= */ null)

        verify(sysuiDialog)
            .setMessage(context.getString(R.string.share_to_app_stop_dialog_message_entire_screen))
    }

    @Test
    fun message_entireScreen_hasHostPackage() {
        createAndSetDelegate(ENTIRE_SCREEN)
        val hostAppInfo = mock<ApplicationInfo>()
        whenever(hostAppInfo.loadLabel(kosmos.packageManager)).thenReturn("Host Package")
        whenever(kosmos.packageManager.getApplicationInfo(eq(HOST_PACKAGE), any<Int>()))
            .thenReturn(hostAppInfo)

        underTest.beforeCreate(sysuiDialog, /* savedInstanceState= */ null)

        verify(sysuiDialog)
            .setMessage(
                context.getString(
                    R.string.share_to_app_stop_dialog_message_entire_screen_with_host_app,
                    "Host Package",
                )
            )
    }

    @Test
    fun message_singleTask_unknownAppName() {
        val baseIntent =
            Intent().apply { this.component = ComponentName("fake.task.package", "cls") }
        whenever(kosmos.packageManager.getApplicationInfo(eq("fake.task.package"), any<Int>()))
            .thenThrow(PackageManager.NameNotFoundException())

        createAndSetDelegate(
            MediaProjectionState.Projecting.SingleTask(
                HOST_PACKAGE,
                hostDeviceName = null,
                createTask(taskId = 1, baseIntent = baseIntent)
            )
        )

        underTest.beforeCreate(sysuiDialog, /* savedInstanceState= */ null)

        verify(sysuiDialog)
            .setMessage(
                context.getString(R.string.share_to_app_stop_dialog_message_single_app_generic)
            )
    }

    @Test
    fun message_singleTask_hasAppName() {
        val baseIntent =
            Intent().apply { this.component = ComponentName("fake.task.package", "cls") }
        val appInfo = mock<ApplicationInfo>()
        whenever(appInfo.loadLabel(kosmos.packageManager)).thenReturn("Fake Package")
        whenever(kosmos.packageManager.getApplicationInfo(eq("fake.task.package"), any<Int>()))
            .thenReturn(appInfo)

        createAndSetDelegate(
            MediaProjectionState.Projecting.SingleTask(
                HOST_PACKAGE,
                hostDeviceName = null,
                createTask(taskId = 1, baseIntent = baseIntent)
            )
        )

        underTest.beforeCreate(sysuiDialog, /* savedInstanceState= */ null)

        verify(sysuiDialog)
            .setMessage(
                context.getString(
                    R.string.share_to_app_stop_dialog_message_single_app_specific,
                    "Fake Package",
                )
            )
    }

    @Test
    fun negativeButton() {
        createAndSetDelegate(SINGLE_TASK)

        underTest.beforeCreate(sysuiDialog, /* savedInstanceState= */ null)

        verify(sysuiDialog).setNegativeButton(R.string.close_dialog_button, null)
    }

    @Test
    fun positiveButton() =
        kosmos.testScope.runTest {
            createAndSetDelegate(ENTIRE_SCREEN)
            whenever(kosmos.packageManager.getApplicationInfo(eq(HOST_PACKAGE), any<Int>()))
                .thenThrow(PackageManager.NameNotFoundException())

            underTest.beforeCreate(sysuiDialog, /* savedInstanceState= */ null)

            val clickListener = argumentCaptor<DialogInterface.OnClickListener>()

            // Verify the button has the right text
            verify(sysuiDialog)
                .setPositiveButton(
                    eq(R.string.share_to_app_stop_dialog_button),
                    clickListener.capture()
                )

            // Verify that clicking the button stops the recording
            assertThat(kosmos.fakeMediaProjectionRepository.stopProjectingInvoked).isFalse()

            clickListener.firstValue.onClick(mock<DialogInterface>(), 0)
            runCurrent()

            assertThat(kosmos.fakeMediaProjectionRepository.stopProjectingInvoked).isTrue()
        }

    private fun createAndSetDelegate(state: MediaProjectionState.Projecting) {
        underTest =
            EndShareToAppDialogDelegate(
                kosmos.endMediaProjectionDialogHelper,
                kosmos.applicationContext,
                stopAction = kosmos.mediaProjectionChipInteractor::stopProjecting,
                ProjectionChipModel.Projecting(
                    ProjectionChipModel.Type.SHARE_TO_APP,
                    state,
                ),
            )
    }

    companion object {
        private const val HOST_PACKAGE = "fake.host.package"
        private val ENTIRE_SCREEN = MediaProjectionState.Projecting.EntireScreen(HOST_PACKAGE)
        private val SINGLE_TASK =
            MediaProjectionState.Projecting.SingleTask(
                HOST_PACKAGE,
                hostDeviceName = null,
                createTask(taskId = 1)
            )
    }
}
