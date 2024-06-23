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

@file:OptIn(ExperimentalCoroutinesApi::class)

package com.android.systemui.scene.domain.startable

import android.os.DeadObjectException
import android.os.RemoteException
import com.android.internal.policy.IKeyguardStateCallback
import com.android.systemui.CoreStartable
import com.android.systemui.bouncer.domain.interactor.SimBouncerInteractor
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.deviceentry.domain.interactor.DeviceEntryInteractor
import com.android.systemui.keyguard.domain.interactor.TrustInteractor
import com.android.systemui.scene.domain.interactor.SceneInteractor
import com.android.systemui.scene.shared.flag.SceneContainerFlag
import com.android.systemui.scene.shared.model.Scenes
import com.android.systemui.user.domain.interactor.SelectedUserInteractor
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Keeps all [IKeyguardStateCallback]s hydrated with the latest state. */
@SysUISingleton
class KeyguardStateCallbackStartable
@Inject
constructor(
    @Application private val applicationScope: CoroutineScope,
    @Background private val backgroundDispatcher: CoroutineDispatcher,
    private val sceneInteractor: SceneInteractor,
    private val selectedUserInteractor: SelectedUserInteractor,
    private val deviceEntryInteractor: DeviceEntryInteractor,
    private val simBouncerInteractor: SimBouncerInteractor,
    private val trustInteractor: TrustInteractor,
) : CoreStartable {

    private val callbacks = mutableListOf<IKeyguardStateCallback>()

    override fun start() {
        if (!SceneContainerFlag.isEnabled) {
            return
        }

        hydrateKeyguardShowingAndInputRestrictionStates()
        hydrateSimSecureState()
        notifyWhenKeyguardShowingChanged()
        notifyWhenTrustChanged()
    }

    fun addCallback(callback: IKeyguardStateCallback) {
        SceneContainerFlag.assertInNewMode()

        callbacks.add(callback)

        applicationScope.launch(backgroundDispatcher) {
            callback.onShowingStateChanged(
                !deviceEntryInteractor.isDeviceEntered.value,
                selectedUserInteractor.getSelectedUserId(),
            )
            callback.onTrustedChanged(trustInteractor.isTrusted.value)
            callback.onSimSecureStateChanged(simBouncerInteractor.isAnySimSecure.value)
            // TODO(b/348644111): add support for mNeedToReshowWhenReenabled
            callback.onInputRestrictedStateChanged(!deviceEntryInteractor.isDeviceEntered.value)
        }
    }

    private fun hydrateKeyguardShowingAndInputRestrictionStates() {
        applicationScope.launch {
            combine(
                    selectedUserInteractor.selectedUser,
                    deviceEntryInteractor.isDeviceEntered,
                    ::Pair
                )
                .collectLatest { (selectedUserId, isDeviceEntered) ->
                    val iterator = callbacks.iterator()
                    withContext(backgroundDispatcher) {
                        while (iterator.hasNext()) {
                            val callback = iterator.next()
                            try {
                                callback.onShowingStateChanged(!isDeviceEntered, selectedUserId)
                                // TODO(b/348644111): add support for mNeedToReshowWhenReenabled
                                callback.onInputRestrictedStateChanged(!isDeviceEntered)
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

    private fun hydrateSimSecureState() {
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

    private fun notifyWhenKeyguardShowingChanged() {
        applicationScope.launch {
            // This is equivalent to isDeviceEntered but it waits for the full transition animation
            // to finish before emitting a new value and not just for the current scene to be
            // switched.
            sceneInteractor.transitionState
                .filter { it.isIdle(Scenes.Gone) || it.isIdle(Scenes.Lockscreen) }
                .map { it.isIdle(Scenes.Lockscreen) }
                .distinctUntilChanged()
                .collectLatest { trustInteractor.reportKeyguardShowingChanged() }
        }
    }

    private fun notifyWhenTrustChanged() {
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
    }
}
