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

@file:OptIn(ExperimentalCoroutinesApi::class)

package com.android.systemui.statusbar.policy.ui.dialog

import android.app.Dialog
import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.animation.ActivityTransitionAnimator
import com.android.systemui.animation.mockActivityTransitionAnimatorController
import com.android.systemui.animation.mockDialogTransitionAnimator
import com.android.systemui.kosmos.applicationCoroutineScope
import com.android.systemui.kosmos.mainCoroutineContext
import com.android.systemui.kosmos.testScope
import com.android.systemui.plugins.activityStarter
import com.android.systemui.runOnMainThreadAndWaitForIdleSync
import com.android.systemui.statusbar.phone.SystemUIDialog
import com.android.systemui.statusbar.phone.systemUIDialogFactory
import com.android.systemui.statusbar.policy.ui.dialog.viewmodel.modesDialogViewModel
import com.android.systemui.testKosmos
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

@SmallTest
@RunWith(AndroidJUnit4::class)
class ModesDialogDelegateTest : SysuiTestCase() {
    private val kosmos = testKosmos()
    private val testScope = kosmos.testScope
    private val activityStarter = kosmos.activityStarter
    private val mockDialogTransitionAnimator = kosmos.mockDialogTransitionAnimator
    private val mockAnimationController = kosmos.mockActivityTransitionAnimatorController
    private val mockDialogEventLogger = kosmos.mockModesDialogEventLogger
    private lateinit var underTest: ModesDialogDelegate

    @Before
    fun setup() {
        whenever(
                mockDialogTransitionAnimator.createActivityTransitionController(
                    any<SystemUIDialog>(),
                    eq(null)
                )
            )
            .thenReturn(mockAnimationController)

        underTest =
            ModesDialogDelegate(
                kosmos.systemUIDialogFactory,
                mockDialogTransitionAnimator,
                activityStarter,
                { kosmos.modesDialogViewModel },
                mockDialogEventLogger,
                kosmos.mainCoroutineContext,
            )
    }

    @Test
    fun launchFromDialog_whenDialogNotOpen() {
        val intent: Intent = mock()

        runOnMainThreadAndWaitForIdleSync { underTest.launchFromDialog(intent) }

        verify(activityStarter)
            .startActivity(eq(intent), eq(true), eq<ActivityTransitionAnimator.Controller?>(null))
    }

    @Test
    fun launchFromDialog_whenDialogOpen() =
        testScope.runTest {
            val intent: Intent = mock()
            lateinit var dialog: Dialog

            runOnMainThreadAndWaitForIdleSync {
                kosmos.applicationCoroutineScope.launch { dialog = underTest.showDialog() }
                runCurrent()
                underTest.launchFromDialog(intent)
            }

            verify(mockDialogTransitionAnimator)
                .createActivityTransitionController(any<Dialog>(), eq(null))
            verify(activityStarter).startActivity(eq(intent), eq(true), eq(mockAnimationController))

            runOnMainThreadAndWaitForIdleSync { dialog.dismiss() }
        }

    @Test
    fun dismiss_clearsDialogReference() {
        val dialog = runOnMainThreadAndWaitForIdleSync { underTest.createDialog() }

        assertThat(underTest.currentDialog).isEqualTo(dialog)

        runOnMainThreadAndWaitForIdleSync {
            dialog.show()
            dialog.dismiss()
        }

        assertThat(underTest.currentDialog).isNull()
    }

    @Test
    fun openSettings_logsEvent() =
        testScope.runTest {
            val dialog: SystemUIDialog = mock()
            underTest.openSettings(dialog)
            verify(mockDialogEventLogger).logDialogSettings()
        }
}
