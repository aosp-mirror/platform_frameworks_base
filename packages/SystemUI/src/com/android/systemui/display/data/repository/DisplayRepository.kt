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

import android.hardware.display.DisplayManager
import android.hardware.display.DisplayManager.DisplayListener
import android.hardware.display.DisplayManager.EVENT_FLAG_DISPLAY_ADDED
import android.hardware.display.DisplayManager.EVENT_FLAG_DISPLAY_CHANGED
import android.hardware.display.DisplayManager.EVENT_FLAG_DISPLAY_REMOVED
import android.os.Handler
import android.util.Log
import android.view.Display
import com.android.systemui.common.coroutine.ConflatedCallbackFlow.conflatedCallbackFlow
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.util.traceSection
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn

/** Provides a [Flow] of [Display] as returned by [DisplayManager]. */
interface DisplayRepository {
    /** Provides a nullable set of displays. */
    val displays: Flow<Set<Display>>

    /**
     * Pending display id that can be enabled/disabled.
     *
     * When `null`, it means there is no pending display waiting to be enabled.
     */
    val pendingDisplay: Flow<Int?>
}

@SysUISingleton
class DisplayRepositoryImpl
@Inject
constructor(
    private val displayManager: DisplayManager,
    @Background backgroundHandler: Handler,
    @Application applicationScope: CoroutineScope,
    @Background backgroundCoroutineDispatcher: CoroutineDispatcher
) : DisplayRepository {

    override val displays: Flow<Set<Display>> =
        conflatedCallbackFlow {
                val callback =
                    object : DisplayListener {
                        override fun onDisplayAdded(displayId: Int) {
                            trySend(getDisplays())
                        }

                        override fun onDisplayRemoved(displayId: Int) {
                            trySend(getDisplays())
                        }

                        override fun onDisplayChanged(displayId: Int) {
                            trySend(getDisplays())
                        }
                    }
                displayManager.registerDisplayListener(
                    callback,
                    backgroundHandler,
                    EVENT_FLAG_DISPLAY_ADDED or
                        EVENT_FLAG_DISPLAY_CHANGED or
                        EVENT_FLAG_DISPLAY_REMOVED,
                )
                awaitClose { displayManager.unregisterDisplayListener(callback) }
            }
            .flowOn(backgroundCoroutineDispatcher)
            .stateIn(
                applicationScope,
                started = SharingStarted.WhileSubscribed(),
                initialValue = getDisplays()
            )

    private fun getDisplays(): Set<Display> =
        traceSection("DisplayRepository#getDisplays()") {
            displayManager.displays?.toSet() ?: emptySet()
        }

    override val pendingDisplay: Flow<Int?> =
        conflatedCallbackFlow {
                val callback =
                    object : DisplayConnectionListener {
                        private val pendingIds = mutableSetOf<Int>()
                        override fun onDisplayConnected(id: Int) {
                            pendingIds += id
                            trySend(id)
                        }

                        override fun onDisplayDisconnected(id: Int) {
                            if (id in pendingIds) {
                                pendingIds -= id
                                trySend(null)
                            } else {
                                Log.e(
                                    TAG,
                                    "onDisplayDisconnected received for unknown display. " +
                                        "id=$id, knownIds=$pendingIds"
                                )
                            }
                        }
                    }
                displayManager.registerDisplayListener(
                    callback,
                    backgroundHandler,
                    DisplayManager.EVENT_FLAG_DISPLAY_CONNECTION_CHANGED,
                )
                awaitClose { displayManager.unregisterDisplayListener(callback) }
            }
            .flowOn(backgroundCoroutineDispatcher)
            .stateIn(
                applicationScope,
                started = SharingStarted.WhileSubscribed(),
                initialValue = null
            )

    private companion object {
        const val TAG = "DisplayRepository"
    }
}

/** Used to provide default implementations for all methods. */
private interface DisplayConnectionListener : DisplayListener {

    override fun onDisplayConnected(id: Int) {}
    override fun onDisplayDisconnected(id: Int) {}
    override fun onDisplayAdded(id: Int) {}
    override fun onDisplayRemoved(id: Int) {}
    override fun onDisplayChanged(id: Int) {}
}
