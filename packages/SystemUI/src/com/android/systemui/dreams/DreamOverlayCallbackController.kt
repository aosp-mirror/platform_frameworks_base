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

package com.android.systemui.dreams

import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.statusbar.policy.CallbackController
import javax.inject.Inject

/** Dream overlay-related callback information */
@SysUISingleton
class DreamOverlayCallbackController @Inject constructor() :
    CallbackController<DreamOverlayCallbackController.Callback> {

    private val callbacks = mutableSetOf<DreamOverlayCallbackController.Callback>()

    var isDreaming = false
        private set

    override fun addCallback(callback: DreamOverlayCallbackController.Callback) {
        callbacks.add(callback)
    }

    override fun removeCallback(callback: DreamOverlayCallbackController.Callback) {
        callbacks.remove(callback)
    }

    fun onWakeUp() {
        isDreaming = false
        callbacks.forEach { it.onWakeUp() }
    }

    fun onStartDream() {
        isDreaming = true
        callbacks.forEach { it.onStartDream() }
    }

    interface Callback {
        /** Dream overlay has ended */
        fun onWakeUp()

        /** Dream overlay has started */
        fun onStartDream()
    }
}
