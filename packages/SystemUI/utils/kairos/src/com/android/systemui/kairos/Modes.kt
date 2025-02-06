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

package com.android.systemui.kairos

/**
 * A modal Kairos sub-network.
 *
 * When [enabled][enableMode], all network modifications are applied immediately to the Kairos
 * network. When the returned [Events] emits a [BuildMode], that mode is enabled and replaces this
 * mode, undoing all modifications in the process (any registered [observers][BuildScope.observe]
 * are unregistered, and any pending [side-effects][BuildScope.effect] are cancelled).
 *
 * Use [compiledBuildSpec] to compile and stand-up a mode graph.
 *
 * @see StatefulMode
 */
@ExperimentalKairosApi
fun interface BuildMode<out A> {
    /**
     * Invoked when this mode is enabled. Returns a value and an [Events] that signals a switch to a
     * new mode.
     */
    fun BuildScope.enableMode(): Pair<A, Events<BuildMode<A>>>
}

/**
 * Returns a [BuildSpec] that, when [applied][BuildScope.applySpec], stands up a modal-transition
 * graph starting with this [BuildMode], automatically switching to new modes as they are produced.
 *
 * @see BuildMode
 */
@ExperimentalKairosApi
val <A> BuildMode<A>.compiledBuildSpec: BuildSpec<State<A>>
    get() = buildSpec {
        var modeChangeEvents by EventsLoop<BuildMode<A>>()
        val activeMode: State<Pair<A, Events<BuildMode<A>>>> =
            modeChangeEvents
                .map { it.run { buildSpec { enableMode() } } }
                .holdLatestSpec(buildSpec { enableMode() })
        modeChangeEvents =
            activeMode
                .map { statefully { it.second.nextOnly() } }
                .applyLatestStateful()
                .switchEvents()
        activeMode.map { it.first }
    }

/**
 * A modal Kairos sub-network.
 *
 * When [enabled][enableMode], all state accumulation is immediately started. When the returned
 * [Events] emits a [BuildMode], that mode is enabled and replaces this mode, stopping all state
 * accumulation in the process.
 *
 * Use [compiledStateful] to compile and stand-up a mode graph.
 *
 * @see BuildMode
 */
@ExperimentalKairosApi
fun interface StatefulMode<out A> {
    /**
     * Invoked when this mode is enabled. Returns a value and an [Events] that signals a switch to a
     * new mode.
     */
    fun StateScope.enableMode(): Pair<A, Events<StatefulMode<A>>>
}

/**
 * Returns a [Stateful] that, when [applied][StateScope.applyStateful], stands up a modal-transition
 * graph starting with this [StatefulMode], automatically switching to new modes as they are
 * produced.
 *
 * @see StatefulMode
 */
@ExperimentalKairosApi
val <A> StatefulMode<A>.compiledStateful: Stateful<State<A>>
    get() = statefully {
        var modeChangeEvents by EventsLoop<StatefulMode<A>>()
        val activeMode: State<Pair<A, Events<StatefulMode<A>>>> =
            modeChangeEvents
                .map { it.run { statefully { enableMode() } } }
                .holdLatestStateful(statefully { enableMode() })
        modeChangeEvents =
            activeMode
                .map { statefully { it.second.nextOnly() } }
                .applyLatestStateful()
                .switchEvents()
        activeMode.map { it.first }
    }
