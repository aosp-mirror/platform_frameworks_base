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

package com.android.systemui.communal.log

import com.android.compose.animation.scene.ObservableTransitionState
import com.android.internal.logging.UiEventLogger
import com.android.systemui.CoreStartable
import com.android.systemui.communal.domain.interactor.CommunalSceneInteractor
import com.android.systemui.communal.shared.log.CommunalUiEvent
import com.android.systemui.communal.shared.model.CommunalScenes
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.keyguard.domain.interactor.KeyguardInteractor
import com.android.systemui.util.kotlin.pairwise
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach

/** A [CoreStartable] responsible for logging metrics for the communal hub. */
@SysUISingleton
class CommunalLoggerStartable
@Inject
constructor(
    @Background private val backgroundScope: CoroutineScope,
    private val communalSceneInteractor: CommunalSceneInteractor,
    private val keyguardInteractor: KeyguardInteractor,
    private val uiEventLogger: UiEventLogger,
) : CoreStartable {

    override fun start() {
        communalSceneInteractor.transitionState
            .map { state ->
                when {
                    state.isOnCommunal() -> CommunalUiEvent.COMMUNAL_HUB_SHOWN
                    state.isNotOnCommunal() -> CommunalUiEvent.COMMUNAL_HUB_GONE
                    else -> null
                }
            }
            .filterNotNull()
            .distinctUntilChanged()
            // Drop the default value.
            .drop(1)
            .onEach { uiEvent -> uiEventLogger.log(uiEvent) }
            .launchIn(backgroundScope)

        communalSceneInteractor.transitionState
            .pairwise()
            .combine(keyguardInteractor.isDreamingWithOverlay) { (old, new), isDreaming ->
                when {
                    new.isOnCommunal() && old.isSwipingToCommunal() ->
                        if (isDreaming) {
                            CommunalUiEvent.DREAM_TO_COMMUNAL_HUB_SWIPE_FINISH
                        } else {
                            CommunalUiEvent.COMMUNAL_HUB_SWIPE_TO_ENTER_FINISH
                        }
                    new.isOnCommunal() && old.isSwipingFromCommunal() ->
                        if (isDreaming) {
                            CommunalUiEvent.COMMUNAL_HUB_TO_DREAM_SWIPE_CANCEL
                        } else {
                            CommunalUiEvent.COMMUNAL_HUB_SWIPE_TO_EXIT_CANCEL
                        }
                    new.isNotOnCommunal() && old.isSwipingFromCommunal() ->
                        if (isDreaming) {
                            CommunalUiEvent.COMMUNAL_HUB_TO_DREAM_SWIPE_FINISH
                        } else {
                            CommunalUiEvent.COMMUNAL_HUB_SWIPE_TO_EXIT_FINISH
                        }
                    new.isNotOnCommunal() && old.isSwipingToCommunal() ->
                        if (isDreaming) {
                            CommunalUiEvent.DREAM_TO_COMMUNAL_HUB_SWIPE_CANCEL
                        } else {
                            CommunalUiEvent.COMMUNAL_HUB_SWIPE_TO_ENTER_CANCEL
                        }
                    new.isSwipingToCommunal() && old.isNotOnCommunal() ->
                        if (isDreaming) {
                            CommunalUiEvent.DREAM_TO_COMMUNAL_HUB_SWIPE_START
                        } else {
                            CommunalUiEvent.COMMUNAL_HUB_SWIPE_TO_ENTER_START
                        }
                    new.isSwipingFromCommunal() && old.isOnCommunal() ->
                        if (isDreaming) {
                            CommunalUiEvent.COMMUNAL_HUB_TO_DREAM_SWIPE_START
                        } else {
                            CommunalUiEvent.COMMUNAL_HUB_SWIPE_TO_EXIT_START
                        }
                    else -> null
                }
            }
            .filterNotNull()
            .distinctUntilChanged()
            .onEach { uiEvent -> uiEventLogger.log(uiEvent) }
            .launchIn(backgroundScope)
    }
}

/** Whether currently in communal scene. */
private fun ObservableTransitionState.isOnCommunal(): Boolean {
    return this is ObservableTransitionState.Idle && currentScene == CommunalScenes.Communal
}

/** Whether currently in a scene other than communal. */
private fun ObservableTransitionState.isNotOnCommunal(): Boolean {
    return this is ObservableTransitionState.Idle && currentScene != CommunalScenes.Communal
}

/** Whether currently transitioning from another scene to communal. */
private fun ObservableTransitionState.isSwipingToCommunal(): Boolean {
    return this is ObservableTransitionState.Transition &&
        toContent == CommunalScenes.Communal &&
        isInitiatedByUserInput
}

/** Whether currently transitioning from communal to another scene. */
private fun ObservableTransitionState.isSwipingFromCommunal(): Boolean {
    return this is ObservableTransitionState.Transition &&
        fromContent == CommunalScenes.Communal &&
        isInitiatedByUserInput
}
