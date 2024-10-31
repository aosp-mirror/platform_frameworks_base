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

package com.android.systemui.statusbar.events.shared.model

/** Direct representation of the system event animation scheduler's current state */
enum class SystemEventAnimationState {
    /** No animation is in progress */
    Idle,
    /** An animation is queued, and awaiting the debounce period */
    AnimationQueued,
    /** System is animating out, and chip is animating in */
    AnimatingIn,
    /**
     * Chip has animated in and is awaiting exit animation, and optionally playing its own animation
     */
    RunningChipAnim,
    /** Chip is animating away and system is animating back */
    AnimatingOut,
    /** Chip has animated away, and the persistent dot is showing */
    ShowingPersistentDot,
}
