/*
 * Copyright (C) 2024 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */
package com.android.systemui.plugins.clocks

import android.view.View
import com.android.systemui.plugins.annotations.ProtectedInterface
import com.android.systemui.plugins.annotations.SimpleProperty

/** Interface for a specific clock face version rendered by the clock */
@ProtectedInterface
interface ClockFaceController {
    @get:SimpleProperty
    @Deprecated("Prefer use of layout")
    /** View that renders the clock face */
    val view: View

    @get:SimpleProperty
    /** Layout specification for this clock */
    val layout: ClockFaceLayout

    @get:SimpleProperty
    /** Determines the way the hosting app should behave when rendering this clock face */
    val config: ClockFaceConfig

    @get:SimpleProperty
    /** Current theme information the clock is using */
    val theme: ThemeConfig

    @get:SimpleProperty
    /** Events specific to this clock face */
    val events: ClockFaceEvents

    @get:SimpleProperty
    /** Triggers for various animations */
    val animations: ClockAnimations
}
