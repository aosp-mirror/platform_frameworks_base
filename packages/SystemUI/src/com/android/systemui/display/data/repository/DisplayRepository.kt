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

import android.annotation.SuppressLint
import android.hardware.display.DisplayManager
import android.hardware.display.DisplayManager.DISPLAY_CATEGORY_ALL_INCLUDING_DISABLED
import android.hardware.display.DisplayManager.DisplayListener
import android.hardware.display.DisplayManager.EVENT_FLAG_DISPLAY_ADDED
import android.hardware.display.DisplayManager.EVENT_FLAG_DISPLAY_CHANGED
import android.hardware.display.DisplayManager.EVENT_FLAG_DISPLAY_REMOVED
import android.os.Handler
import android.util.Log
import android.view.Display
import com.android.app.tracing.FlowTracing.traceEach
import com.android.app.tracing.traceSection
import com.android.systemui.Flags
import com.android.systemui.common.coroutine.ConflatedCallbackFlow.conflatedCallbackFlow
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.display.data.DisplayEvent
import com.android.systemui.util.Compile
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
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.scan
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn

/** Provides a [Flow] of [Display] as returned by [DisplayManager]. */
interface DisplayRepository {
    /** Display change event indicating a change to the given displayId has occurred. */
    val displayChangeEvent: Flow<Int>

    /** Display addition event indicating a new display has been added. */
    val displayAdditionEvent: Flow<Display?>

    /** Provides the current set of displays. */
    val displays: Flow<Set<Display>>

    /**
     * Pending display id that can be enabled/disabled.
     *
     * When `null`, it means there is no pending display waiting to be enabled.
     */
    val pendingDisplay: Flow<PendingDisplay?>

    /** Whether the default display is currently off. */
    val defaultDisplayOff: Flow<Boolean>

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
@SuppressLint("SharedFlowCreation")
class DisplayRepositoryImpl
@Inject
constructor(
    private val displayManager: DisplayManager,
    @Background backgroundHandler: Handler,
    @Background bgApplicationScope: CoroutineScope,
    @Background backgroundCoroutineDispatcher: CoroutineDispatcher
) : DisplayRepository {
    private val allDisplayEvents: Flow<DisplayEvent> =
        conflatedCallbackFlow {
                val callback =
                    object : DisplayListener {
                        override fun onDisplayAdded(displayId: Int) {
                            trySend(DisplayEvent.Added(displayId))
                        }

                        override fun onDisplayRemoved(displayId: Int) {
                            trySend(DisplayEvent.Removed(displayId))
                        }

                        override fun onDisplayChanged(displayId: Int) {
                            trySend(DisplayEvent.Changed(displayId))
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
            .onStart { emit(DisplayEvent.Changed(Display.DEFAULT_DISPLAY)) }
            .debugLog("allDisplayEvents")
            .flowOn(backgroundCoroutineDispatcher)

    override val displayChangeEvent: Flow<Int> =
        allDisplayEvents.filterIsInstance<DisplayEvent.Changed>().map { event -> event.displayId }

    override val displayAdditionEvent: Flow<Display?> =
        allDisplayEvents.filterIsInstance<DisplayEvent.Added>().map { getDisplay(it.displayId) }

    private val oldEnabledDisplays: Flow<Set<Display>> =
        allDisplayEvents
            .map { getDisplays() }
            .shareIn(bgApplicationScope, started = SharingStarted.WhileSubscribed(), replay = 1)

    /** Propagate to the listeners only enabled displays */
    private val enabledDisplayIds: Flow<Set<Int>> =
        if (Flags.enableEfficientDisplayRepository()) {
                allDisplayEvents
                    .scan(initial = emptySet()) { previousIds: Set<Int>, event: DisplayEvent ->
                        val id = event.displayId
                        when (event) {
                            is DisplayEvent.Removed -> previousIds - id
                            is DisplayEvent.Added,
                            is DisplayEvent.Changed -> previousIds + id
                        }
                    }
                    .shareIn(
                        bgApplicationScope,
                        started = SharingStarted.WhileSubscribed(),
                        replay = 1
                    )
            } else {
                oldEnabledDisplays.map { enabledDisplaysSet ->
                    enabledDisplaysSet.map { it.displayId }.toSet()
                }
            }
            .debugLog("enabledDisplayIds")

    /**
     * Represents displays that went though the [DisplayListener.onDisplayAdded] callback.
     *
     * Those are commonly the ones provided by [DisplayManager.getDisplays] by default.
     */
    private val enabledDisplays: Flow<Set<Display>> =
        if (Flags.enableEfficientDisplayRepository()) {
            enabledDisplayIds
                .mapElementsLazily { displayId -> getDisplay(displayId) }
                .flowOn(backgroundCoroutineDispatcher)
                .debugLog("enabledDisplayIds")
        } else {
            oldEnabledDisplays
        }

    /**
     * Represents displays that went though the [DisplayListener.onDisplayAdded] callback.
     *
     * Those are commonly the ones provided by [DisplayManager.getDisplays] by default.
     */
    override val displays: Flow<Set<Display>> = enabledDisplays

    private fun getDisplays(): Set<Display> =
        traceSection("$TAG#getDisplays()") { displayManager.displays?.toSet() ?: emptySet() }

    private val _ignoredDisplayIds = MutableStateFlow<Set<Int>>(emptySet())
    private val ignoredDisplayIds: Flow<Set<Int>> = _ignoredDisplayIds.debugLog("ignoredDisplayIds")

    private fun getInitialConnectedDisplays(): Set<Int> =
        traceSection("$TAG#getInitialConnectedDisplays") {
            displayManager
                .getDisplays(DISPLAY_CATEGORY_ALL_INCLUDING_DISABLED)
                .map { it.displayId }
                .toSet()
                .also {
                    if (DEBUG) {
                        Log.d(TAG, "getInitialConnectedDisplays: $it")
                    }
                }
        }

    /* keeps connected displays until they are disconnected. */
    private val connectedDisplayIds: StateFlow<Set<Int>> =
        conflatedCallbackFlow {
                val connectedIds = getInitialConnectedDisplays().toMutableSet()
                val callback =
                    object : DisplayConnectionListener {
                        override fun onDisplayConnected(id: Int) {
                            if (DEBUG) {
                                Log.d(TAG, "display with id=$id connected.")
                            }
                            connectedIds += id
                            _ignoredDisplayIds.value -= id
                            trySend(connectedIds.toSet())
                        }

                        override fun onDisplayDisconnected(id: Int) {
                            connectedIds -= id
                            if (DEBUG) {
                                Log.d(TAG, "display with id=$id disconnected.")
                            }
                            _ignoredDisplayIds.value -= id
                            trySend(connectedIds.toSet())
                        }
                    }
                trySend(connectedIds.toSet())
                displayManager.registerDisplayListener(
                    callback,
                    backgroundHandler,
                    DisplayManager.EVENT_FLAG_DISPLAY_CONNECTION_CHANGED,
                )
                awaitClose { displayManager.unregisterDisplayListener(callback) }
            }
            .distinctUntilChanged()
            .debugLog("connectedDisplayIds")
            .stateIn(
                bgApplicationScope,
                started = SharingStarted.WhileSubscribed(),
                // The initial value is set to empty, but connected displays are gathered as soon as
                // the flow starts being collected. This is to ensure the call to get displays (an
                // IPC) happens in the background instead of when this object
                // is instantiated.
                initialValue = emptySet()
            )

    private val connectedExternalDisplayIds: Flow<Set<Int>> =
        connectedDisplayIds
            .map { connectedDisplayIds ->
                traceSection("$TAG#filteringExternalDisplays") {
                    connectedDisplayIds
                        .filter { id -> getDisplayType(id) == Display.TYPE_EXTERNAL }
                        .toSet()
                }
            }
            .flowOn(backgroundCoroutineDispatcher)
            .debugLog("connectedExternalDisplayIds")

    private fun getDisplayType(displayId: Int): Int? =
        traceSection("$TAG#getDisplayType") { displayManager.getDisplay(displayId)?.type }

    private fun getDisplay(displayId: Int): Display? =
        traceSection("$TAG#getDisplay") { displayManager.getDisplay(displayId) }

    /**
     * Pending displays are the ones connected, but not enabled and not ignored.
     *
     * A connected display is ignored after the user makes the decision to use it or not. For now,
     * the initial decision from the user is final and not reversible.
     */
    private val pendingDisplayIds: Flow<Set<Int>> =
        combine(enabledDisplayIds, connectedExternalDisplayIds, ignoredDisplayIds) {
                enabledDisplaysIds,
                connectedExternalDisplayIds,
                ignoredDisplayIds ->
                if (DEBUG) {
                    Log.d(
                        TAG,
                        "combining enabled=$enabledDisplaysIds, " +
                            "connectedExternalDisplayIds=$connectedExternalDisplayIds, " +
                            "ignored=$ignoredDisplayIds"
                    )
                }
                connectedExternalDisplayIds - enabledDisplaysIds - ignoredDisplayIds
            }
            .debugLog("allPendingDisplayIds")

    /** Which display id should be enabled among the pending ones. */
    private val pendingDisplayId: Flow<Int?> =
        pendingDisplayIds.map { it.maxOrNull() }.distinctUntilChanged().debugLog("pendingDisplayId")

    override val pendingDisplay: Flow<DisplayRepository.PendingDisplay?> =
        pendingDisplayId
            .map { displayId ->
                val id = displayId ?: return@map null
                object : DisplayRepository.PendingDisplay {
                    override val id = id

                    override suspend fun enable() {
                        traceSection("DisplayRepository#enable($id)") {
                            if (DEBUG) {
                                Log.d(TAG, "Enabling display with id=$id")
                            }
                            displayManager.enableConnectedDisplay(id)
                        }
                        // After the display has been enabled, it is automatically ignored.
                        ignore()
                    }

                    override suspend fun ignore() {
                        traceSection("DisplayRepository#ignore($id)") {
                            _ignoredDisplayIds.value += id
                        }
                    }

                    override suspend fun disable() {
                        ignore()
                        traceSection("DisplayRepository#disable($id)") {
                            if (DEBUG) {
                                Log.d(TAG, "Disabling display with id=$id")
                            }
                            displayManager.disableConnectedDisplay(id)
                        }
                    }
                }
            }
            .debugLog("pendingDisplay")

    override val defaultDisplayOff: Flow<Boolean> =
        displays
            .map { displays -> displays.firstOrNull { it.displayId == Display.DEFAULT_DISPLAY } }
            .map { it?.state == Display.STATE_OFF }

    private fun <T> Flow<T>.debugLog(flowName: String): Flow<T> {
        return if (DEBUG) {
            traceEach(flowName, logcat = true, traceEmissionCount = true)
        } else {
            this
        }
    }

    /**
     * Maps a set of T to a set of V, minimizing the number of `createValue` calls taking into
     * account the diff between each root flow emission.
     *
     * This is needed to minimize the number of [getDisplay] in this class. Note that if the
     * [createValue] returns a null element, it will not be added in the output set.
     */
    private fun <T, V> Flow<Set<T>>.mapElementsLazily(createValue: (T) -> V?): Flow<Set<V>> {
        var initialSet = emptySet<T>()
        val currentMap = mutableMapOf<T, V>()
        var resultSet = emptySet<V>()
        return onEach { currentSet ->
                val removed = initialSet - currentSet
                val added = currentSet - initialSet
                if (added.isNotEmpty() || removed.isNotEmpty()) {
                    added.forEach { key: T -> createValue(key)?.let { currentMap[key] = it } }
                    removed.forEach { key: T -> currentMap.remove(key) }
                    resultSet = currentMap.values.toSet() // Creates a **copy** of values
                }
                initialSet = currentSet
            }
            .map { resultSet }
    }

    private companion object {
        const val TAG = "DisplayRepository"
        val DEBUG = Log.isLoggable(TAG, Log.DEBUG) || Compile.IS_DEBUG
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
