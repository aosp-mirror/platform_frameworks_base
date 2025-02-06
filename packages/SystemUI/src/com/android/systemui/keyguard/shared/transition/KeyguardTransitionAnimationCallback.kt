/*
 * Copyright (C) 2025 The Android Open Source Project
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
 * limitations under the License
 */
package com.android.systemui.keyguard.shared.transition

import com.android.systemui.keyguard.shared.model.KeyguardState

/**
 * Defines an interface for classes that can be notified when a keyguard transition starts, ends, or
 * is canceled.
 */
interface KeyguardTransitionAnimationCallback {

    /** Notifies that an animation from [from] to [to] has started. */
    fun onAnimationStarted(from: KeyguardState, to: KeyguardState)

    /** Notifies that an animation from [from] to [to] has ended. */
    fun onAnimationEnded(from: KeyguardState, to: KeyguardState)

    /** Notifies that an animation from [from] to [to] has been canceled. */
    fun onAnimationCanceled(from: KeyguardState, to: KeyguardState)
}
