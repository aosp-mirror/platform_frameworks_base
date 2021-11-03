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

package com.android.systemui.statusbar.phone.panelstate

import androidx.annotation.FloatRange
import com.android.systemui.dagger.SysUISingleton
import javax.inject.Inject

/**
 * A class responsible for managing the notification panel's current state.
 *
 * TODO(b/200063118): Move [PanelBar.panelExpansionChanged] logic to this class and make this class
 *   the one source of truth for the state of panel expansion.
 */
@SysUISingleton
class PanelExpansionStateManager @Inject constructor() {

    private val listeners: MutableList<PanelExpansionListener> = mutableListOf()

    /** Adds a listener that will be notified about panel events. */
    fun addListener(listener: PanelExpansionListener) {
        listeners.add(listener)
    }

    /** Called when the panel expansion has changed. Notifies all listeners of change. */
    fun onPanelExpansionChanged(
        @FloatRange(from = 0.0, to = 1.0) fraction: Float,
        tracking: Boolean
    ) {
        listeners.forEach { it.onPanelExpansionChanged(fraction, tracking) }
    }
}
