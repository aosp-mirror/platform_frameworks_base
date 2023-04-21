/*
 * Copyright (C) 2022 The Android Open Source Project
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
 *
 */

package com.android.systemui.controls.ui

import android.app.ActivityOptions
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.Intent.FLAG_ACTIVITY_MULTIPLE_TASK
import android.content.Intent.FLAG_ACTIVITY_NEW_TASK
import android.graphics.Rect
import android.testing.AndroidTestingRunner
import android.testing.TestableLooper
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.util.boundsOnScreen
import com.android.systemui.util.concurrency.FakeExecutor
import com.android.systemui.util.mockito.any
import com.android.systemui.util.mockito.argumentCaptor
import com.android.systemui.util.mockito.capture
import com.android.systemui.util.mockito.eq
import com.android.systemui.util.mockito.whenever
import com.android.systemui.util.time.FakeSystemClock
import com.android.wm.shell.TaskView
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.Captor
import org.mockito.Mock
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations

@SmallTest
@RunWith(AndroidTestingRunner::class)
@TestableLooper.RunWithLooper
class PanelTaskViewControllerTest : SysuiTestCase() {

    companion object {
        val FAKE_BOUNDS = Rect(10, 20, 30, 40)
    }

    @Mock private lateinit var activityContext: Context
    @Mock private lateinit var taskView: TaskView
    @Mock private lateinit var pendingIntent: PendingIntent
    @Mock private lateinit var hideRunnable: () -> Unit

    @Captor private lateinit var listenerCaptor: ArgumentCaptor<TaskView.Listener>

    private lateinit var uiExecutor: FakeExecutor
    private lateinit var underTest: PanelTaskViewController

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)

        whenever(taskView.boundsOnScreen).thenAnswer { (it.arguments[0] as Rect).set(FAKE_BOUNDS) }
        whenever(taskView.post(any())).thenAnswer {
            uiExecutor.execute(it.arguments[0] as Runnable)
            true
        }
        whenever(activityContext.resources).thenReturn(context.resources)

        uiExecutor = FakeExecutor(FakeSystemClock())

        underTest =
            PanelTaskViewController(
                activityContext,
                uiExecutor,
                pendingIntent,
                taskView,
                hideRunnable
            )
    }

    @Test
    fun testLaunchTaskViewAttachedListener() {
        underTest.launchTaskView()
        verify(taskView).setListener(eq(uiExecutor), any())
    }

    @Test
    fun testTaskViewOnInitializeStartsActivity() {
        underTest.launchTaskView()
        verify(taskView).setListener(any(), capture(listenerCaptor))

        listenerCaptor.value.onInitialized()
        uiExecutor.runAllReady()

        val intentCaptor = argumentCaptor<Intent>()
        val optionsCaptor = argumentCaptor<ActivityOptions>()

        verify(taskView)
            .startActivity(
                eq(pendingIntent),
                /* fillInIntent */ capture(intentCaptor),
                capture(optionsCaptor),
                eq(FAKE_BOUNDS)
            )

        assertThat(intentCaptor.value.flags)
            .isEqualTo(FLAG_ACTIVITY_NEW_TASK or FLAG_ACTIVITY_MULTIPLE_TASK)
        assertThat(optionsCaptor.value.taskAlwaysOnTop).isTrue()
    }

    @Test
    fun testHideRunnableCalledWhenBackOnRoot() {
        underTest.launchTaskView()
        verify(taskView).setListener(any(), capture(listenerCaptor))

        listenerCaptor.value.onBackPressedOnTaskRoot(0)

        verify(hideRunnable).invoke()
    }

    @Test
    fun testTaskViewReleasedOnDismiss() {
        underTest.dismiss()
        verify(taskView).release()
    }

    @Test
    fun testTaskViewReleasedOnBackOnRoot() {
        underTest.launchTaskView()
        verify(taskView).setListener(any(), capture(listenerCaptor))

        listenerCaptor.value.onBackPressedOnTaskRoot(0)
        verify(taskView).release()
    }

    @Test
    fun testOnTaskRemovalStarted() {
        underTest.launchTaskView()
        verify(taskView).setListener(any(), capture(listenerCaptor))

        listenerCaptor.value.onTaskRemovalStarted(0)
        verify(taskView).release()
    }

    @Test
    fun testOnRefreshBounds() {
        underTest.launchTaskView()

        underTest.refreshBounds()
        verify(taskView).onLocationChanged()
    }
}
