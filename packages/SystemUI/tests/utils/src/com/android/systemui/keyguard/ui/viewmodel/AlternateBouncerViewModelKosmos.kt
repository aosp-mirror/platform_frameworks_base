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

@file:OptIn(ExperimentalCoroutinesApi::class)

package com.android.systemui.keyguard.ui.viewmodel

import com.android.systemui.bouncer.domain.interactor.alternateBouncerInteractor
import com.android.systemui.bouncer.domain.interactor.primaryBouncerInteractor
import com.android.systemui.keyguard.dismissCallbackRegistry
import com.android.systemui.keyguard.domain.interactor.keyguardTransitionInteractor
import com.android.systemui.kosmos.Kosmos
import com.android.systemui.kosmos.Kosmos.Fixture
import com.android.systemui.statusbar.phone.statusBarKeyguardViewManager
import kotlinx.coroutines.ExperimentalCoroutinesApi

val Kosmos.alternateBouncerViewModel by Fixture {
    AlternateBouncerViewModel(
        statusBarKeyguardViewManager = statusBarKeyguardViewManager,
        keyguardTransitionInteractor = keyguardTransitionInteractor,
        dismissCallbackRegistry = dismissCallbackRegistry,
        alternateBouncerInteractor = { alternateBouncerInteractor },
        primaryBouncerInteractor = primaryBouncerInteractor,
    )
}
