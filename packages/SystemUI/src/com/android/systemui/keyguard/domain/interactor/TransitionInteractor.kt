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

package com.android.systemui.keyguard.domain.interactor
/**
 * Each TransitionInteractor is responsible for determining under which conditions to notify
 * [KeyguardTransitionRepository] to signal a transition. When (and if) the transition occurs is
 * determined by [KeyguardTransitionRepository].
 *
 * [name] field should be a unique identifiable string representing this state, used primarily for
 * logging
 *
 * MUST list implementing classes in dagger module [StartKeyguardTransitionModule] and also in the
 * 'when' clause of [KeyguardTransitionCoreStartable]
 */
sealed class TransitionInteractor(val name: String) {

    abstract fun start()

    fun <A, B, C> toTriple(a: A, bc: Pair<B, C>) = Triple(a, bc.first, bc.second)

    fun <A, B, C> toTriple(ab: Pair<A, B>, c: C) = Triple(ab.first, ab.second, c)
}
