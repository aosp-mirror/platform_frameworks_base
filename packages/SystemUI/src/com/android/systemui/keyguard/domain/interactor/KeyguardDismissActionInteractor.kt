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

import com.android.keyguard.logging.KeyguardLogger
import com.android.systemui.bouncer.domain.interactor.PrimaryBouncerInteractor
import com.android.systemui.bouncer.shared.flag.ComposeBouncerFlags
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.deviceentry.domain.interactor.DeviceUnlockedInteractor
import com.android.systemui.keyguard.data.repository.KeyguardRepository
import com.android.systemui.keyguard.shared.model.DismissAction
import com.android.systemui.keyguard.shared.model.KeyguardDone
import com.android.systemui.keyguard.shared.model.KeyguardState.GONE
import com.android.systemui.lifecycle.ExclusiveActivatable
import com.android.systemui.log.core.LogLevel
import com.android.systemui.scene.domain.interactor.SceneInteractor
import com.android.systemui.scene.shared.flag.SceneContainerFlag
import com.android.systemui.scene.shared.model.Scenes
import com.android.systemui.shade.domain.interactor.ShadeInteractor
import dagger.Lazy
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

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
    shadeInteractor: Lazy<ShadeInteractor>,
    keyguardInteractor: Lazy<KeyguardInteractor>,
    sceneInteractor: Lazy<SceneInteractor>,
    private val keyguardLogger: KeyguardLogger,
    private val primaryBouncerInteractor: PrimaryBouncerInteractor,
) : ExclusiveActivatable() {
    private val dismissAction: Flow<DismissAction> = repository.dismissAction

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
            // Using sceneInteractor instead of transitionInteractor because of a race
            // condition that forms between transitionInteractor (transitionState) and
            // isOnShadeWhileUnlocked where the latter emits false before the former emits
            // true, causing the merge to not emit until it's too late.
            sceneInteractor
                .get()
                .currentScene
                .map { it == Scenes.Gone }
                .distinctUntilChanged()
                .filter { it }
                .map {}
        } else {
            transitionInteractor
                .isFinishedIn(scene = Scenes.Gone, stateWithoutSceneContainer = GONE)
                .filter { it }
                .map {}
        }

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

    /** Launch any relevant coroutines that are required by this interactor. */
    override suspend fun onActivated(): Nothing {
        coroutineScope {
            launch {
                merge(finishedTransitionToGone, isOnShadeWhileUnlocked.filter { it }.map {})
                    .collect {
                        log("finishedTransitionToGone")
                        runDismissAction()
                    }
            }

            launch {
                dismissInteractor.dismissKeyguardRequestWithImmediateDismissAction.collect {
                    log("eventsThatRequireKeyguardDismissal")
                    runDismissAction()
                }
            }

            launch { repository.dismissAction.collect { log("updatedDismissAction=$it") } }
            awaitCancellation()
        }
    }

    fun clearDismissAction() {
        repository.setDismissAction(DismissAction.None)
    }

    /** Run the dismiss action and starts the dismiss keyguard transition. */
    private suspend fun runDismissAction() {
        val dismissAction = repository.dismissAction.value
        var keyguardDoneTiming: KeyguardDone = KeyguardDone.IMMEDIATE
        if (dismissAction != DismissAction.None) {
            keyguardDoneTiming = dismissAction.onDismissAction.invoke()
            dismissInteractor.setKeyguardDone(keyguardDoneTiming)
            clearDismissAction()
        }
        if (!SceneContainerFlag.isEnabled) {
            // This is required to reset some state flows in the repository which ideally should be
            // sharedFlows but are not due to performance concerns.
            primaryBouncerInteractor.notifyKeyguardAuthenticatedHandled()
        }
    }

    private fun log(message: String) {
        keyguardLogger.log(TAG, LogLevel.DEBUG, message)
    }

    companion object {
        private const val TAG = "KeyguardDismissAction"
    }
}
