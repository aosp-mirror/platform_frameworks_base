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

import com.android.systemui.plugins.annotations.ProtectedInterface
import com.android.systemui.plugins.annotations.SimpleProperty
import java.io.PrintWriter

/** Interface for controlling an active clock */
@ProtectedInterface
interface ClockController {
    @get:SimpleProperty
    /** A small version of the clock, appropriate for smaller viewports */
    val smallClock: ClockFaceController

    @get:SimpleProperty
    /** A large version of the clock, appropriate when a bigger viewport is available */
    val largeClock: ClockFaceController

    @get:SimpleProperty
    /** Determines the way the hosting app should behave when rendering either clock face */
    val config: ClockConfig

    @get:SimpleProperty
    /** Events that clocks may need to respond to */
    val events: ClockEvents

    /** Initializes various rendering parameters. If never called, provides reasonable defaults. */
    fun initialize(isDarkTheme: Boolean, dozeFraction: Float, foldFraction: Float)

    /** Optional method for dumping debug information */
    fun dump(pw: PrintWriter)
}
