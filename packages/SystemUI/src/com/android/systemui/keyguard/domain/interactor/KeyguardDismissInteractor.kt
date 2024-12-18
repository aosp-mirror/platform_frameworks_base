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
 * limitations under the License
 */

package com.android.systemui.keyguard.domain.interactor

import com.android.internal.policy.IKeyguardDismissCallback
import com.android.systemui.bouncer.domain.interactor.AlternateBouncerInteractor
import com.android.systemui.bouncer.domain.interactor.PrimaryBouncerInteractor
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.keyguard.DismissCallbackRegistry
import com.android.systemui.keyguard.data.repository.KeyguardRepository
import com.android.systemui.keyguard.data.repository.TrustRepository
import com.android.systemui.keyguard.shared.model.DismissAction
import com.android.systemui.keyguard.shared.model.KeyguardDone
import com.android.systemui.power.domain.interactor.PowerInteractor
import com.android.systemui.user.domain.interactor.SelectedUserInteractor
import com.android.systemui.util.kotlin.Utils.Companion.toQuad
import com.android.systemui.util.kotlin.sample
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Encapsulates business logic for requesting the keyguard to dismiss/finish/done. */
@SysUISingleton
class KeyguardDismissInteractor
@Inject
constructor(
    @Main private val mainDispatcher: CoroutineDispatcher,
    @Application private val scope: CoroutineScope,
    private val keyguardRepository: KeyguardRepository,
    private val primaryBouncerInteractor: PrimaryBouncerInteractor,
    private val selectedUserInteractor: SelectedUserInteractor,
    private val dismissCallbackRegistry: DismissCallbackRegistry,
    trustRepository: TrustRepository,
    alternateBouncerInteractor: AlternateBouncerInteractor,
    powerInteractor: PowerInteractor,
) {
    /*
     * Updates when a biometric has authenticated the device and is requesting to dismiss
     * the keyguard. When true, a class 3 biometrics has authenticated. Else, a lower class
     * biometric strength has authenticated and is requesting to dismiss the keyguard.
     */
    private val biometricAuthenticatedRequestDismissKeyguard: Flow<Unit> =
        primaryBouncerInteractor.keyguardAuthenticatedBiometrics.map {} // map to Unit

    /*
     * Updates when a trust change is requesting to dismiss the keyguard and is able to do so
     * in the current device state.
     */
    private val onTrustGrantedRequestDismissKeyguard: Flow<Unit> =
        trustRepository.trustAgentRequestingToDismissKeyguard
            .sample(
                combine(
                    primaryBouncerInteractor.isShowing,
                    alternateBouncerInteractor.isVisible,
                    powerInteractor.isInteractive,
                    ::Triple,
                ),
                ::toQuad,
            )
            .filter { (trustModel, primaryBouncerShowing, altBouncerShowing, interactive) ->
                val bouncerShowing = primaryBouncerShowing || altBouncerShowing
                (interactive || trustModel.flags.temporaryAndRenewable()) &&
                    (bouncerShowing || trustModel.flags.dismissKeyguardRequested())
            }
            .map {} // map to Unit

    /*
     * Updates when the current user successfully has authenticated via primary authentication
     * (pin/pattern/password).
     */
    private val primaryAuthenticated: Flow<Unit> =
        primaryBouncerInteractor.keyguardAuthenticatedPrimaryAuth
            .filter { authedUserId -> authedUserId == selectedUserInteractor.getSelectedUserId() }
            .map {} // map to Unit

    /*
     * Updates when the current user requests the bouncer after they've already successfully
     * authenticated (ie: from non-bypass face auth, from a trust agent that didn't immediately
     * dismiss the keyguard, or if keyguard security is set to SWIPE or NONE).
     */
    private val userRequestedBouncerWhenAlreadyAuthenticated: Flow<Unit> =
        primaryBouncerInteractor.userRequestedBouncerWhenAlreadyAuthenticated
            .filter { authedUserId -> authedUserId == selectedUserInteractor.getSelectedUserId() }
            .map {} // map to Unit

    /** Updates when keyguardDone should be requested. */
    val keyguardDone: Flow<KeyguardDone> = keyguardRepository.keyguardDone

    /** Updates when any request to dismiss the current user's keyguard has arrived. */
    private val dismissKeyguardRequest: Flow<DismissAction> =
        merge(
                biometricAuthenticatedRequestDismissKeyguard,
                onTrustGrantedRequestDismissKeyguard,
                primaryAuthenticated,
                userRequestedBouncerWhenAlreadyAuthenticated,
            )
            .sample(keyguardRepository.dismissAction)

    /**
     * Updates when a request to dismiss the current user's keyguard has arrived and there's a
     * dismiss action to run immediately. It's expected that the consumer will request keyguardDone
     * with or without a deferral.
     */
    val dismissKeyguardRequestWithImmediateDismissAction: Flow<Unit> =
        dismissKeyguardRequest.filter { it is DismissAction.RunImmediately }.map {} // map to Unit

    /**
     * Updates when a request to dismiss the current user's keyguard has arrived and there's isn't a
     * dismiss action to run immediately. There may still be a dismiss action to run after the
     * keyguard transitions to GONE.
     */
    val dismissKeyguardRequestWithoutImmediateDismissAction: Flow<Unit> =
        dismissKeyguardRequest.filter { it !is DismissAction.RunImmediately }.map {} // map to Unit

    suspend fun setKeyguardDone(keyguardDoneTiming: KeyguardDone) {
        keyguardRepository.setKeyguardDone(keyguardDoneTiming)
    }

    /**
     * Dismiss the keyguard (or show the bouncer) and invoke the provided callback once dismissed.
     *
     * TODO(b/358412565): Support dismiss messages.
     */
    fun dismissKeyguardWithCallback(callback: IKeyguardDismissCallback?) {
        scope.launch {
            withContext(mainDispatcher) {
                if (callback != null) {
                    dismissCallbackRegistry.addCallback(callback)
                }

                // This will either show the bouncer, or dismiss the keyguard if insecure.
                // We currently need to request showing the primary bouncer in order to start a
                // transition to PRIMARY_BOUNCER. Once we refactor that so that starting the
                // transition is what causes the bouncer to show, we can remove this entire method,
                // and simply ask KeyguardTransitionInteractor to transition to a bouncer state or
                // dismiss keyguard.
                primaryBouncerInteractor.show(true)
            }
        }
    }
}
