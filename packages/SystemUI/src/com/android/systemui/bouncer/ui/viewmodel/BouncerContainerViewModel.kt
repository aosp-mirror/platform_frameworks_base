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

package com.android.systemui.bouncer.ui.viewmodel

import androidx.compose.runtime.getValue
import com.android.keyguard.ViewMediatorCallback
import com.android.systemui.authentication.domain.interactor.AuthenticationInteractor
import com.android.systemui.bouncer.domain.interactor.PrimaryBouncerInteractor
import com.android.systemui.lifecycle.ExclusiveActivatable
import com.android.systemui.lifecycle.Hydrator
import com.android.systemui.user.domain.interactor.SelectedUserInteractor
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

class BouncerContainerViewModel
@AssistedInject
constructor(
    private val legacyInteractor: PrimaryBouncerInteractor,
    private val authenticationInteractor: AuthenticationInteractor,
    private val selectedUserInteractor: SelectedUserInteractor,
    private val viewMediatorCallback: ViewMediatorCallback?,
) : ExclusiveActivatable() {

    private val hydrator = Hydrator("BouncerContainerViewModel")

    val isVisible: Boolean by
        hydrator.hydratedStateOf(traceName = "isVisible", source = legacyInteractor.isShowing)

    override suspend fun onActivated(): Nothing {
        coroutineScope {
            launch {
                authenticationInteractor.onAuthenticationResult.collect { authenticationSucceeded ->
                    if (authenticationSucceeded) {
                        // Some dismiss actions require that keyguard be dismissed right away or
                        // deferred until something else later on dismisses keyguard (eg. end of
                        // a hide animation).
                        val deferKeyguardDone =
                            legacyInteractor.bouncerDismissAction?.onDismissAction?.onDismiss()
                        legacyInteractor.setDismissAction(null, null)

                        viewMediatorCallback?.let {
                            val selectedUserId = selectedUserInteractor.getSelectedUserId()
                            if (deferKeyguardDone == true) {
                                it.keyguardDonePending(selectedUserId)
                            } else {
                                it.keyguardDone(selectedUserId)
                            }
                        }
                    }
                }
            }

            launch {
                legacyInteractor.startingDisappearAnimation.collect {
                    it.run()
                    legacyInteractor.hide()
                }
            }

            hydrator.activate()
        }
    }

    @AssistedFactory
    interface Factory {
        fun create(): BouncerContainerViewModel
    }
}
