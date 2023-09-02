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
import android.os.Trace
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
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
    val pendingDisplay: Flow<PendingDisplay?>

    /** Represents a connected display that has not been enabled yet. */
    interface PendingDisplay {
        /** Id of the pending display. */
        val id: Int

        /** Enables the display, making it available to the system. */
        suspend fun enable()

        /**
         * Ignores the pending display. When called, this specific display id doesn't appear as
         * pending anymore until the display is disconnected and reconnected again.
         */
        suspend fun ignore()

        /** Disables the display, making it unavailable to the system. */
        suspend fun disable()
    }
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

    // Displays are enabled only after receiving them in [onDisplayAdded]
    private val enabledDisplays: StateFlow<Set<Display>> =
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

    /** Propagate to the listeners only enabled displays */
    override val displays: Flow<Set<Display>> = enabledDisplays

    private val enabledDisplayIds: Flow<Set<Int>> =
        enabledDisplays
            .map { enabledDisplaysSet -> enabledDisplaysSet.map { it.displayId }.toSet() }
            .debugLog("enabledDisplayIds")

    private val ignoredDisplayIds = MutableStateFlow<Set<Int>>(emptySet())

    /* keeps connected displays until they are disconnected. */
    private val connectedDisplayIds: StateFlow<Set<Int>> =
        conflatedCallbackFlow {
                val callback =
                    object : DisplayConnectionListener {
                        private val connectedIds = mutableSetOf<Int>()
                        override fun onDisplayConnected(id: Int) {
                            if (DEBUG) {
                                Log.d(TAG, "$id connected")
                            }
                            connectedIds += id
                            ignoredDisplayIds.value -= id
                            trySend(connectedIds.toSet())
                        }

                        override fun onDisplayDisconnected(id: Int) {
                            connectedIds -= id
                            if (DEBUG) {
                                Log.d(TAG, "$id disconnected. Connected ids: $connectedIds")
                            }
                            ignoredDisplayIds.value -= id
                            trySend(connectedIds.toSet())
                        }
                    }
                displayManager.registerDisplayListener(
                    callback,
                    backgroundHandler,
                    DisplayManager.EVENT_FLAG_DISPLAY_CONNECTION_CHANGED,
                )
                awaitClose { displayManager.unregisterDisplayListener(callback) }
            }
            .distinctUntilChanged()
            .debugLog("connectedDisplayIds")
            .flowOn(backgroundCoroutineDispatcher)
            .stateIn(
                applicationScope,
                started = SharingStarted.WhileSubscribed(),
                initialValue = emptySet()
            )

    /**
     * Pending displays are the ones connected, but not enabled and not ignored. A connected display
     * is ignored after the user makes the decision to use it or not. For now, the initial decision
     * from the user is final and not reversible.
     */
    private val pendingDisplayIds: Flow<Set<Int>> =
        combine(enabledDisplayIds, connectedDisplayIds, ignoredDisplayIds) {
                enabledDisplaysIds,
                connectedDisplayIds,
                ignoredDisplayIds ->
                if (DEBUG) {
                    Log.d(
                        TAG,
                        "combining enabled: $enabledDisplaysIds, " +
                            "connected: $connectedDisplayIds, ignored: $ignoredDisplayIds"
                    )
                }
                connectedDisplayIds - enabledDisplaysIds - ignoredDisplayIds
            }
            .debugLog("pendingDisplayIds")

    override val pendingDisplay: Flow<DisplayRepository.PendingDisplay?> =
        pendingDisplayIds
            .map { pendingDisplayIds ->
                val id = pendingDisplayIds.maxOrNull() ?: return@map null
                object : DisplayRepository.PendingDisplay {
                    override val id = id
                    override suspend fun enable() {
                        traceSection("DisplayRepository#enable($id)") {
                            displayManager.enableConnectedDisplay(id)
                        }
                        // After the display has been enabled, it is automatically ignored.
                        ignore()
                    }

                    override suspend fun ignore() {
                        traceSection("DisplayRepository#ignore($id)") {
                            ignoredDisplayIds.value += id
                        }
                    }

                    override suspend fun disable() {
                        ignore()
                        traceSection("DisplayRepository#disable($id)") {
                            displayManager.disableConnectedDisplay(id)
                        }
                    }
                }
            }
            .debugLog("pendingDisplay")

    private fun <T> Flow<T>.debugLog(flowName: String): Flow<T> {
        return if (DEBUG) {
            this.onEach {
                Log.d(TAG, "$flowName: $it")
                Trace.asyncTraceForTrackEnd(Trace.TRACE_TAG_APP, "$TAG#$flowName", 0)
                Trace.asyncTraceForTrackBegin(Trace.TRACE_TAG_APP, "$TAG#$flowName", "$it", 0)
            }
        } else {
            this
        }
    }

    private companion object {
        const val TAG = "DisplayRepository"
        val DEBUG = Log.isLoggable(TAG, Log.DEBUG)
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
