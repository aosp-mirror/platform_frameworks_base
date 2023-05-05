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
 */

package com.android.systemui.bouncer.ui.viewmodel

import com.android.systemui.authentication.shared.model.AuthenticationMethodModel
import com.android.systemui.bouncer.domain.interactor.BouncerInteractor
import com.android.systemui.dagger.qualifiers.Application
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** Holds UI state and handles user input on bouncer UIs. */
class BouncerViewModel
@AssistedInject
constructor(
    @Application private val applicationScope: CoroutineScope,
    interactorFactory: BouncerInteractor.Factory,
    containerName: String,
) {
    private val interactor: BouncerInteractor = interactorFactory.create(containerName)

    /** The user-facing message to show in the bouncer. */
    val message: StateFlow<String> =
        interactor.message
            .map { it ?: "" }
            .stateIn(
                scope = applicationScope,
                started = SharingStarted.WhileSubscribed(),
                initialValue = interactor.message.value ?: "",
            )

    /** Notifies that the authenticate button was clicked. */
    fun onAuthenticateButtonClicked() {
        // TODO(b/280877228): remove this and send the real input.
        interactor.authenticate(
            when (interactor.authenticationMethod.value) {
                is AuthenticationMethodModel.PIN -> listOf(1, 2, 3, 4)
                is AuthenticationMethodModel.Password -> "password".toList()
                is AuthenticationMethodModel.Pattern ->
                    listOf(
                        AuthenticationMethodModel.Pattern.PatternCoordinate(2, 0),
                        AuthenticationMethodModel.Pattern.PatternCoordinate(2, 1),
                        AuthenticationMethodModel.Pattern.PatternCoordinate(2, 2),
                        AuthenticationMethodModel.Pattern.PatternCoordinate(1, 1),
                        AuthenticationMethodModel.Pattern.PatternCoordinate(0, 0),
                        AuthenticationMethodModel.Pattern.PatternCoordinate(0, 1),
                        AuthenticationMethodModel.Pattern.PatternCoordinate(0, 2),
                    )
                else -> emptyList()
            }
        )
    }

    /** Notifies that the emergency services button was clicked. */
    fun onEmergencyServicesButtonClicked() {
        // TODO(b/280877228): implement this.
    }
}
