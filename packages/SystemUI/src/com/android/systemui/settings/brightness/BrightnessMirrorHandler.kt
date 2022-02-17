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
 * limitations under the License
 */

package com.android.systemui.settings.brightness

import com.android.systemui.statusbar.policy.BrightnessMirrorController
import com.android.systemui.statusbar.policy.BrightnessMirrorController.BrightnessMirrorListener

class BrightnessMirrorHandler(private val brightnessController: MirroredBrightnessController) {

    private var mirrorController: BrightnessMirrorController? = null

    private val brightnessMirrorListener = BrightnessMirrorListener { updateBrightnessMirror() }

    fun onQsPanelAttached() {
        mirrorController?.addCallback(brightnessMirrorListener)
    }

    fun onQsPanelDettached() {
        mirrorController?.removeCallback(brightnessMirrorListener)
    }

    fun setController(controller: BrightnessMirrorController) {
        mirrorController?.removeCallback(brightnessMirrorListener)
        mirrorController = controller
        mirrorController?.addCallback(brightnessMirrorListener)
        updateBrightnessMirror()
    }

    private fun updateBrightnessMirror() {
        mirrorController?.let { brightnessController.setMirror(it) }
    }
}