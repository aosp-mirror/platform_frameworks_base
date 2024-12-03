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

package com.android.systemui.keyguard.ui.viewmodel

import android.content.applicationContext
import com.android.systemui.common.ui.domain.interactor.configurationInteractor
import com.android.systemui.keyguard.domain.interactor.keyguardTransitionInteractor
import com.android.systemui.kosmos.Kosmos
import com.android.systemui.kosmos.Kosmos.Fixture
import kotlinx.coroutines.ExperimentalCoroutinesApi

@ExperimentalCoroutinesApi
val Kosmos.deviceEntryBackgroundViewModel by Fixture {
    DeviceEntryBackgroundViewModel(
        context = applicationContext,
        deviceEntryIconViewModel = deviceEntryIconViewModel,
        configurationInteractor = configurationInteractor,
        keyguardTransitionInteractor = keyguardTransitionInteractor,
        alternateBouncerToAodTransitionViewModel = alternateBouncerToAodTransitionViewModel,
        alternateBouncerToDozingTransitionViewModel = alternateBouncerToDozingTransitionViewModel,
        aodToLockscreenTransitionViewModel = aodToLockscreenTransitionViewModel,
        dozingToLockscreenTransitionViewModel = dozingToLockscreenTransitionViewModel,
        dreamingToAodTransitionViewModel = dreamingToAodTransitionViewModel,
        dreamingToLockscreenTransitionViewModel = dreamingToLockscreenTransitionViewModel,
        goneToAodTransitionViewModel = goneToAodTransitionViewModel,
        goneToDozingTransitionViewModel = goneToDozingTransitionViewModel,
        goneToLockscreenTransitionViewModel = goneToLockscreenTransitionViewModel,
        lockscreenToAodTransitionViewModel = lockscreenToAodTransitionViewModel,
        occludedToAodTransitionViewModel = occludedToAodTransitionViewModel,
        occludedToDozingTransitionViewModel = occludedToDozingTransitionViewModel,
        occludedToLockscreenTransitionViewModel = occludedToLockscreenTransitionViewModel,
        primaryBouncerToAodTransitionViewModel = primaryBouncerToAodTransitionViewModel,
        primaryBouncerToDozingTransitionViewModel = primaryBouncerToDozingTransitionViewModel,
        primaryBouncerToLockscreenTransitionViewModel =
            primaryBouncerToLockscreenTransitionViewModel,
        lockscreenToDozingTransitionViewModel = lockscreenToDozingTransitionViewModel,
    )
}
