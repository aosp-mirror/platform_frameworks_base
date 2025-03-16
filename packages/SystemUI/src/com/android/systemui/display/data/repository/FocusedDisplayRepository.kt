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

package com.android.systemui.display.data.repository

import android.annotation.MainThread
import android.view.Display.DEFAULT_DISPLAY
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.log.LogBuffer
import com.android.systemui.log.core.LogLevel
import com.android.systemui.log.dagger.FocusedDisplayRepoLog
import com.android.systemui.utils.coroutines.flow.conflatedCallbackFlow
import com.android.wm.shell.shared.FocusTransitionListener
import com.android.wm.shell.shared.ShellTransitions
import java.util.concurrent.Executor
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn

/** Repository tracking display focus. */
interface FocusedDisplayRepository {
    /** Provides the currently focused display. */
    val focusedDisplayId: StateFlow<Int>
}

@SysUISingleton
@MainThread
class FocusedDisplayRepositoryImpl
@Inject
constructor(
    @Background val backgroundScope: CoroutineScope,
    @Background private val backgroundExecutor: Executor,
    transitions: ShellTransitions,
    @FocusedDisplayRepoLog logBuffer: LogBuffer,
) : FocusedDisplayRepository {
    val focusedTask: Flow<Int> =
        conflatedCallbackFlow<Int> {
                val listener =
                    object : FocusTransitionListener {
                        override fun onFocusedDisplayChanged(displayId: Int) {
                            trySend(displayId)
                        }
                    }
                transitions.setFocusTransitionListener(listener, backgroundExecutor)
                awaitClose { transitions.unsetFocusTransitionListener(listener) }
            }
            .onEach {
                logBuffer.log(
                    "FocusedDisplayRepository",
                    LogLevel.INFO,
                    { str1 = it.toString() },
                    { "Newly focused display: $str1" },
                )
            }

    override val focusedDisplayId: StateFlow<Int>
        get() = focusedTask.stateIn(backgroundScope, SharingStarted.Eagerly, DEFAULT_DISPLAY)
}
