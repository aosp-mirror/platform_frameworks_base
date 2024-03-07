/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.systemui.common.shared.model

/** Models the bounds of the notification container. */
data class NotificationContainerBounds(
    /** The position of the left of the container in its window coordinate system, in pixels. */
    val left: Float = 0f,
    /** The position of the top of the container in its window coordinate system, in pixels. */
    val top: Float = 0f,
    /** The position of the right of the container in its window coordinate system, in pixels. */
    val right: Float = 0f,
    /** The position of the bottom of the container in its window coordinate system, in pixels. */
    val bottom: Float = 0f,
    /** Whether any modifications to top/bottom should be smoothly animated. */
    val isAnimated: Boolean = false,
) {
    /** The current height of the notification container. */
    val height: Float = bottom - top
}
