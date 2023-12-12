/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.systemui.shade

import android.annotation.IntDef
import android.os.Trace
import android.os.Trace.TRACE_TAG_APP as TRACE_TAG
import android.util.Log
import androidx.annotation.FloatRange
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.util.Compile
import java.util.concurrent.CopyOnWriteArrayList
import javax.inject.Inject

/**
 * A class responsible for managing the notification panel's current state.
 *
 * TODO(b/200063118): Make this class the one source of truth for the state of panel expansion.
 */
@SysUISingleton
@Deprecated("Use ShadeInteractor instead")
class ShadeExpansionStateManager @Inject constructor() {

    private val expansionListeners = CopyOnWriteArrayList<ShadeExpansionListener>()
    private val stateListeners = CopyOnWriteArrayList<ShadeStateListener>()

    @PanelState private var state: Int = STATE_CLOSED
    @FloatRange(from = 0.0, to = 1.0) private var fraction: Float = 0f
    private var expanded: Boolean = false
    private var tracking: Boolean = false
    private var dragDownPxAmount: Float = 0f

    /**
     * Adds a listener that will be notified when the panel expansion fraction has changed and
     * returns the current state in a ShadeExpansionChangeEvent for legacy purposes (b/23035507).
     *
     * @see #addExpansionListener
     */
    @Deprecated("Use ShadeInteractor instead")
    fun addExpansionListener(listener: ShadeExpansionListener): ShadeExpansionChangeEvent {
        expansionListeners.add(listener)
        return ShadeExpansionChangeEvent(fraction, expanded, tracking, dragDownPxAmount)
    }

    /** Removes an expansion listener. */
    fun removeExpansionListener(listener: ShadeExpansionListener) {
        expansionListeners.remove(listener)
    }

    /** Adds a listener that will be notified when the panel state has changed. */
    @Deprecated("Use ShadeInteractor instead")
    fun addStateListener(listener: ShadeStateListener) {
        stateListeners.add(listener)
    }

    /** Returns true if the panel is currently closed and false otherwise. */
    fun isClosed(): Boolean = state == STATE_CLOSED

    /**
     * Called when the panel expansion has changed.
     *
     * @param fraction the fraction from the expansion in [0, 1]
     * @param expanded whether the panel is currently expanded; this is independent from the
     *   fraction as the panel also might be expanded if the fraction is 0.
     * @param tracking whether we're currently tracking the user's gesture.
     */
    fun onPanelExpansionChanged(
        @FloatRange(from = 0.0, to = 1.0) fraction: Float,
        expanded: Boolean,
        tracking: Boolean,
        dragDownPxAmount: Float
    ) {
        require(!fraction.isNaN()) { "fraction cannot be NaN" }
        val oldState = state

        this.fraction = fraction
        this.expanded = expanded
        this.tracking = tracking
        this.dragDownPxAmount = dragDownPxAmount

        var fullyClosed = true
        var fullyOpened = false

        if (expanded) {
            if (this.state == STATE_CLOSED) {
                updateStateInternal(STATE_OPENING)
            }
            fullyClosed = false
            fullyOpened = fraction >= 1f
        }

        if (fullyOpened && !tracking) {
            updateStateInternal(STATE_OPEN)
        } else if (fullyClosed && !tracking && this.state != STATE_CLOSED) {
            updateStateInternal(STATE_CLOSED)
        }

        debugLog(
            "panelExpansionChanged:" +
                "start state=${oldState.panelStateToString()} " +
                "end state=${state.panelStateToString()} " +
                "f=$fraction " +
                "expanded=$expanded " +
                "tracking=$tracking " +
                "dragDownPxAmount=$dragDownPxAmount " +
                "${if (fullyOpened) " fullyOpened" else ""} " +
                if (fullyClosed) " fullyClosed" else ""
        )

        if (Trace.isTagEnabled(TRACE_TAG)) {
            Trace.traceCounter(TRACE_TAG, "panel_expansion", (fraction * 100).toInt())
            if (state != oldState) {
                Trace.asyncTraceForTrackEnd(TRACE_TAG, TRACK_NAME, 0)
                Trace.asyncTraceForTrackBegin(TRACE_TAG, TRACK_NAME, state.panelStateToString(), 0)
            }
        }

        val expansionChangeEvent =
            ShadeExpansionChangeEvent(fraction, expanded, tracking, dragDownPxAmount)
        expansionListeners.forEach { it.onPanelExpansionChanged(expansionChangeEvent) }
    }

    /** Updates the panel state if necessary. */
    fun updateState(@PanelState state: Int) {
        debugLog(
            "update state: ${this.state.panelStateToString()} -> ${state.panelStateToString()}"
        )
        if (this.state != state) {
            updateStateInternal(state)
        }
    }

    private fun updateStateInternal(@PanelState state: Int) {
        debugLog("go state: ${this.state.panelStateToString()} -> ${state.panelStateToString()}")
        this.state = state
        stateListeners.forEach { it.onPanelStateChanged(state) }
    }

    private fun debugLog(msg: String) {
        if (!DEBUG) return
        Log.v(TAG, msg)
    }

    companion object {
        private const val TRACK_NAME = "ShadeExpansionState"
    }
}

/** Enum for the current state of the panel. */
@Retention(AnnotationRetention.SOURCE)
@IntDef(value = [STATE_CLOSED, STATE_OPENING, STATE_OPEN])
internal annotation class PanelState

const val STATE_CLOSED = 0
const val STATE_OPENING = 1
const val STATE_OPEN = 2

@PanelState
fun Int.panelStateToString(): String {
    return when (this) {
        STATE_CLOSED -> "CLOSED"
        STATE_OPENING -> "OPENING"
        STATE_OPEN -> "OPEN"
        else -> this.toString()
    }
}

private val TAG = ShadeExpansionStateManager::class.simpleName
private val DEBUG = Compile.IS_DEBUG && Log.isLoggable(TAG, Log.DEBUG)
