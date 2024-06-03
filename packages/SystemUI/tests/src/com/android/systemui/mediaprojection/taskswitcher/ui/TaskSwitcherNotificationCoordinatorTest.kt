/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.systemui.mediaprojection.taskswitcher.ui

import android.app.Notification
import android.app.NotificationManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.kosmos.testScope
import com.android.systemui.mediaprojection.taskswitcher.FakeActivityTaskManager.Companion.createTask
import com.android.systemui.mediaprojection.taskswitcher.FakeMediaProjectionManager
import com.android.systemui.mediaprojection.taskswitcher.fakeActivityTaskManager
import com.android.systemui.mediaprojection.taskswitcher.fakeMediaProjectionManager
import com.android.systemui.mediaprojection.taskswitcher.taskSwitcherKosmos
import com.android.systemui.mediaprojection.taskswitcher.taskSwitcherViewModel
import com.android.systemui.res.R
import com.android.systemui.util.mockito.any
import com.android.systemui.util.mockito.argumentCaptor
import com.android.systemui.util.mockito.mock
import com.google.common.truth.Truth.assertThat
import junit.framework.Assert.assertEquals
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.Mockito.never
import org.mockito.Mockito.reset
import org.mockito.Mockito.verify

@RunWith(AndroidJUnit4::class)
@SmallTest
class TaskSwitcherNotificationCoordinatorTest : SysuiTestCase() {

    private val notificationManager = mock<NotificationManager>()
    private val kosmos = taskSwitcherKosmos()
    private val testScope = kosmos.testScope
    private val fakeActivityTaskManager = kosmos.fakeActivityTaskManager
    private val fakeMediaProjectionManager = kosmos.fakeMediaProjectionManager
    private val viewModel = kosmos.taskSwitcherViewModel

    private lateinit var coordinator: TaskSwitcherNotificationCoordinator

    @Before
    fun setup() {
        coordinator =
            TaskSwitcherNotificationCoordinator(
                context,
                notificationManager,
                testScope.backgroundScope,
                viewModel,
                fakeBroadcastDispatcher,
            )
        coordinator.start()
        // When the coordinator starts up, the view model will immediately emit a NotShowing event
        // and hide the notification. That's fine, but we should reset the notification manager so
        // that the initial emission isn't part of the tests.
        reset(notificationManager)
    }

    @Test
    fun showNotification() {
        testScope.runTest {
            switchTask()

            val notification = ArgumentCaptor.forClass(Notification::class.java)
            verify(notificationManager).notify(any(), any(), notification.capture())
            assertNotification(notification)
        }
    }

    @Test
    fun hideNotification() {
        testScope.runTest {
            // First, show a notification
            switchTask()

            // WHEN the projection is stopped
            fakeMediaProjectionManager.dispatchOnStop()

            // THEN the notification is hidden
            verify(notificationManager).cancel(any(), any())
        }
    }

    @Test
    fun notificationIdIsConsistent() {
        testScope.runTest {
            // First, show a notification
            switchTask()
            val idNotify = argumentCaptor<Int>()
            verify(notificationManager).notify(any(), idNotify.capture(), any())

            // Then, hide the notification
            fakeMediaProjectionManager.dispatchOnStop()
            val idCancel = argumentCaptor<Int>()
            verify(notificationManager).cancel(any(), idCancel.capture())

            assertEquals(idCancel.value, idNotify.value)
        }
    }

    @Test
    fun switchTaskAction_hidesNotification() =
        testScope.runTest {
            switchTask()
            val notification = argumentCaptor<Notification>()
            verify(notificationManager).notify(any(), any(), notification.capture())
            verify(notificationManager, never()).cancel(any(), any())

            val action = findSwitchAction(notification.value)
            fakeBroadcastDispatcher.sendIntentToMatchingReceiversOnly(
                context,
                action.actionIntent.intent
            )

            verify(notificationManager).cancel(any(), any())
        }

    @Test
    fun goBackAction_hidesNotification() =
        testScope.runTest {
            switchTask()
            val notification = argumentCaptor<Notification>()
            verify(notificationManager).notify(any(), any(), notification.capture())
            verify(notificationManager, never()).cancel(any(), any())

            val action = findGoBackAction(notification.value)
            fakeBroadcastDispatcher.sendIntentToMatchingReceiversOnly(
                context,
                action.actionIntent.intent
            )

            verify(notificationManager).cancel(any(), any())
        }

    private fun findSwitchAction(notification: Notification): Notification.Action {
        return notification.actions.first {
            it.title == context.getString(R.string.media_projection_task_switcher_action_switch)
        }
    }

    private fun findGoBackAction(notification: Notification): Notification.Action {
        return notification.actions.first {
            it.title == context.getString(R.string.media_projection_task_switcher_action_back)
        }
    }

    private fun switchTask() {
        val projectedTask = createTask(taskId = 1)
        val foregroundTask = createTask(taskId = 2)
        fakeActivityTaskManager.addRunningTasks(projectedTask, foregroundTask)
        fakeMediaProjectionManager.dispatchOnSessionSet(
            session =
                FakeMediaProjectionManager.createSingleTaskSession(projectedTask.token.asBinder())
        )
        fakeActivityTaskManager.moveTaskToForeground(foregroundTask)
    }

    private fun assertNotification(notification: ArgumentCaptor<Notification>) {
        val text = notification.value.extras.getCharSequence(Notification.EXTRA_TEXT)
        assertEquals(context.getString(R.string.media_projection_task_switcher_text), text)

        val actions = notification.value.actions
        assertThat(actions).hasLength(2)
    }
}
