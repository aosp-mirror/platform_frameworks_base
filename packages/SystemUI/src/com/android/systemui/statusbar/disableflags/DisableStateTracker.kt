/*
 * Copyright (C) 2022 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package com.android.systemui.statusbar.disableflags

import com.android.systemui.statusbar.CommandQueue

/**
 * Tracks the relevant DISABLE_* flags provided in [mask1] and [mask2] and sets [isDisabled] based
 * on those masks. [callback] will be notified whenever [isDisabled] changes.
 *
 * Users are responsible for adding and removing this tracker from [CommandQueue] callbacks.
 */
class DisableStateTracker(
    private val mask1: Int,
    private val mask2: Int,
    private val callback: Callback,
) : CommandQueue.Callbacks {
    /**
     * True if any of the bits in [mask1] or [mask2] are on for the current disable flags, and false
     * otherwise.
     */
    var isDisabled = false
        private set(value) {
            if (field == value) return
            field = value
            callback.onDisabledChanged()
        }

    private var displayId: Int? = null

    /** Start tracking the disable flags and updating [isDisabled] accordingly. */
    fun startTracking(commandQueue: CommandQueue, displayId: Int) {
        // A view will only have its displayId once it's attached to a window, so we can only
        // provide the displayId when we start tracking.
        this.displayId = displayId
        commandQueue.addCallback(this)
    }

    /**
     * Stop tracking the disable flags.
     *
     * [isDisabled] will stay at the same value until we start tracking again.
     */
    fun stopTracking(commandQueue: CommandQueue) {
        this.displayId = null
        commandQueue.removeCallback(this)
    }

    override fun disable(displayId: Int, state1: Int, state2: Int, animate: Boolean) {
        if (this.displayId == null || displayId != this.displayId) {
            return
        }
        isDisabled = state1 and mask1 != 0 || state2 and mask2 != 0
    }

    /** Callback triggered whenever the value of [isDisabled] changes. */
    fun interface Callback {
        fun onDisabledChanged()
    }
}
