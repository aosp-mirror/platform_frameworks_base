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

package com.android.systemui.statusbar.core

import com.android.systemui.statusbar.data.repository.StatusBarModePerDisplayRepository
import com.android.systemui.statusbar.phone.AutoHideController
import com.android.systemui.statusbar.window.StatusBarWindowController
import com.android.systemui.statusbar.window.data.repository.StatusBarWindowStatePerDisplayRepository
import kotlinx.coroutines.CoroutineScope
import org.mockito.kotlin.mock

class FakeStatusBarOrchestratorFactory : StatusBarOrchestrator.Factory {

    private val createdOrchestrators = mutableMapOf<Int, StatusBarOrchestrator>()

    fun createdOrchestratorForDisplay(displayId: Int): StatusBarOrchestrator? =
        createdOrchestrators[displayId]

    override fun create(
        displayId: Int,
        displayScope: CoroutineScope,
        statusBarWindowStateRepository: StatusBarWindowStatePerDisplayRepository,
        statusBarModeRepository: StatusBarModePerDisplayRepository,
        statusBarInitializer: StatusBarInitializer,
        statusBarWindowController: StatusBarWindowController,
        autoHideController: AutoHideController,
    ): StatusBarOrchestrator =
        mock<StatusBarOrchestrator>().also { createdOrchestrators[displayId] = it }
}
