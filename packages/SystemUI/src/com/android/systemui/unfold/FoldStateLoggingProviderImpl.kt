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

package com.android.systemui.unfold

import android.util.Log
import com.android.systemui.unfold.FoldStateLoggingProvider.FoldStateLoggingListener
import com.android.systemui.unfold.FoldStateLoggingProvider.LoggedFoldedStates
import com.android.systemui.unfold.updates.FOLD_UPDATE_FINISH_CLOSED
import com.android.systemui.unfold.updates.FOLD_UPDATE_FINISH_FULL_OPEN
import com.android.systemui.unfold.updates.FOLD_UPDATE_FINISH_HALF_OPEN
import com.android.systemui.unfold.updates.FOLD_UPDATE_START_CLOSING
import com.android.systemui.unfold.updates.FOLD_UPDATE_START_OPENING
import com.android.systemui.unfold.updates.FoldStateProvider
import com.android.systemui.unfold.updates.FoldStateProvider.FoldUpdate
import com.android.systemui.util.time.SystemClock

/**
 * Reports device fold states for logging purposes.
 *
 * Wraps the state provided by [FoldStateProvider] to output only [FULLY_OPENED], [FULLY_CLOSED] and
 * [HALF_OPENED] for logging purposes.
 *
 * Note that [HALF_OPENED] state is only emitted after the device angle is stable for some timeout.
 * Check [FoldStateProvider] impl for it.
 *
 * This doesn't log the following transitions:
 * - [HALF_OPENED] -> [FULLY_OPENED]: not interesting, as there is no transition going on
 * - [HALF_OPENED] -> [HALF_OPENED]: not meaningful.
 */
class FoldStateLoggingProviderImpl(
    private val foldStateProvider: FoldStateProvider,
    private val clock: SystemClock
) : FoldStateLoggingProvider, FoldStateProvider.FoldUpdatesListener {

    private val outputListeners: MutableList<FoldStateLoggingListener> = mutableListOf()

    @LoggedFoldedStates private var lastState: Int? = null
    private var actionStartMillis: Long? = null

    override fun init() {
        foldStateProvider.addCallback(this)
        foldStateProvider.start()
    }

    override fun uninit() {
        foldStateProvider.removeCallback(this)
        foldStateProvider.stop()
    }

    override fun onHingeAngleUpdate(angle: Float) {}

    override fun onFoldUpdate(@FoldUpdate update: Int) {
        val now = clock.elapsedRealtime()
        when (update) {
            FOLD_UPDATE_START_OPENING -> {
                lastState = FULLY_CLOSED
                actionStartMillis = now
            }
            FOLD_UPDATE_START_CLOSING -> actionStartMillis = now
            FOLD_UPDATE_FINISH_HALF_OPEN -> dispatchState(HALF_OPENED)
            FOLD_UPDATE_FINISH_FULL_OPEN -> dispatchState(FULLY_OPENED)
            FOLD_UPDATE_FINISH_CLOSED -> dispatchState(FULLY_CLOSED)
        }
    }

    private fun dispatchState(@LoggedFoldedStates current: Int) {
        val now = clock.elapsedRealtime()
        val previous = lastState
        val lastActionStart = actionStartMillis

        if (previous != null && previous != current && lastActionStart != null) {
            val time = now - lastActionStart
            val foldStateChange = FoldStateChange(previous, current, time)
            outputListeners.forEach { it.onFoldUpdate(foldStateChange) }
            if (DEBUG) {
                Log.d(TAG, "From $previous to $current in $time")
            }
        }

        actionStartMillis = null
        lastState = current
    }

    override fun addCallback(listener: FoldStateLoggingListener) {
        outputListeners.add(listener)
    }

    override fun removeCallback(listener: FoldStateLoggingListener) {
        outputListeners.remove(listener)
    }
}

private const val DEBUG = false
private const val TAG = "FoldStateLoggingProviderImpl"
