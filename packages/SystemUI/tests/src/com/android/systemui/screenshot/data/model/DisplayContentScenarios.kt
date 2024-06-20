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

package com.android.systemui.screenshot.data.model

import android.content.ComponentName
import android.graphics.Rect
import com.android.systemui.screenshot.data.model.DisplayContentScenarios.Bounds.FREE_FORM
import com.android.systemui.screenshot.data.model.DisplayContentScenarios.Bounds.FULL_SCREEN
import com.android.systemui.screenshot.data.model.DisplayContentScenarios.Bounds.PIP
import com.android.systemui.screenshot.data.model.DisplayContentScenarios.Bounds.SPLIT_BOTTOM
import com.android.systemui.screenshot.data.model.DisplayContentScenarios.Bounds.SPLIT_TOP
import com.android.systemui.screenshot.data.model.DisplayContentScenarios.RootTasks.emptyRootSplit
import com.android.systemui.screenshot.data.model.DisplayContentScenarios.RootTasks.freeForm
import com.android.systemui.screenshot.data.model.DisplayContentScenarios.RootTasks.fullScreen
import com.android.systemui.screenshot.data.model.DisplayContentScenarios.RootTasks.launcher
import com.android.systemui.screenshot.data.model.DisplayContentScenarios.RootTasks.pictureInPicture
import com.android.systemui.screenshot.policy.ActivityType
import com.android.systemui.screenshot.policy.TestUserIds
import com.android.systemui.screenshot.policy.WindowingMode
import com.android.systemui.screenshot.policy.newChildTask
import com.android.systemui.screenshot.policy.newRootTaskInfo

/** Tools for creating a [DisplayContentModel] for different usage scenarios. */
object DisplayContentScenarios {

    data class TaskSpec(val taskId: Int, val userId: Int, val name: String)

    /** Home screen, with only the launcher visible */
    fun launcherOnly(shadeExpanded: Boolean = false) =
        DisplayContentModel(
            displayId = 0,
            systemUiState = SystemUiState(shadeExpanded = shadeExpanded),
            rootTasks =
                listOf(
                    launcher(visible = true),
                    emptyRootSplit,
                )
        )

    /** A Full screen activity for the personal (primary) user, with launcher behind it */
    fun singleFullScreen(spec: TaskSpec, shadeExpanded: Boolean = false) =
        DisplayContentModel(
            displayId = 0,
            systemUiState = SystemUiState(shadeExpanded = shadeExpanded),
            rootTasks =
                listOf(
                    fullScreen(spec, visible = true),
                    launcher(visible = false),
                    emptyRootSplit,
                )
        )

    fun splitScreenApps(
        top: TaskSpec,
        bottom: TaskSpec,
        focusedTaskId: Int,
        shadeExpanded: Boolean = false,
    ): DisplayContentModel {
        val topBounds = SPLIT_TOP
        val bottomBounds = SPLIT_BOTTOM
        return DisplayContentModel(
            displayId = 0,
            systemUiState = SystemUiState(shadeExpanded = shadeExpanded),
            rootTasks =
                listOf(
                    newRootTaskInfo(
                        taskId = 2,
                        userId = TestUserIds.PERSONAL,
                        bounds = FULL_SCREEN,
                        topActivity =
                            ComponentName.unflattenFromString(
                                if (top.taskId == focusedTaskId) top.name else bottom.name
                            ),
                    ) {
                        listOf(
                                newChildTask(
                                    taskId = top.taskId,
                                    bounds = topBounds,
                                    userId = top.userId,
                                    name = top.name
                                ),
                                newChildTask(
                                    taskId = bottom.taskId,
                                    bounds = bottomBounds,
                                    userId = bottom.userId,
                                    name = bottom.name
                                )
                            )
                            // Child tasks are ordered bottom-up in RootTaskInfo.
                            // Sort 'focusedTaskId' last.
                            // Boolean natural ordering: [false, true].
                            .sortedBy { it.id == focusedTaskId }
                    },
                    launcher(visible = false),
                )
        )
    }

    fun pictureInPictureApp(
        pip: TaskSpec,
        fullScreen: TaskSpec? = null,
        shadeExpanded: Boolean = false,
    ): DisplayContentModel {
        return DisplayContentModel(
            displayId = 0,
            systemUiState = SystemUiState(shadeExpanded = shadeExpanded),
            rootTasks =
                buildList {
                    add(pictureInPicture(pip))
                    fullScreen?.also { add(fullScreen(it, visible = true)) }
                    add(launcher(visible = (fullScreen == null)))
                    add(emptyRootSplit)
                }
        )
    }

    fun freeFormApps(
        vararg tasks: TaskSpec,
        focusedTaskId: Int,
        shadeExpanded: Boolean = false,
    ): DisplayContentModel {
        val freeFormTasks =
            tasks
                .map { freeForm(it) }
                // Root tasks are ordered top-down in List<RootTaskInfo>.
                // Sort 'focusedTaskId' last (Boolean natural ordering: [false, true])
                .sortedBy { it.childTaskIds[0] != focusedTaskId }
        return DisplayContentModel(
            displayId = 0,
            systemUiState = SystemUiState(shadeExpanded = shadeExpanded),
            rootTasks = freeFormTasks + launcher(visible = true) + emptyRootSplit
        )
    }

    /**
     * All of these are arbitrary dimensions exposed for asserting equality on test data.
     *
     * They should not be updated nor compared with any real device usage, except to keep them
     * somewhat sensible in terms of logical position (Re: PIP, SPLIT, etc).
     */
    object Bounds {
        val FULL_SCREEN = Rect(0, 0, 1080, 2400)
        val PIP = Rect(440, 1458, 1038, 1794)
        val SPLIT_TOP = Rect(0, 0, 1080, 1187)
        val SPLIT_BOTTOM = Rect(0, 1213, 1080, 2400)
        val FREE_FORM = Rect(119, 332, 1000, 1367)
    }

    /** A collection of task names used in test scenarios */
    object ActivityNames {
        /** The main YouTube activity */
        const val YOUTUBE =
            "com.google.android.youtube/" +
                "com.google.android.youtube.app.honeycomb.Shell\$HomeActivity"

        /** The main Files Activity */
        const val FILES =
            "com.google.android.apps.nbu.files/" +
                "com.google.android.apps.nbu.files.home.HomeActivity"

        /** The YouTube picture-in-picture activity */
        const val YOUTUBE_PIP =
            "com.google.android.youtube/" +
                "com.google.android.apps.youtube.app.watchwhile.WatchWhileActivity"

        /** The NexusLauncher activity */
        const val LAUNCHER =
            "com.google.android.apps.nexuslauncher/" +
                "com.google.android.apps.nexuslauncher.NexusLauncherActivity"
    }

    /**
     * A set of predefined RootTaskInfo used in test scenarios, matching as closely as possible
     * actual values returned by ActivityTaskManager
     */
    object RootTasks {
        /** An empty RootTaskInfo with no child tasks. */
        val emptyWithNoChildTasks =
            newRootTaskInfo(
                taskId = 2,
                visible = true,
                running = true,
                numActivities = 0,
                bounds = FULL_SCREEN,
            ) {
                emptyList()
            }

        /**
         * The empty RootTaskInfo that is always at the end of a list from ActivityTaskManager when
         * no other visible activities are in split mode
         */
        val emptyRootSplit =
            newRootTaskInfo(
                taskId = 2,
                visible = false,
                running = false,
                numActivities = 0,
                bounds = FULL_SCREEN,
                activityType = ActivityType.Undefined,
            ) {
                listOf(
                    newChildTask(taskId = 3, bounds = FULL_SCREEN, name = ""),
                    newChildTask(taskId = 4, bounds = Rect(0, 2400, 1080, 3600), name = ""),
                )
            }

        /** NexusLauncher on the default display. Usually below all other visible tasks */
        fun launcher(visible: Boolean) =
            newRootTaskInfo(
                taskId = 1,
                activityType = ActivityType.Home,
                visible = visible,
                bounds = FULL_SCREEN,
                topActivity = ComponentName.unflattenFromString(ActivityNames.LAUNCHER),
                topActivityType = ActivityType.Home,
            ) {
                listOf(newChildTask(taskId = 1002, name = ActivityNames.LAUNCHER))
            }

        /** A full screen Activity */
        fun fullScreen(task: TaskSpec, visible: Boolean) =
            newRootTaskInfo(
                taskId = task.taskId,
                userId = task.userId,
                visible = visible,
                bounds = FULL_SCREEN,
                topActivity = ComponentName.unflattenFromString(task.name),
            ) {
                listOf(newChildTask(taskId = task.taskId, userId = task.userId, name = task.name))
            }

        /** An activity in Picture-in-Picture mode */
        fun pictureInPicture(task: TaskSpec) =
            newRootTaskInfo(
                taskId = task.taskId,
                userId = task.userId,
                bounds = PIP,
                windowingMode = WindowingMode.PictureInPicture,
                topActivity = ComponentName.unflattenFromString(task.name),
            ) {
                listOf(newChildTask(taskId = task.taskId, userId = userId, name = task.name))
            }

        /** An activity in FreeForm mode */
        fun freeForm(task: TaskSpec) =
            newRootTaskInfo(
                taskId = task.taskId,
                userId = task.userId,
                bounds = FREE_FORM,
                windowingMode = WindowingMode.Freeform,
                topActivity = ComponentName.unflattenFromString(task.name),
            ) {
                listOf(newChildTask(taskId = task.taskId, userId = userId, name = task.name))
            }
    }
}
