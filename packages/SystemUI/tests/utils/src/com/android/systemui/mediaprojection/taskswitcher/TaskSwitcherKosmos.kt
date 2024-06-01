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

package com.android.systemui.mediaprojection.taskswitcher

import com.android.systemui.kosmos.Kosmos
import com.android.systemui.kosmos.applicationCoroutineScope
import com.android.systemui.kosmos.testDispatcher
import com.android.systemui.mediaprojection.data.repository.realMediaProjectionRepository
import com.android.systemui.mediaprojection.taskswitcher.data.repository.ActivityTaskManagerTasksRepository
import com.android.systemui.mediaprojection.taskswitcher.domain.interactor.TaskSwitchInteractor
import com.android.systemui.mediaprojection.taskswitcher.ui.viewmodel.TaskSwitcherNotificationViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher

val Kosmos.fakeActivityTaskManager by Kosmos.Fixture { FakeActivityTaskManager() }

val Kosmos.fakeMediaProjectionManager by Kosmos.Fixture { FakeMediaProjectionManager() }

val Kosmos.activityTaskManagerTasksRepository by
    Kosmos.Fixture {
        ActivityTaskManagerTasksRepository(
            activityTaskManager = fakeActivityTaskManager.activityTaskManager,
            applicationScope = applicationCoroutineScope,
            backgroundDispatcher = testDispatcher
        )
    }

val Kosmos.taskSwitcherInteractor by
    Kosmos.Fixture {
        TaskSwitchInteractor(realMediaProjectionRepository, activityTaskManagerTasksRepository)
    }

val Kosmos.taskSwitcherViewModel by
    Kosmos.Fixture { TaskSwitcherNotificationViewModel(taskSwitcherInteractor, testDispatcher) }

@OptIn(ExperimentalCoroutinesApi::class)
fun taskSwitcherKosmos() = Kosmos().apply { testDispatcher = UnconfinedTestDispatcher() }
