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

package com.android.systemui.shade.ui.viewmodel

import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.keyguard.domain.interactor.KeyguardTransitionInteractor
import com.android.systemui.keyguard.shared.model.Edge
import com.android.systemui.keyguard.shared.model.KeyguardState.DREAMING
import com.android.systemui.keyguard.shared.model.KeyguardState.GLANCEABLE_HUB
import com.android.systemui.keyguard.shared.model.KeyguardState.OCCLUDED
import com.android.systemui.scene.shared.model.Scenes
import com.android.systemui.util.kotlin.BooleanFlowOperators.any
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Models UI state for the shade window. */
@SysUISingleton
class NotificationShadeWindowModel
@Inject
constructor(
    keyguardTransitionInteractor: KeyguardTransitionInteractor,
) {
    /**
     * Considered to be occluded if in OCCLUDED, DREAMING, GLANCEABLE_HUB/Communal, or transitioning
     * between those states. Every permutation is listed so we can use optimal flows and support
     * Scenes.
     */
    val isKeyguardOccluded: Flow<Boolean> =
        listOf(
                // Finished in state...
                keyguardTransitionInteractor.transitionValue(OCCLUDED).map { it == 1f },
                keyguardTransitionInteractor.transitionValue(DREAMING).map { it == 1f },
                keyguardTransitionInteractor.transitionValue(Scenes.Communal, GLANCEABLE_HUB).map {
                    it == 1f
                },

                // ... or transitions between those states
                keyguardTransitionInteractor.isInTransition(Edge.create(OCCLUDED, DREAMING)),
                keyguardTransitionInteractor.isInTransition(Edge.create(DREAMING, OCCLUDED)),
                keyguardTransitionInteractor.isInTransition(
                    edge = Edge.create(from = OCCLUDED, to = Scenes.Communal),
                    edgeWithoutSceneContainer = Edge.create(from = OCCLUDED, to = GLANCEABLE_HUB),
                ),
                keyguardTransitionInteractor.isInTransition(
                    edge = Edge.create(from = Scenes.Communal, to = OCCLUDED),
                    edgeWithoutSceneContainer = Edge.create(from = GLANCEABLE_HUB, to = OCCLUDED),
                ),
                keyguardTransitionInteractor.isInTransition(
                    edge = Edge.create(from = DREAMING, to = Scenes.Communal),
                    edgeWithoutSceneContainer = Edge.create(from = DREAMING, to = GLANCEABLE_HUB),
                ),
                keyguardTransitionInteractor.isInTransition(
                    edge = Edge.create(from = Scenes.Communal, to = DREAMING),
                    edgeWithoutSceneContainer = Edge.create(from = GLANCEABLE_HUB, to = DREAMING),
                ),
            )
            .any()
}
