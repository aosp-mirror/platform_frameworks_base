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

package com.android.systemui.bouncer.ui.viewmodel

import android.app.admin.devicePolicyManager
import android.content.applicationContext
import com.android.systemui.authentication.domain.interactor.authenticationInteractor
import com.android.systemui.bouncer.domain.interactor.bouncerActionButtonInteractor
import com.android.systemui.bouncer.domain.interactor.bouncerInteractor
import com.android.systemui.bouncer.domain.interactor.simBouncerInteractor
import com.android.systemui.bouncer.shared.flag.composeBouncerFlags
import com.android.systemui.inputmethod.domain.interactor.inputMethodInteractor
import com.android.systemui.kosmos.Kosmos
import com.android.systemui.kosmos.Kosmos.Fixture
import com.android.systemui.kosmos.testDispatcher
import com.android.systemui.kosmos.testScope
import com.android.systemui.user.domain.interactor.selectedUserInteractor
import com.android.systemui.user.ui.viewmodel.userSwitcherViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi

val Kosmos.bouncerViewModel by Fixture {
    BouncerViewModel(
        applicationContext = applicationContext,
        applicationScope = testScope.backgroundScope,
        mainDispatcher = testDispatcher,
        bouncerInteractor = bouncerInteractor,
        inputMethodInteractor = inputMethodInteractor,
        simBouncerInteractor = simBouncerInteractor,
        authenticationInteractor = authenticationInteractor,
        selectedUserInteractor = selectedUserInteractor,
        devicePolicyManager = devicePolicyManager,
        bouncerMessageViewModel = bouncerMessageViewModel,
        flags = composeBouncerFlags,
        selectedUser = userSwitcherViewModel.selectedUser,
        users = userSwitcherViewModel.users,
        userSwitcherMenu = userSwitcherViewModel.menu,
        actionButton = bouncerActionButtonInteractor.actionButton,
    )
}
