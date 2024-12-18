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
import com.android.systemui.authentication.shared.model.AuthenticationMethodModel
import com.android.systemui.bouncer.domain.interactor.bouncerActionButtonInteractor
import com.android.systemui.bouncer.domain.interactor.bouncerInteractor
import com.android.systemui.bouncer.domain.interactor.simBouncerInteractor
import com.android.systemui.bouncer.ui.helper.BouncerHapticPlayer
import com.android.systemui.haptics.msdl.bouncerHapticPlayer
import com.android.systemui.inputmethod.domain.interactor.inputMethodInteractor
import com.android.systemui.kosmos.Kosmos
import com.android.systemui.kosmos.Kosmos.Fixture
import com.android.systemui.user.domain.interactor.selectedUserInteractor
import com.android.systemui.user.ui.viewmodel.userSwitcherViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.StateFlow

val Kosmos.bouncerUserActionsViewModel by Fixture {
    BouncerUserActionsViewModel(bouncerInteractor = bouncerInteractor)
}

val Kosmos.bouncerUserActionsViewModelFactory by Fixture {
    object : BouncerUserActionsViewModel.Factory {
        override fun create(): BouncerUserActionsViewModel {
            return bouncerUserActionsViewModel
        }
    }
}

val Kosmos.bouncerSceneContentViewModel by Fixture {
    BouncerSceneContentViewModel(
        applicationContext = applicationContext,
        bouncerInteractor = bouncerInteractor,
        authenticationInteractor = authenticationInteractor,
        devicePolicyManager = devicePolicyManager,
        bouncerMessageViewModelFactory = bouncerMessageViewModelFactory,
        userSwitcher = userSwitcherViewModel,
        actionButtonInteractor = bouncerActionButtonInteractor,
        pinViewModelFactory = pinBouncerViewModelFactory,
        patternViewModelFactory = patternBouncerViewModelFactory,
        passwordViewModelFactory = passwordBouncerViewModelFactory,
        bouncerHapticPlayer = bouncerHapticPlayer,
    )
}

val Kosmos.bouncerSceneContentViewModelFactory by Fixture {
    object : BouncerSceneContentViewModel.Factory {
        override fun create(): BouncerSceneContentViewModel {
            return bouncerSceneContentViewModel
        }
    }
}

val Kosmos.pinBouncerViewModelFactory by Fixture {
    object : PinBouncerViewModel.Factory {
        override fun create(
            isInputEnabled: StateFlow<Boolean>,
            onIntentionalUserInput: () -> Unit,
            authenticationMethod: AuthenticationMethodModel,
            bouncerHapticPlayer: BouncerHapticPlayer,
        ): PinBouncerViewModel {
            return PinBouncerViewModel(
                applicationContext = applicationContext,
                interactor = bouncerInteractor,
                simBouncerInteractor = simBouncerInteractor,
                isInputEnabled = isInputEnabled,
                onIntentionalUserInput = onIntentionalUserInput,
                authenticationMethod = authenticationMethod,
                bouncerHapticPlayer = bouncerHapticPlayer,
            )
        }
    }
}

val Kosmos.patternBouncerViewModelFactory by Fixture {
    object : PatternBouncerViewModel.Factory {
        override fun create(
            bouncerHapticPlayer: BouncerHapticPlayer,
            isInputEnabled: StateFlow<Boolean>,
            onIntentionalUserInput: () -> Unit,
        ): PatternBouncerViewModel {
            return PatternBouncerViewModel(
                applicationContext = applicationContext,
                interactor = bouncerInteractor,
                isInputEnabled = isInputEnabled,
                onIntentionalUserInput = onIntentionalUserInput,
                bouncerHapticPlayer = bouncerHapticPlayer,
            )
        }
    }
}

val Kosmos.passwordBouncerViewModelFactory by Fixture {
    object : PasswordBouncerViewModel.Factory {
        override fun create(
            isInputEnabled: StateFlow<Boolean>,
            onIntentionalUserInput: () -> Unit,
        ): PasswordBouncerViewModel {
            return PasswordBouncerViewModel(
                interactor = bouncerInteractor,
                inputMethodInteractor = inputMethodInteractor,
                selectedUserInteractor = selectedUserInteractor,
                isInputEnabled = isInputEnabled,
                onIntentionalUserInput = onIntentionalUserInput,
            )
        }
    }
}
