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
package com.android.systemui.unfold.util

import com.android.systemui.unfold.updates.FOLD_UPDATE_FINISH_FULL_OPEN
import com.android.systemui.unfold.updates.FOLD_UPDATE_FINISH_HALF_OPEN
import com.android.systemui.unfold.updates.FoldStateProvider
import com.android.systemui.unfold.updates.FoldStateProvider.FoldUpdate
import com.android.systemui.unfold.updates.FoldStateProvider.FoldUpdatesListener

class TestFoldStateProvider : FoldStateProvider {

    private val listeners: MutableList<FoldUpdatesListener> = arrayListOf()
    val hasListeners: Boolean
        get() = listeners.isNotEmpty()

    override fun start() {
    }

    override fun stop() {
        listeners.clear()
    }

    private var _isFinishedOpening: Boolean = false

    override val isFinishedOpening: Boolean
        get() = _isFinishedOpening

    override fun addCallback(listener: FoldUpdatesListener) {
        listeners += listener
    }

    override fun removeCallback(listener: FoldUpdatesListener) {
        listeners -= listener
    }

    fun sendFoldUpdate(@FoldUpdate update: Int) {
        if (update == FOLD_UPDATE_FINISH_FULL_OPEN || update == FOLD_UPDATE_FINISH_HALF_OPEN) {
            _isFinishedOpening = true
        }
        listeners.forEach { it.onFoldUpdate(update) }
    }

    fun sendHingeAngleUpdate(angle: Float) {
        listeners.forEach { it.onHingeAngleUpdate(angle) }
    }

    fun sendUnfoldedScreenAvailable() {
        listeners.forEach { it.onUnfoldedScreenAvailable() }
    }
}
