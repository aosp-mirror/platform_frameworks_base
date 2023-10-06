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
 *
 */

package com.android.systemui.multishade.shared.model

import androidx.annotation.FloatRange

/**
 * Models a part of an ongoing proxied user input gesture.
 *
 * "Proxied" user input is coming through a proxy; typically from an external app or different UI.
 * In other words: it's not user input that's occurring directly on the shade UI itself.
 */
sealed class ProxiedInputModel {
    /** The user is dragging their pointer. */
    data class OnDrag(
        /**
         * The relative position of the pointer as a fraction of its container width where `0` is
         * all the way to the left and `1` is all the way to the right.
         */
        @FloatRange(from = 0.0, to = 1.0) val xFraction: Float,
        /** The amount that the pointer was dragged, in pixels. */
        val yDragAmountPx: Float,
    ) : ProxiedInputModel()

    /** The user finished dragging by lifting up their pointer. */
    object OnDragEnd : ProxiedInputModel()

    /**
     * The drag gesture has been canceled. Usually because the pointer exited the draggable area.
     */
    object OnDragCancel : ProxiedInputModel()

    /** The user has tapped (clicked). */
    object OnTap : ProxiedInputModel()
}
