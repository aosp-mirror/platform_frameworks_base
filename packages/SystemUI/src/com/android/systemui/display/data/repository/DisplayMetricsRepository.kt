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

package com.android.systemui.display.data.repository

import android.content.Context
import android.content.res.Configuration
import android.util.DisplayMetrics
import com.android.systemui.common.coroutine.ConflatedCallbackFlow.conflatedCallbackFlow
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.log.LogBuffer
import com.android.systemui.log.core.LogLevel
import com.android.systemui.log.dagger.DisplayMetricsRepoLog
import com.android.systemui.statusbar.policy.ConfigurationController
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn

/** Repository tracking display-related metrics like display height and width. */
@SysUISingleton
class DisplayMetricsRepository
@Inject
constructor(
    @Application scope: CoroutineScope,
    configurationController: ConfigurationController,
    displayMetricsHolder: DisplayMetrics,
    context: Context,
    @DisplayMetricsRepoLog logBuffer: LogBuffer,
) {

    private val displayMetrics: StateFlow<DisplayMetrics> =
        conflatedCallbackFlow {
                val callback =
                    object : ConfigurationController.ConfigurationListener {
                        override fun onConfigChanged(newConfig: Configuration?) {
                            context.display?.getMetrics(displayMetricsHolder)
                            trySend(displayMetricsHolder)
                        }
                    }
                configurationController.addCallback(callback)
                awaitClose { configurationController.removeCallback(callback) }
            }
            .onEach {
                logBuffer.log(
                    "DisplayMetrics",
                    LogLevel.INFO,
                    { str1 = it.toString() },
                    { "New metrics: $str1" },
                )
            }
            .stateIn(scope, SharingStarted.Eagerly, displayMetricsHolder)

    /** Returns the current display height in pixels. */
    val heightPixels: Int
        get() = displayMetrics.value.heightPixels
}
