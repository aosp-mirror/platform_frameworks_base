/*
 * Copyright (C) 2022 The Android Open Source Project
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
package com.android.systemui.keyguard.shared.model

import android.animation.ValueAnimator

/** Tracks who is controlling the current transition, and how to run it. */
data class TransitionInfo(
    val ownerName: String,
    val from: KeyguardState,
    val to: KeyguardState,
    /** [null] animator signals manual control, otherwise transition run by the animator */
    val animator: ValueAnimator?,
    /**
     * If the transition resets in the cancellation of another transition, use this mode to
     * determine how to continue.
     */
    val modeOnCanceled: TransitionModeOnCanceled = TransitionModeOnCanceled.LAST_VALUE,
) {
    override fun toString(): String =
        "TransitionInfo(ownerName=$ownerName, from=$from, to=$to, " +
            (if (animator != null) {
                "animated"
            } else {
                "manual"
            }) +
            ")"
}
