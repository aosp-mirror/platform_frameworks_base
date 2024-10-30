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

package com.android.systemui.statusbar.events

import android.view.Gravity
import android.view.Surface

/** Represents a corner on the display for the privacy dot. */
enum class PrivacyDotCorner(
    val index: Int,
    val gravity: Int,
    val innerGravity: Int,
    val title: String,
) {
    TopLeft(
        index = 0,
        gravity = Gravity.TOP or Gravity.LEFT,
        innerGravity = Gravity.CENTER_VERTICAL or Gravity.RIGHT,
        title = "TopLeft",
    ),
    TopRight(
        index = 1,
        gravity = Gravity.TOP or Gravity.RIGHT,
        innerGravity = Gravity.CENTER_VERTICAL or Gravity.LEFT,
        title = "TopRight",
    ),
    BottomRight(
        index = 2,
        gravity = Gravity.BOTTOM or Gravity.RIGHT,
        innerGravity = Gravity.CENTER_VERTICAL or Gravity.RIGHT,
        title = "BottomRight",
    ),
    BottomLeft(
        index = 3,
        gravity = Gravity.BOTTOM or Gravity.LEFT,
        innerGravity = Gravity.CENTER_VERTICAL or Gravity.LEFT,
        title = "BottomLeft",
    ),
}

fun PrivacyDotCorner.rotatedCorner(@Surface.Rotation rotation: Int): PrivacyDotCorner {
    var modded = index - rotation
    if (modded < 0) {
        modded += 4
    }
    return PrivacyDotCorner.entries[modded]
}
