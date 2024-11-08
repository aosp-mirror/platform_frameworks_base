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
 *
 */

package com.android.systemui.user.domain.interactor

import com.android.app.tracing.coroutines.launchTraced as launch
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.user.data.repository.UserRepository
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/** Encapsulates business logic to for the logout. */
@SysUISingleton
class UserLogoutInteractor
@Inject
constructor(
    private val userRepository: UserRepository,
    @Application private val applicationScope: CoroutineScope,
) {

    val isLogoutEnabled: StateFlow<Boolean> =
        combine(
                userRepository.isSecondaryUserLogoutEnabled,
                userRepository.isLogoutToSystemUserEnabled,
                Boolean::or,
            )
            .stateIn(applicationScope, SharingStarted.Eagerly, false)

    fun logOut() {
        applicationScope.launch {
            if (userRepository.isSecondaryUserLogoutEnabled.value) {
                userRepository.logOutSecondaryUser()
            } else if (userRepository.isLogoutToSystemUserEnabled.value) {
                userRepository.logOutToSystemUser()
            }
        }
    }
}
