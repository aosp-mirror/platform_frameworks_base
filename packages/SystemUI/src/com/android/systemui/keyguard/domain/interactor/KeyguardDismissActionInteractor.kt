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
 *
 */

package com.android.systemui.keyguard.domain.interactor

import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.keyguard.data.repository.KeyguardRepository
import com.android.systemui.keyguard.shared.model.DismissAction
import com.android.systemui.keyguard.shared.model.KeyguardDone
import com.android.systemui.keyguard.shared.model.KeyguardState.ALTERNATE_BOUNCER
import com.android.systemui.keyguard.shared.model.KeyguardState.GONE
import com.android.systemui.keyguard.shared.model.KeyguardState.PRIMARY_BOUNCER
import com.android.systemui.scene.domain.interactor.SceneInteractor
import com.android.systemui.scene.shared.flag.SceneContainerFlag
import com.android.systemui.scene.shared.model.Scenes
import com.android.systemui.util.kotlin.sample
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNot
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.stateIn

/** Encapsulates business-logic for actions to run when the keyguard is dismissed. */
@ExperimentalCoroutinesApi
@SysUISingleton
class KeyguardDismissActionInteractor
@Inject
constructor(
    private val repository: KeyguardRepository,
    transitionInteractor: KeyguardTransitionInteractor,
    val dismissInteractor: KeyguardDismissInteractor,
    @Application private val applicationScope: CoroutineScope,
    sceneInteractor: SceneInteractor,
) {
    val dismissAction: Flow<DismissAction> = repository.dismissAction

    val onCancel: Flow<Runnable> = dismissAction.map { it.onCancelAction }

    // TODO (b/268240415): use message in alt + primary bouncer message
    // message to show to the user about the dismiss action, else empty string
    val message = dismissAction.map { it.message }

    /**
     * True if the dismiss action will run an animation on the lockscreen and requires any views
     * that would obscure this animation (ie: the primary bouncer) to immediately hide, so the
     * animation would be visible.
     */
    val willAnimateDismissActionOnLockscreen: StateFlow<Boolean> =
        dismissAction
            .map { it.willAnimateOnLockscreen }
            .stateIn(
                scope = applicationScope,
                started = SharingStarted.WhileSubscribed(),
                initialValue = false,
            )

    private val finishedTransitionToGone: Flow<Unit> =
        if (SceneContainerFlag.isEnabled) {
            sceneInteractor.transitionState.filter { it.isIdle(Scenes.Gone) }.map {}
        } else {
            transitionInteractor.finishedKeyguardState.filter { it == GONE }.map {}
        }

    val executeDismissAction: Flow<() -> KeyguardDone> =
        merge(
                finishedTransitionToGone,
                dismissInteractor.dismissKeyguardRequestWithImmediateDismissAction
            )
            .sample(dismissAction)
            .filterNot { it is DismissAction.None }
            .map { it.onDismissAction }
    val resetDismissAction: Flow<Unit> =
        transitionInteractor.finishedKeyguardTransitionStep
            .filter { it.to != ALTERNATE_BOUNCER && it.to != PRIMARY_BOUNCER && it.to != GONE }
            .sample(dismissAction)
            .filterNot { it is DismissAction.None }
            .map {} // map to Unit

    fun runDismissAnimationOnKeyguard(): Boolean {
        return willAnimateDismissActionOnLockscreen.value
    }

    fun runAfterKeyguardGone(runnable: Runnable) {
        setDismissAction(
            DismissAction.RunAfterKeyguardGone(
                dismissAction = { runnable.run() },
                onCancelAction = {},
                message = "",
                willAnimateOnLockscreen = false,
            )
        )
    }

    fun setDismissAction(dismissAction: DismissAction) {
        repository.dismissAction.value.onCancelAction.run()
        repository.setDismissAction(dismissAction)
    }

    fun handleDismissAction() {
        repository.setDismissAction(DismissAction.None)
    }

    suspend fun setKeyguardDone(keyguardDoneTiming: KeyguardDone) {
        dismissInteractor.setKeyguardDone(keyguardDoneTiming)
    }
}
