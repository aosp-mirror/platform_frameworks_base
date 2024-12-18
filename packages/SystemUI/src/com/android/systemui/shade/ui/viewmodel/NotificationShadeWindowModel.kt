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

import com.android.systemui.authentication.domain.interactor.AuthenticationInteractor
import com.android.systemui.authentication.shared.model.AuthenticationMethodModel
import com.android.systemui.bouncer.domain.interactor.PrimaryBouncerInteractor
import com.android.systemui.bouncer.shared.flag.ComposeBouncerFlags
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.keyguard.domain.interactor.KeyguardTransitionInteractor
import com.android.systemui.keyguard.shared.model.Edge
import com.android.systemui.keyguard.shared.model.KeyguardState.DREAMING
import com.android.systemui.keyguard.shared.model.KeyguardState.GLANCEABLE_HUB
import com.android.systemui.keyguard.shared.model.KeyguardState.OCCLUDED
import com.android.systemui.scene.domain.interactor.SceneInteractor
import com.android.systemui.scene.shared.flag.SceneContainerFlag
import com.android.systemui.scene.shared.model.Scenes
import com.android.systemui.util.kotlin.BooleanFlowOperators.any
import com.android.systemui.util.kotlin.sample
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map

/** Models UI state for the shade window. */
@SysUISingleton
class NotificationShadeWindowModel
@Inject
constructor(
    keyguardTransitionInteractor: KeyguardTransitionInteractor,
    sceneInteractor: dagger.Lazy<SceneInteractor>,
    authenticationInteractor: dagger.Lazy<AuthenticationInteractor>,
    primaryBouncerInteractor: PrimaryBouncerInteractor,
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

    /**
     * Whether bouncer is currently showing or not.
     *
     * Applicable only when either [SceneContainerFlag] or [ComposeBouncerFlags] are enabled,
     * otherwise it throws an error.
     */
    val isBouncerShowing: Flow<Boolean> =
        when {
            SceneContainerFlag.isEnabled -> {
                sceneInteractor.get().transitionState.map { it.isIdle(Scenes.Bouncer) }
            }
            ComposeBouncerFlags.isOnlyComposeBouncerEnabled() -> primaryBouncerInteractor.isShowing
            else ->
                flow {
                    error(
                        "Consume this flow only when SceneContainerFlag " +
                            "or ComposeBouncerFlags are enabled"
                    )
                }
        }.distinctUntilChanged()

    /**
     * Whether the bouncer currently require IME for device entry.
     *
     * This emits true when the authentication method is set to password and the bouncer is
     * currently showing. Throws an error when this is used without either [SceneContainerFlag] or
     * [ComposeBouncerFlags]
     */
    val doesBouncerRequireIme: Flow<Boolean> =
        if (ComposeBouncerFlags.isComposeBouncerOrSceneContainerEnabled()) {
                // This is required to make the window, where the bouncer resides,
                // focusable. InputMethodManager allows IME to be shown only for views
                // in windows that do not have the FLAG_NOT_FOCUSABLE flag.

                isBouncerShowing
                    .sample(authenticationInteractor.get().authenticationMethod, ::Pair)
                    .map { (showing, authMethod) ->
                        showing && authMethod == AuthenticationMethodModel.Password
                    }
            } else {
                flow {
                    error(
                        "Consume this flow only when SceneContainerFlag " +
                            "or ComposeBouncerFlags are enabled"
                    )
                }
            }
            .distinctUntilChanged()
}
