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

import android.app.ActivityOptions
import android.app.PendingIntent
import android.content.Context
import android.testing.TestableLooper
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.activity.EmptyTestActivity
import com.android.systemui.broadcast.BroadcastSender
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.statusbar.policy.KeyguardStateController
import com.android.systemui.util.mockito.argumentCaptor
import com.android.systemui.util.mockito.capture
import com.android.wm.shell.taskview.TaskView
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mock
import org.mockito.Mockito.any
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations

@SmallTest
@RunWith(AndroidJUnit4::class)
@TestableLooper.RunWithLooper
class DetailDialogTest : SysuiTestCase() {

    @Rule
    @JvmField
    val activityRule: ActivityScenarioRule<EmptyTestActivity> =
        ActivityScenarioRule(EmptyTestActivity::class.java)

    @Mock private lateinit var taskView: TaskView
    @Mock private lateinit var broadcastSender: BroadcastSender
    @Mock private lateinit var controlViewHolder: ControlViewHolder
    @Mock private lateinit var pendingIntent: PendingIntent
    @Mock private lateinit var keyguardStateController: KeyguardStateController
    @Mock private lateinit var activityStarter: ActivityStarter

    private lateinit var underTest: DetailDialog

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)

        underTest = createDialog(pendingIntent)
    }

    @Test
    fun testPendingIntentIsUnModified() {
        // WHEN the TaskView is initialized
        underTest.stateCallback.onInitialized()

        // THEN the PendingIntent used to call startActivity is unmodified by systemui
        verify(taskView).startActivity(eq(pendingIntent), any(), any(), any())
    }

    @Test
    fun testActivityOptionsAllowBal() {
        // WHEN the TaskView is initialized
        underTest.stateCallback.onInitialized()

        val optionsCaptor = argumentCaptor<ActivityOptions>()

        // THEN the ActivityOptions have the correct flags
        verify(taskView).startActivity(any(), any(), capture(optionsCaptor), any())

        assertThat(optionsCaptor.value.pendingIntentBackgroundActivityStartMode)
            .isEqualTo(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
        assertThat(optionsCaptor.value.isPendingIntentBackgroundActivityLaunchAllowedByPermission)
            .isTrue()
        assertThat(optionsCaptor.value.taskAlwaysOnTop).isTrue()
    }

    @Test
    fun testDismissRemovesTheTask() {
        activityRule.scenario.onActivity {
            underTest = createDialog(pendingIntent, it)
            underTest.show()

            underTest.dismiss()

            verify(taskView).removeTask()
            verify(taskView, never()).release()
        }
    }

    @Test
    fun testTaskRemovalReleasesTaskView() {
        underTest.stateCallback.onTaskRemovalStarted(0)

        verify(taskView).release()
    }

    private fun createDialog(
        pendingIntent: PendingIntent,
        context: Context = mContext,
    ): DetailDialog {
        return DetailDialog(
            context,
            broadcastSender,
            taskView,
            pendingIntent,
            controlViewHolder,
            keyguardStateController,
            activityStarter,
        )
    }
}
