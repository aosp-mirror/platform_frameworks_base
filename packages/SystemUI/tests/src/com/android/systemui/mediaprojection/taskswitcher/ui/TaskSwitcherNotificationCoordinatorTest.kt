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
import android.os.Handler
import android.testing.AndroidTestingRunner
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.mediaprojection.taskswitcher.data.repository.ActivityTaskManagerTasksRepository
import com.android.systemui.mediaprojection.taskswitcher.data.repository.FakeActivityTaskManager
import com.android.systemui.mediaprojection.taskswitcher.data.repository.FakeActivityTaskManager.Companion.createTask
import com.android.systemui.mediaprojection.taskswitcher.data.repository.FakeMediaProjectionManager
import com.android.systemui.mediaprojection.taskswitcher.data.repository.MediaProjectionManagerRepository
import com.android.systemui.mediaprojection.taskswitcher.domain.interactor.TaskSwitchInteractor
import com.android.systemui.mediaprojection.taskswitcher.ui.viewmodel.TaskSwitcherNotificationViewModel
import com.android.systemui.res.R
import com.android.systemui.util.mockito.any
import com.android.systemui.util.mockito.argumentCaptor
import com.android.systemui.util.mockito.mock
import com.google.common.truth.Truth.assertThat
import junit.framework.Assert.assertEquals
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.Mockito.never
import org.mockito.Mockito.verify

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidTestingRunner::class)
@SmallTest
class TaskSwitcherNotificationCoordinatorTest : SysuiTestCase() {

    private val notificationManager = mock<NotificationManager>()

    private val dispatcher = UnconfinedTestDispatcher()
    private val testScope = TestScope(dispatcher)

    private val fakeActivityTaskManager = FakeActivityTaskManager()
    private val fakeMediaProjectionManager = FakeMediaProjectionManager()

    private val tasksRepo =
        ActivityTaskManagerTasksRepository(
            activityTaskManager = fakeActivityTaskManager.activityTaskManager,
            applicationScope = testScope.backgroundScope,
            backgroundDispatcher = dispatcher
        )

    private val mediaRepo =
        MediaProjectionManagerRepository(
            mediaProjectionManager = fakeMediaProjectionManager.mediaProjectionManager,
            handler = Handler.getMain(),
            applicationScope = testScope.backgroundScope,
            tasksRepository = tasksRepo,
            backgroundDispatcher = dispatcher,
            mediaProjectionServiceHelper = fakeMediaProjectionManager.helper,
        )

    private val interactor = TaskSwitchInteractor(mediaRepo, tasksRepo)
    private val viewModel =
        TaskSwitcherNotificationViewModel(interactor, backgroundDispatcher = dispatcher)

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
            fakeMediaProjectionManager.dispatchOnStop()

            verify(notificationManager).cancel(any(), any())
        }
    }

    @Test
    fun notificationIdIsConsistent() {
        testScope.runTest {
            fakeMediaProjectionManager.dispatchOnStop()
            val idCancel = argumentCaptor<Int>()
            verify(notificationManager).cancel(any(), idCancel.capture())

            switchTask()
            val idNotify = argumentCaptor<Int>()
            verify(notificationManager).notify(any(), idNotify.capture(), any())

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
