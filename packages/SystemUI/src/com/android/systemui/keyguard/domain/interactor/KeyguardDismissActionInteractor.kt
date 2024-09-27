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

import com.android.systemui.bouncer.domain.interactor.AlternateBouncerInteractor
import com.android.systemui.bouncer.shared.flag.ComposeBouncerFlags
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.deviceentry.domain.interactor.DeviceUnlockedInteractor
import com.android.systemui.keyguard.data.repository.KeyguardRepository
import com.android.systemui.keyguard.shared.model.DismissAction
import com.android.systemui.keyguard.shared.model.KeyguardDone
import com.android.systemui.keyguard.shared.model.KeyguardState.GONE
import com.android.systemui.keyguard.shared.model.KeyguardState.PRIMARY_BOUNCER
import com.android.systemui.power.domain.interactor.PowerInteractor
import com.android.systemui.scene.shared.flag.SceneContainerFlag
import com.android.systemui.scene.shared.model.Scenes
import com.android.systemui.shade.domain.interactor.ShadeInteractor
import com.android.systemui.util.kotlin.Utils.Companion.sampleFilter
import com.android.systemui.util.kotlin.sample
import dagger.Lazy
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNot
import kotlinx.coroutines.flow.flow
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
    deviceUnlockedInteractor: Lazy<DeviceUnlockedInteractor>,
    powerInteractor: PowerInteractor,
    alternateBouncerInteractor: AlternateBouncerInteractor,
    shadeInteractor: Lazy<ShadeInteractor>,
    keyguardInteractor: Lazy<KeyguardInteractor>,
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
        transitionInteractor
            .isFinishedIn(scene = Scenes.Gone, stateWithoutSceneContainer = GONE)
            .filter { it }
            .map {}

    /**
     * True if the any variation of the notification shade or quick settings is showing AND the
     * device is unlocked. Else, false.
     */
    private val isOnShadeWhileUnlocked: Flow<Boolean> =
        if (SceneContainerFlag.isEnabled) {
            combine(
                    shadeInteractor.get().isAnyExpanded,
                    deviceUnlockedInteractor.get().deviceUnlockStatus,
                ) { isAnyExpanded, unlockStatus ->
                    isAnyExpanded && unlockStatus.isUnlocked
                }
                .distinctUntilChanged()
        } else if (ComposeBouncerFlags.isOnlyComposeBouncerEnabled()) {
            combine(
                    shadeInteractor.get().isAnyExpanded,
                    keyguardInteractor.get().isKeyguardDismissible,
                ) { isAnyExpanded, keyguardDismissible ->
                    isAnyExpanded && keyguardDismissible
                }
                .distinctUntilChanged()
        } else {
            flow {
                error(
                    "This should not be used when both SceneContainerFlag " +
                        "and ComposeBouncerFlag are disabled"
                )
            }
        }

    val executeDismissAction: Flow<() -> KeyguardDone> =
        merge(
                if (SceneContainerFlag.isEnabled) {
                    // Using currentScene instead of finishedTransitionToGone because of a race
                    // condition that forms between finishedTransitionToGone and
                    // isOnShadeWhileUnlocked where the latter emits false before the former emits
                    // true, causing the merge to not emit until it's too late.
                    sceneInteractor
                        .get()
                        .currentScene
                        .map { it == Scenes.Gone }
                        .distinctUntilChanged()
                        .filter { it }
                } else {
                    finishedTransitionToGone
                },
                isOnShadeWhileUnlocked.filter { it }.map {},
                dismissInteractor.dismissKeyguardRequestWithImmediateDismissAction,
            )
            .sample(dismissAction)
            .filterNot { it is DismissAction.None }
            .map { it.onDismissAction }

    val resetDismissAction: Flow<Unit> =
        combine(
                if (SceneContainerFlag.isEnabled) {
                    // Using currentScene instead of isFinishedIn because of a race condition that
                    // forms between isFinishedIn(Gone) and isOnShadeWhileUnlocked where the latter
                    // emits false before the former emits true, causing the evaluation of the
                    // combine to come up with true, temporarily, before settling on false, which is
                    // a valid final state. That causes an incorrect reset of the dismiss action to
                    // occur before it gets executed.
                    sceneInteractor
                        .get()
                        .currentScene
                        .map { it == Scenes.Gone }
                        .distinctUntilChanged()
                } else {
                    transitionInteractor.isFinishedIn(
                        scene = Scenes.Gone,
                        stateWithoutSceneContainer = GONE,
                    )
                },
                transitionInteractor.isFinishedIn(
                    scene = Scenes.Bouncer,
                    stateWithoutSceneContainer = PRIMARY_BOUNCER,
                ),
                alternateBouncerInteractor.isVisible,
                isOnShadeWhileUnlocked,
                powerInteractor.isAsleep,
            ) { isOnGone, isOnBouncer, isOnAltBouncer, isOnShadeWhileUnlocked, isAsleep ->
                (!isOnGone && !isOnBouncer && !isOnAltBouncer && !isOnShadeWhileUnlocked) ||
                    isAsleep
            }
            .filter { it }
            .sampleFilter(dismissAction) { it !is DismissAction.None }
            .map {}

    fun runDismissAnimationOnKeyguard(): Boolean {
        return willAnimateDismissActionOnLockscreen.value
    }

    fun runAfterKeyguardGone(runnable: Runnable) {
        if (ComposeBouncerFlags.isUnexpectedlyInLegacyMode()) return
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
        if (ComposeBouncerFlags.isUnexpectedlyInLegacyMode()) return
        repository.dismissAction.value.onCancelAction.run()
        repository.setDismissAction(dismissAction)
    }

    fun handleDismissAction() {
        if (ComposeBouncerFlags.isUnexpectedlyInLegacyMode()) return
        repository.setDismissAction(DismissAction.None)
    }

    suspend fun setKeyguardDone(keyguardDoneTiming: KeyguardDone) {
        if (ComposeBouncerFlags.isUnexpectedlyInLegacyMode()) return
        dismissInteractor.setKeyguardDone(keyguardDoneTiming)
    }
}
