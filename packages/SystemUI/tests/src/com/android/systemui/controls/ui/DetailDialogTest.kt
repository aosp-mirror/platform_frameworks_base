/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.systemui.controls.ui

import android.app.PendingIntent
import android.testing.AndroidTestingRunner
import android.testing.TestableLooper
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.broadcast.BroadcastSender
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.statusbar.policy.KeyguardStateController
import com.android.wm.shell.TaskView
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mock
import org.mockito.Mockito.any
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations

@SmallTest
@RunWith(AndroidTestingRunner::class)
@TestableLooper.RunWithLooper
class DetailDialogTest : SysuiTestCase() {

    @Mock
    private lateinit var taskView: TaskView
    @Mock
    private lateinit var broadcastSender: BroadcastSender
    @Mock
    private lateinit var controlViewHolder: ControlViewHolder
    @Mock
    private lateinit var pendingIntent: PendingIntent
    @Mock
    private lateinit var keyguardStateController: KeyguardStateController
    @Mock
    private lateinit var activityStarter: ActivityStarter

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
    }

    @Test
    fun testPendingIntentIsUnModified() {
        // GIVEN the dialog is created with a PendingIntent
        val dialog = createDialog(pendingIntent)

        // WHEN the TaskView is initialized
        dialog.stateCallback.onInitialized()

        // THEN the PendingIntent used to call startActivity is unmodified by systemui
        verify(taskView).startActivity(eq(pendingIntent), any(), any(), any())
    }

    private fun createDialog(pendingIntent: PendingIntent): DetailDialog {
        return DetailDialog(
            mContext,
            broadcastSender,
            taskView,
            pendingIntent,
            controlViewHolder,
            keyguardStateController,
            activityStarter
        )
    }
}
