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

package com.android.systemui.screenshot.data.repository

import android.annotation.SuppressLint
import android.app.ActivityTaskManager
import android.app.IActivityTaskManager
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.screenshot.data.model.DisplayContentModel
import com.android.systemui.screenshot.data.model.SystemUiState
import com.android.systemui.screenshot.proxy.SystemUiProxy
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/**
 * Implements DisplayTaskRepository using [IActivityTaskManager], along with [ProfileTypeRepository]
 * and [SystemUiProxy].
 */
@SuppressLint("MissingPermission")
class DisplayContentRepositoryImpl
@Inject
constructor(
    private val atmService: IActivityTaskManager,
    private val systemUiProxy: SystemUiProxy,
    @Background private val background: CoroutineDispatcher,
) : DisplayContentRepository {

    override suspend fun getDisplayContent(displayId: Int): DisplayContentModel {
        return withContext(background) {
            val rootTasks = atmService.getAllRootTaskInfosOnDisplay(displayId)
            toDisplayTasksModel(displayId, rootTasks)
        }
    }

    private suspend fun toDisplayTasksModel(
        displayId: Int,
        rootTasks: List<ActivityTaskManager.RootTaskInfo>,
    ): DisplayContentModel {
        return DisplayContentModel(
            displayId,
            SystemUiState(systemUiProxy.isNotificationShadeExpanded()),
            rootTasks
        )
    }
}
