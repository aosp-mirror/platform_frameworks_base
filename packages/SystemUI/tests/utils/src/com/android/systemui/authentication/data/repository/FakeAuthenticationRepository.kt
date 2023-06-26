/*
 * Copyright 2023 The Android Open Source Project
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

package com.android.systemui.authentication.data.repository

import com.android.keyguard.KeyguardSecurityModel.SecurityMode
import com.android.systemui.authentication.shared.model.AuthenticationMethodModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class FakeAuthenticationRepository(
    private val delegate: AuthenticationRepository,
    private val onSecurityModeChanged: (SecurityMode) -> Unit,
) : AuthenticationRepository by delegate {

    private val _isUnlocked = MutableStateFlow(false)
    override val isUnlocked: StateFlow<Boolean> = _isUnlocked.asStateFlow()

    private var authenticationMethod: AuthenticationMethodModel = DEFAULT_AUTHENTICATION_METHOD

    override suspend fun getAuthenticationMethod(): AuthenticationMethodModel {
        return authenticationMethod
    }

    fun setAuthenticationMethod(authenticationMethod: AuthenticationMethodModel) {
        this.authenticationMethod = authenticationMethod
        onSecurityModeChanged(authenticationMethod.toSecurityMode())
    }

    fun setUnlocked(isUnlocked: Boolean) {
        _isUnlocked.value = isUnlocked
    }

    companion object {
        val DEFAULT_AUTHENTICATION_METHOD =
            AuthenticationMethodModel.Pin(listOf(1, 2, 3, 4), autoConfirm = false)

        fun AuthenticationMethodModel.toSecurityMode(): SecurityMode {
            return when (this) {
                is AuthenticationMethodModel.Pin -> SecurityMode.PIN
                is AuthenticationMethodModel.Password -> SecurityMode.Password
                is AuthenticationMethodModel.Pattern -> SecurityMode.Pattern
                is AuthenticationMethodModel.Swipe,
                is AuthenticationMethodModel.None -> SecurityMode.None
            }
        }
    }
}
