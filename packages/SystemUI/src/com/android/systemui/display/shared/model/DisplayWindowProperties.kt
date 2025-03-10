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

package com.android.systemui.display.shared.model

import android.content.Context
import android.view.LayoutInflater
import android.view.WindowManager

/** Represents a display specific group of window related properties. */
data class DisplayWindowProperties(
    /** The id of the display associated with this instance. */
    val displayId: Int,
    /**
     * The window type that was used to create the [Context] in this instance, using
     * [Context.createWindowContext]. This is the window type that can be used when adding views to
     * the [WindowManager] associated with this instance.
     */
    @WindowManager.LayoutParams.WindowType val windowType: Int,
    /**
     * The display specific [Context] created using [Context.createWindowContext] with window type
     * associated with this instance.
     */
    val context: Context,

    /**
     * The display specific [WindowManager] instance to be used when adding windows of the type
     * associated with this instance.
     */
    val windowManager: WindowManager,

    /** The [LayoutInflater] to be used with the associated [Context]. */
    val layoutInflater: LayoutInflater,
)
