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

package com.android.systemui.scene.ui.view

import android.view.View
import androidx.compose.runtime.getValue
import com.android.compose.animation.scene.ContentKey
import com.android.internal.jank.Cuj
import com.android.internal.jank.Cuj.CujType
import com.android.internal.jank.InteractionJankMonitor
import com.android.systemui.authentication.domain.interactor.AuthenticationInteractor
import com.android.systemui.authentication.shared.model.AuthenticationMethodModel
import com.android.systemui.deviceentry.domain.interactor.DeviceUnlockedInteractor
import com.android.systemui.lifecycle.ExclusiveActivatable
import com.android.systemui.lifecycle.Hydrator
import com.android.systemui.scene.shared.model.Scenes
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject

/**
 * Monitors scene transitions and reports the beginning and ending of each scene-related CUJ.
 *
 * This general-purpose monitor can be expanded to include other rules that respond to the beginning
 * and/or ending of transitions and reports jank CUI markers to the [InteractionJankMonitor].
 */
class SceneJankMonitor
@AssistedInject
constructor(
    authenticationInteractor: AuthenticationInteractor,
    private val deviceUnlockedInteractor: DeviceUnlockedInteractor,
    private val interactionJankMonitor: InteractionJankMonitor,
) : ExclusiveActivatable() {

    private val hydrator = Hydrator("SceneJankMonitor.hydrator")
    private val authMethod: AuthenticationMethodModel? by
        hydrator.hydratedStateOf(
            traceName = "authMethod",
            initialValue = null,
            source = authenticationInteractor.authenticationMethod,
        )

    override suspend fun onActivated(): Nothing {
        hydrator.activate()
    }

    /**
     * Notifies that a transition is at its start.
     *
     * Should be called exactly once each time a new transition starts.
     */
    fun onTransitionStart(view: View, from: ContentKey, to: ContentKey, @CujType cuj: Int?) {
        cuj.orCalculated(from, to) { nonNullCuj -> interactionJankMonitor.begin(view, nonNullCuj) }
    }

    /**
     * Notifies that the previous transition is at its end.
     *
     * Should be called exactly once each time a transition ends.
     */
    fun onTransitionEnd(from: ContentKey, to: ContentKey, @CujType cuj: Int?) {
        cuj.orCalculated(from, to) { nonNullCuj -> interactionJankMonitor.end(nonNullCuj) }
    }

    /**
     * Returns this CUI marker (CUJ identifier), one that's calculated based on other state, or
     * `null`, if no appropriate CUJ could be calculated.
     */
    private fun Int?.orCalculated(
        from: ContentKey,
        to: ContentKey,
        ifNotNull: (nonNullCuj: Int) -> Unit,
    ) {
        val thisOrCalculatedCuj = this ?: calculatedCuj(from = from, to = to)

        if (thisOrCalculatedCuj != null) {
            ifNotNull(thisOrCalculatedCuj)
        }
    }

    @CujType
    private fun calculatedCuj(from: ContentKey, to: ContentKey): Int? {
        val isDeviceUnlocked = deviceUnlockedInteractor.deviceUnlockStatus.value.isUnlocked
        return when {
            to == Scenes.Bouncer ->
                when (authMethod) {
                    is AuthenticationMethodModel.Pin,
                    is AuthenticationMethodModel.Sim -> Cuj.CUJ_LOCKSCREEN_PIN_APPEAR
                    is AuthenticationMethodModel.Pattern -> Cuj.CUJ_LOCKSCREEN_PATTERN_APPEAR
                    is AuthenticationMethodModel.Password -> Cuj.CUJ_LOCKSCREEN_PASSWORD_APPEAR
                    is AuthenticationMethodModel.None -> null
                    null -> null
                }
            from == Scenes.Bouncer && isDeviceUnlocked ->
                when (authMethod) {
                    is AuthenticationMethodModel.Pin,
                    is AuthenticationMethodModel.Sim -> Cuj.CUJ_LOCKSCREEN_PIN_DISAPPEAR
                    is AuthenticationMethodModel.Pattern -> Cuj.CUJ_LOCKSCREEN_PATTERN_DISAPPEAR
                    is AuthenticationMethodModel.Password -> Cuj.CUJ_LOCKSCREEN_PASSWORD_DISAPPEAR
                    is AuthenticationMethodModel.None -> null
                    null -> null
                }
            else -> null
        }
    }

    @AssistedFactory
    interface Factory {
        fun create(): SceneJankMonitor
    }
}
