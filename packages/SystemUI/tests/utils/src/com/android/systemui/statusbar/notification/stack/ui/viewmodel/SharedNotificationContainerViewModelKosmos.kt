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

package com.android.systemui.statusbar.notification.stack.ui.viewmodel

import com.android.systemui.communal.domain.interactor.communalInteractor
import com.android.systemui.dump.dumpManager
import com.android.systemui.keyguard.domain.interactor.keyguardInteractor
import com.android.systemui.keyguard.domain.interactor.keyguardTransitionInteractor
import com.android.systemui.keyguard.ui.viewmodel.alternateBouncerToGoneTransitionViewModel
import com.android.systemui.keyguard.ui.viewmodel.aodBurnInViewModel
import com.android.systemui.keyguard.ui.viewmodel.aodToLockscreenTransitionViewModel
import com.android.systemui.keyguard.ui.viewmodel.dozingToLockscreenTransitionViewModel
import com.android.systemui.keyguard.ui.viewmodel.dreamingToLockscreenTransitionViewModel
import com.android.systemui.keyguard.ui.viewmodel.glanceableHubToLockscreenTransitionViewModel
import com.android.systemui.keyguard.ui.viewmodel.goneToAodTransitionViewModel
import com.android.systemui.keyguard.ui.viewmodel.goneToDozingTransitionViewModel
import com.android.systemui.keyguard.ui.viewmodel.goneToDreamingTransitionViewModel
import com.android.systemui.keyguard.ui.viewmodel.lockscreenToDreamingTransitionViewModel
import com.android.systemui.keyguard.ui.viewmodel.lockscreenToGlanceableHubTransitionViewModel
import com.android.systemui.keyguard.ui.viewmodel.lockscreenToGoneTransitionViewModel
import com.android.systemui.keyguard.ui.viewmodel.lockscreenToOccludedTransitionViewModel
import com.android.systemui.keyguard.ui.viewmodel.lockscreenToPrimaryBouncerTransitionViewModel
import com.android.systemui.keyguard.ui.viewmodel.occludedToAodTransitionViewModel
import com.android.systemui.keyguard.ui.viewmodel.occludedToLockscreenTransitionViewModel
import com.android.systemui.keyguard.ui.viewmodel.primaryBouncerToGoneTransitionViewModel
import com.android.systemui.keyguard.ui.viewmodel.primaryBouncerToLockscreenTransitionViewModel
import com.android.systemui.kosmos.Kosmos
import com.android.systemui.kosmos.Kosmos.Fixture
import com.android.systemui.kosmos.applicationCoroutineScope
import com.android.systemui.shade.domain.interactor.shadeInteractor
import com.android.systemui.statusbar.notification.stack.domain.interactor.sharedNotificationContainerInteractor
import kotlinx.coroutines.ExperimentalCoroutinesApi

@OptIn(ExperimentalCoroutinesApi::class)
val Kosmos.sharedNotificationContainerViewModel by Fixture {
    SharedNotificationContainerViewModel(
        interactor = sharedNotificationContainerInteractor,
        dumpManager = dumpManager,
        applicationScope = applicationCoroutineScope,
        keyguardInteractor = keyguardInteractor,
        keyguardTransitionInteractor = keyguardTransitionInteractor,
        shadeInteractor = shadeInteractor,
        communalInteractor = communalInteractor,
        alternateBouncerToGoneTransitionViewModel = alternateBouncerToGoneTransitionViewModel,
        aodToLockscreenTransitionViewModel = aodToLockscreenTransitionViewModel,
        dozingToLockscreenTransitionViewModel = dozingToLockscreenTransitionViewModel,
        dreamingToLockscreenTransitionViewModel = dreamingToLockscreenTransitionViewModel,
        goneToAodTransitionViewModel = goneToAodTransitionViewModel,
        goneToDozingTransitionViewModel = goneToDozingTransitionViewModel,
        goneToDreamingTransitionViewModel = goneToDreamingTransitionViewModel,
        glanceableHubToLockscreenTransitionViewModel = glanceableHubToLockscreenTransitionViewModel,
        lockscreenToDreamingTransitionViewModel = lockscreenToDreamingTransitionViewModel,
        lockscreenToGlanceableHubTransitionViewModel = lockscreenToGlanceableHubTransitionViewModel,
        lockscreenToGoneTransitionViewModel = lockscreenToGoneTransitionViewModel,
        lockscreenToOccludedTransitionViewModel = lockscreenToOccludedTransitionViewModel,
        lockscreenToPrimaryBouncerTransitionViewModel =
            lockscreenToPrimaryBouncerTransitionViewModel,
        occludedToAodTransitionViewModel = occludedToAodTransitionViewModel,
        occludedToLockscreenTransitionViewModel = occludedToLockscreenTransitionViewModel,
        primaryBouncerToGoneTransitionViewModel = primaryBouncerToGoneTransitionViewModel,
        primaryBouncerToLockscreenTransitionViewModel =
            primaryBouncerToLockscreenTransitionViewModel,
        aodBurnInViewModel = aodBurnInViewModel,
    )
}
