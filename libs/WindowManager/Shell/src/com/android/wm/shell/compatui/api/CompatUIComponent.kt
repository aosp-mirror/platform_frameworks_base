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

package com.android.wm.shell.compatui.api

import android.util.Log

/**
 * The component created after a {@link CompatUISpec} definition
 */
class CompatUIComponent(
    private val spec: CompatUISpec,
    private val id: String
) {

    /**
     * Invoked every time a new CompatUIInfo comes from core
     * @param newInfo The new CompatUIInfo object
     * @param sharedState The state shared between all the component
     */
    fun update(newInfo: CompatUIInfo, state: CompatUIState) {
        // TODO(b/322817374): To be removed when the implementation is provided.
        Log.d("CompatUIComponent", "update() newInfo: $newInfo state:$state")
    }

    fun release() {
        // TODO(b/322817374): To be removed when the implementation is provided.
        Log.d("CompatUIComponent", "release()")
    }
}