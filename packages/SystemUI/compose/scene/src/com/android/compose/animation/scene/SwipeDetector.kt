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

package com.android.compose.animation.scene

import androidx.compose.runtime.Stable
import androidx.compose.ui.input.pointer.PointerInputChange

/** {@link SwipeDetector} helps determine whether a swipe gestured has occurred. */
@Stable
interface SwipeDetector {
    /**
     * Invoked on changes to pointer input. Returns {@code true} if a swipe has been recognized,
     * {@code false} otherwise.
     */
    fun detectSwipe(change: PointerInputChange): Boolean
}

val DefaultSwipeDetector = PassthroughSwipeDetector()

/** An {@link SwipeDetector} implementation that recognizes a swipe on any input. */
class PassthroughSwipeDetector : SwipeDetector {
    override fun detectSwipe(change: PointerInputChange): Boolean {
        // Simply accept all changes as a swipe
        return true
    }
}
