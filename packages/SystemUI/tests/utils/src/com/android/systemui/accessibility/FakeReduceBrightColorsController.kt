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

package com.android.systemui.accessibility

import com.android.systemui.qs.ReduceBrightColorsController

class FakeReduceBrightColorsController : ReduceBrightColorsController {

    private var isEnabled = false

    private val callbacks = LinkedHashSet<ReduceBrightColorsController.Listener>()

    override fun addCallback(listener: ReduceBrightColorsController.Listener) {
        callbacks.add(listener)
    }

    override fun removeCallback(listener: ReduceBrightColorsController.Listener) {
        callbacks.remove(listener)
    }

    override fun isReduceBrightColorsActivated(): Boolean {
        return isEnabled
    }

    override fun setReduceBrightColorsActivated(activated: Boolean) {
        if (activated != isEnabled) {
            isEnabled = activated
            for (callback in callbacks) {
                callback.onActivated(activated)
            }
        }
    }
}
