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

package com.android.wm.shell.desktopmode.persistence

import android.content.Context
import android.window.DesktopModeFlags
import com.android.wm.shell.desktopmode.DesktopRepository
import com.android.wm.shell.desktopmode.DesktopUserRepositories
import com.android.wm.shell.shared.annotations.ShellMainThread
import com.android.wm.shell.shared.desktopmode.DesktopModeStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Initializes the [DesktopRepository] from the [DesktopPersistentRepository].
 *
 * This class is responsible for reading the [DesktopPersistentRepository] and initializing the
 * [DesktopRepository] with the tasks that previously existed in desktop.
 */
class DesktopRepositoryInitializerImpl(
    private val context: Context,
    private val persistentRepository: DesktopPersistentRepository,
    @ShellMainThread private val mainCoroutineScope: CoroutineScope,
) : DesktopRepositoryInitializer {
    override fun initialize(userRepositories: DesktopUserRepositories) {
        if (!DesktopModeFlags.ENABLE_DESKTOP_WINDOWING_PERSISTENCE.isTrue()) return
        //  TODO: b/365962554 - Handle the case that user moves to desktop before it's initialized
        mainCoroutineScope.launch {
            val desktopUserPersistentRepositoryMap =
                persistentRepository.getUserDesktopRepositoryMap() ?: return@launch
            for (userId in desktopUserPersistentRepositoryMap.keys) {
                val repository = userRepositories.getProfile(userId)
                val desktopRepositoryState =
                    persistentRepository.getDesktopRepositoryState(userId) ?: continue
                val desktopByDesktopIdMap = desktopRepositoryState.desktopMap
                for (desktopId in desktopByDesktopIdMap.keys) {
                    val persistentDesktop =
                        persistentRepository.readDesktop(userId, desktopId) ?: continue
                    val maxTasks =
                        DesktopModeStatus.getMaxTaskLimit(context).takeIf { it > 0 }
                            ?: persistentDesktop.zOrderedTasksCount
                    var visibleTasksCount = 0
                    persistentDesktop.zOrderedTasksList
                        // Reverse it so we initialize the repo from bottom to top.
                        .reversed()
                        .mapNotNull { taskId -> persistentDesktop.tasksByTaskIdMap[taskId] }
                        .forEach { task ->
                            if (
                                task.desktopTaskState == DesktopTaskState.VISIBLE &&
                                    visibleTasksCount < maxTasks
                            ) {
                                visibleTasksCount++
                                repository.addTask(
                                    persistentDesktop.displayId,
                                    task.taskId,
                                    isVisible = false,
                                )
                            } else {
                                repository.addTask(
                                    persistentDesktop.displayId,
                                    task.taskId,
                                    isVisible = false,
                                )
                                repository.minimizeTask(persistentDesktop.displayId, task.taskId)
                            }
                        }
                }
            }
        }
    }
}
