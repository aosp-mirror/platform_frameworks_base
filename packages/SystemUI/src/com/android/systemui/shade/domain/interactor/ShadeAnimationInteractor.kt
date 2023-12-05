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

package com.android.systemui.shade.domain.interactor

import kotlinx.coroutines.flow.Flow

/** Business logic related to shade animations and transitions. */
interface ShadeAnimationInteractor {
    /**
     * Whether a short animation to close the shade or QS is running. This will be false if the user
     * is manually closing the shade or QS but true if they lift their finger and an animation
     * completes the close. Important: if QS is collapsing back to shade, this will be false because
     * that is not considered "closing".
     */
    val isAnyCloseAnimationRunning: Flow<Boolean>
}
