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

package com.android.systemui.keyguard.ui.viewmodel

import com.android.systemui.keyguard.domain.interactor.KeyguardTransitionInteractor
import com.android.systemui.shade.domain.interactor.ShadeInteractor
import com.android.systemui.util.kotlin.sample
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge

/** Helper for flows that depend on the shade expansion */
class ShadeDependentFlows
@Inject
constructor(
    transitionInteractor: KeyguardTransitionInteractor,
    shadeInteractor: ShadeInteractor,
) {
    /** When the last keyguard state transition started, was the shade fully expanded? */
    private val lastStartedTransitionHadShadeFullyExpanded: Flow<Boolean> =
        transitionInteractor.startedKeyguardTransitionStep.sample(
            shadeInteractor.isAnyFullyExpanded
        )

    /**
     * Decide which flow to use depending on the shade expansion state at the start of the last
     * keyguard state transition.
     */
    fun <T> transitionFlow(
        flowWhenShadeIsExpanded: Flow<T>,
        flowWhenShadeIsNotExpanded: Flow<T>,
    ): Flow<T> {
        val filteredFlowWhenShadeIsExpanded =
            flowWhenShadeIsExpanded
                .sample(lastStartedTransitionHadShadeFullyExpanded, ::Pair)
                .filter { (_, shadeFullyExpanded) -> shadeFullyExpanded }
                .map { (valueWhenShadeIsExpanded, _) -> valueWhenShadeIsExpanded }
        val filteredFlowWhenShadeIsNotExpanded =
            flowWhenShadeIsNotExpanded
                .sample(lastStartedTransitionHadShadeFullyExpanded, ::Pair)
                .filter { (_, shadeFullyExpanded) -> !shadeFullyExpanded }
                .map { (valueWhenShadeIsNotExpanded, _) -> valueWhenShadeIsNotExpanded }
        return merge(filteredFlowWhenShadeIsExpanded, filteredFlowWhenShadeIsNotExpanded)
    }
}
