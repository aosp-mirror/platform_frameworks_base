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
 */

package com.android.systemui.statusbar.pipeline.wifi.ui.viewmodel

import android.graphics.Color
import com.android.systemui.statusbar.pipeline.StatusBarPipelineFlags
import com.android.systemui.statusbar.pipeline.shared.ConnectivityPipelineLogger
import com.android.systemui.statusbar.pipeline.shared.ConnectivityPipelineLogger.Companion.logOutputChange
import com.android.systemui.statusbar.pipeline.wifi.domain.interactor.WifiInteractor
import com.android.systemui.statusbar.pipeline.wifi.shared.WifiConstants
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf

/**
 * Models the UI state for the status bar wifi icon.
 *
 * TODO(b/238425913): Hook this up to the real status bar wifi view using a view binder.
 */
class WifiViewModel @Inject constructor(
    statusBarPipelineFlags: StatusBarPipelineFlags,
    private val constants: WifiConstants,
    private val logger: ConnectivityPipelineLogger,
    private val interactor: WifiInteractor,
) {
    val isActivityInVisible: Flow<Boolean>
        get() =
            if (!constants.shouldShowActivityConfig) {
                flowOf(false)
            } else {
                interactor.hasActivityIn
            }
                .logOutputChange(logger, "activityInVisible")

    /** The tint that should be applied to the icon. */
    val tint: Flow<Int> = if (!statusBarPipelineFlags.useNewPipelineDebugColoring()) {
        emptyFlow()
    } else {
        flowOf(Color.CYAN)
    }
}
