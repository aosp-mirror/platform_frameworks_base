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

import com.android.systemui.communal.domain.interactor.communalInteractor
import com.android.systemui.deviceentry.domain.interactor.deviceEntryInteractor
import com.android.systemui.keyguard.domain.interactor.keyguardInteractor
import com.android.systemui.keyguard.domain.interactor.keyguardTransitionInteractor
import com.android.systemui.kosmos.Kosmos
import com.android.systemui.kosmos.Kosmos.Fixture
import com.android.systemui.statusbar.notification.stack.domain.interactor.notificationsKeyguardInteractor
import com.android.systemui.statusbar.phone.dozeParameters
import com.android.systemui.statusbar.phone.screenOffAnimationController
import kotlinx.coroutines.ExperimentalCoroutinesApi

val Kosmos.keyguardRootViewModel by Fixture {
    KeyguardRootViewModel(
        deviceEntryInteractor = deviceEntryInteractor,
        dozeParameters = dozeParameters,
        keyguardInteractor = keyguardInteractor,
        communalInteractor = communalInteractor,
        keyguardTransitionInteractor = keyguardTransitionInteractor,
        notificationsKeyguardInteractor = notificationsKeyguardInteractor,
        aodToLockscreenTransitionViewModel = aodToLockscreenTransitionViewModel,
        lockscreenToGoneTransitionViewModel = lockscreenToGoneTransitionViewModel,
        alternateBouncerToGoneTransitionViewModel = alternateBouncerToGoneTransitionViewModel,
        primaryBouncerToGoneTransitionViewModel = primaryBouncerToGoneTransitionViewModel,
        screenOffAnimationController = screenOffAnimationController,
        aodBurnInViewModel = aodBurnInViewModel,
        aodAlphaViewModel = aodAlphaViewModel,
        lockscreenToGlanceableHubTransitionViewModel = lockscreenToGlanceableHubTransitionViewModel,
        glanceableHubToLockscreenTransitionViewModel = glanceableHubToLockscreenTransitionViewModel,
    )
}
