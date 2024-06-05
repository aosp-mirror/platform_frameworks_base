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

/** This information will flow from the [KeyguardTransitionRepository] to control the UI layer */
data class TransitionStep
@JvmOverloads
constructor(
    val from: KeyguardState = KeyguardState.OFF,
    val to: KeyguardState = KeyguardState.OFF,
    val value: Float = 0f, // constrained [0.0, 1.0]
    val transitionState: TransitionState = TransitionState.FINISHED,
    val ownerName: String = "",
) {
    constructor(
        info: TransitionInfo,
        value: Float,
        transitionState: TransitionState,
    ) : this(info.from, info.to, value, transitionState, info.ownerName)

    fun isTransitioning(from: KeyguardState? = null, to: KeyguardState? = null): Boolean {
        return (from == null || this.from == from) && (to == null || this.to == to)
    }

    fun isFinishedIn(state: KeyguardState): Boolean {
        return to == state && transitionState == TransitionState.FINISHED
    }
}
