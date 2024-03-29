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

package com.android.systemui.settings.brightness

import android.view.View
import com.android.systemui.settings.brightness.MirrorController.BrightnessMirrorListener
import com.android.systemui.statusbar.policy.CallbackController

interface MirrorController : CallbackController<BrightnessMirrorListener> {

    /**
     * Get the [ToggleSlider] currently associated with this controller, or `null` if none currently
     */
    fun getToggleSlider(): ToggleSlider?

    /**
     * Indicate to this controller that the user is dragging on the brightness view and the mirror
     * should show
     */
    fun showMirror()

    /**
     * Indicate to this controller that the user has stopped dragging on the brightness view and the
     * mirror should hide
     */
    fun hideMirror()

    /**
     * Set the location and size of the current brightness [view] in QS so it can be properly
     * adapted to show the mirror in the same location and with the same size.
     */
    fun setLocationAndSize(view: View)

    fun interface BrightnessMirrorListener {
        fun onBrightnessMirrorReinflated(brightnessMirror: View?)
    }
}
