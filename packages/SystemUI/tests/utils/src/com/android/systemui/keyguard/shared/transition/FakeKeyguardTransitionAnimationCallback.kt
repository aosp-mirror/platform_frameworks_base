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
 * limitations under the License.
 */

package com.android.systemui.keyguard.shared.transition

import com.android.systemui.keyguard.shared.model.KeyguardState

class FakeKeyguardTransitionAnimationCallback : KeyguardTransitionAnimationCallback {

    private val _activeAnimations = mutableListOf<TransitionAnimation>()
    /** The animations that have been started but not yet ended nor canceled. */
    val activeAnimations: List<TransitionAnimation>
        get() = _activeAnimations.toList()

    private val _finishedAnimations = mutableListOf<TransitionAnimation>()
    /** The animations that have ended. */
    val finishedAnimations: List<TransitionAnimation>
        get() = _finishedAnimations.toList()

    private val _canceledAnimations = mutableListOf<TransitionAnimation>()
    /** The animations that have been canceled. */
    val canceledAnimations: List<TransitionAnimation>
        get() = _canceledAnimations.toList()

    override fun onAnimationStarted(from: KeyguardState, to: KeyguardState) {
        _activeAnimations.add(TransitionAnimation(from = from, to = to))
    }

    override fun onAnimationEnded(from: KeyguardState, to: KeyguardState) {
        val animation = TransitionAnimation(from = from, to = to)
        check(_activeAnimations.remove(animation)) { "Ending an animation that wasn't started!" }
        _finishedAnimations.add(animation)
    }

    override fun onAnimationCanceled(from: KeyguardState, to: KeyguardState) {
        val animation = TransitionAnimation(from = from, to = to)
        check(_activeAnimations.remove(animation)) { "Canceling an animation that wasn't started!" }
        _canceledAnimations.add(animation)
    }

    data class TransitionAnimation(val from: KeyguardState, val to: KeyguardState)
}
