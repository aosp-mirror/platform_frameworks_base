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

package com.android.systemui.controls.ui

/**
 * All controls need to respond to changes in state and handle user-generated events.
 * Implementations of this interface provide these different means by adding their own
 * event handlers, and will update the control ui as they see fit.
 */
interface Behavior {

    /**
     * Only called once per instance
     */
    fun initialize(cvh: ControlViewHolder)

    /**
     * Will be invoked on every update provided to the Control
     *
     * @param cws ControlWithState, as loaded from favorites and/or the application
     * @param colorOffset An additional flag to control rendering color. See [RenderInfo]
     */
    fun bind(cws: ControlWithState, colorOffset: Int = 0)
}
