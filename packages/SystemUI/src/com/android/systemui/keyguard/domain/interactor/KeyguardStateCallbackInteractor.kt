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

package com.android.systemui.keyguard.domain.interactor

import android.app.trust.TrustManager
import android.os.DeadObjectException
import android.os.RemoteException
import com.android.internal.policy.IKeyguardStateCallback
import com.android.systemui.CoreStartable
import com.android.systemui.bouncer.domain.interactor.SimBouncerInteractor
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.keyguard.DismissCallbackRegistry
import com.android.systemui.keyguard.KeyguardWmStateRefactor
import com.android.systemui.keyguard.shared.model.KeyguardState
import com.android.systemui.scene.shared.flag.SceneContainerFlag
import com.android.systemui.user.domain.interactor.SelectedUserInteractor
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Updates KeyguardStateCallbacks provided to KeyguardService with KeyguardTransitionInteractor
 * state.
 *
 * This borrows heavily from [KeyguardStateCallbackStartable], which requires Flexiglass. This class
 * can be removed after Flexiglass launches.
 */
@SysUISingleton
class KeyguardStateCallbackInteractor
@Inject
constructor(
    @Application private val applicationScope: CoroutineScope,
    @Background private val backgroundDispatcher: CoroutineDispatcher,
    private val selectedUserInteractor: SelectedUserInteractor,
    private val keyguardTransitionInteractor: KeyguardTransitionInteractor,
    private val trustInteractor: TrustInteractor,
    private val simBouncerInteractor: SimBouncerInteractor,
    private val dismissCallbackRegistry: DismissCallbackRegistry,
    private val wmLockscreenVisibilityInteractor: WindowManagerLockscreenVisibilityInteractor,
    private val trustManager: TrustManager,
) : CoreStartable {
    private val callbacks = mutableListOf<IKeyguardStateCallback>()

    override fun start() {
        if (!KeyguardWmStateRefactor.isEnabled || SceneContainerFlag.isEnabled) {
            return
        }

        applicationScope.launch {
            wmLockscreenVisibilityInteractor.lockscreenVisibility.collectLatest { visible ->
                val iterator = callbacks.iterator()
                withContext(backgroundDispatcher) {
                    while (iterator.hasNext()) {
                        val callback = iterator.next()
                        try {
                            callback.onShowingStateChanged(
                                visible,
                                selectedUserInteractor.getSelectedUserId(),
                            )
                            callback.onInputRestrictedStateChanged(visible)

                            trustManager.reportKeyguardShowingChanged()

                            if (!visible) {
                                dismissCallbackRegistry.notifyDismissSucceeded()
                            }
                        } catch (e: RemoteException) {
                            if (e is DeadObjectException) {
                                iterator.remove()
                            }
                        }
                    }
                }
            }
        }

        applicationScope.launch {
            trustInteractor.isTrusted.collectLatest { isTrusted ->
                val iterator = callbacks.iterator()
                withContext(backgroundDispatcher) {
                    while (iterator.hasNext()) {
                        val callback = iterator.next()
                        try {
                            callback.onTrustedChanged(isTrusted)
                        } catch (e: RemoteException) {
                            if (e is DeadObjectException) {
                                iterator.remove()
                            }
                        }
                    }
                }
            }
        }

        applicationScope.launch {
            simBouncerInteractor.isAnySimSecure.collectLatest { isSimSecured ->
                val iterator = callbacks.iterator()
                withContext(backgroundDispatcher) {
                    while (iterator.hasNext()) {
                        val callback = iterator.next()
                        try {
                            callback.onSimSecureStateChanged(isSimSecured)
                        } catch (e: RemoteException) {
                            if (e is DeadObjectException) {
                                iterator.remove()
                            }
                        }
                    }
                }
            }
        }
    }

    fun addCallback(callback: IKeyguardStateCallback) {
        KeyguardWmStateRefactor.isUnexpectedlyInLegacyMode()
        callbacks.add(callback)

        // Send initial values to new callbacks.
        callback.onShowingStateChanged(!isIdleInGone(), selectedUserInteractor.getSelectedUserId())
        callback.onInputRestrictedStateChanged(!isIdleInGone())
        callback.onTrustedChanged(trustInteractor.isTrusted.value)
        callback.onSimSecureStateChanged(simBouncerInteractor.isAnySimSecure.value)
    }

    /** Whether we're in KeyguardState.GONE and haven't started a transition to another state. */
    private fun isIdleInGone(): Boolean {
        return keyguardTransitionInteractor.getCurrentState() == KeyguardState.GONE &&
            keyguardTransitionInteractor.getStartedState() == KeyguardState.GONE
    }
}
