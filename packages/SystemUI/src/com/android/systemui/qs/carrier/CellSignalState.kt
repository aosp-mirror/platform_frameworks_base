/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.systemui.qs.carrier

/**
 * Represents the state of cell signal for a particular slot.
 *
 * To be used between [QSCarrierGroupController] and [QSCarrier].
 */
data class CellSignalState(
    @JvmField val visible: Boolean = false,
    @JvmField val mobileSignalIconId: Int = 0,
    @JvmField val contentDescription: String? = null,
    @JvmField val typeContentDescription: String? = null,
    @JvmField val roaming: Boolean = false,
) {
    /**
     * Changes the visibility of this state by returning a copy with the visibility changed.
     *
     * If the visibility would not change, the same state is returned.
     *
     * @param visible the new visibility state
     * @return `this` if `this.visible == visible`. Else, a new copy with the visibility changed.
     */
    fun changeVisibility(visible: Boolean): CellSignalState {
        if (this.visible == visible) return this
        else return copy(visible = visible)
    }
}
