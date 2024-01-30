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

package com.android.systemui.statusbar

import android.content.testableContext
import com.android.systemui.classifier.falsingCollector
import com.android.systemui.classifier.falsingManager
import com.android.systemui.dump.dumpManager
import com.android.systemui.keyguard.domain.interactor.naturalScrollingSettingObserver
import com.android.systemui.keyguard.wakefulnessLifecycle
import com.android.systemui.kosmos.Kosmos
import com.android.systemui.kosmos.Kosmos.Fixture
import com.android.systemui.media.controls.ui.mediaHierarchyManager
import com.android.systemui.plugins.activityStarter
import com.android.systemui.power.domain.interactor.powerInteractor
import com.android.systemui.shade.data.repository.shadeRepository
import com.android.systemui.shade.domain.interactor.shadeInteractor
import com.android.systemui.statusbar.notification.stack.ambientState
import com.android.systemui.statusbar.phone.keyguardBypassController
import com.android.systemui.statusbar.phone.lsShadeTransitionLogger
import com.android.systemui.statusbar.policy.configurationController
import com.android.systemui.statusbar.policy.splitShadeStateController

val Kosmos.lockscreenShadeTransitionController by Fixture {
    LockscreenShadeTransitionController(
        statusBarStateController = sysuiStatusBarStateController,
        logger = lsShadeTransitionLogger,
        keyguardBypassController = keyguardBypassController,
        lockScreenUserManager = notificationLockscreenUserManager,
        falsingCollector = falsingCollector,
        ambientState = ambientState,
        mediaHierarchyManager = mediaHierarchyManager,
        scrimTransitionController = lockscreenShadeScrimTransitionController,
        keyguardTransitionControllerFactory = lockscreenShadeKeyguardTransitionControllerFactory,
        depthController = notificationShadeDepthController,
        context = testableContext,
        splitShadeOverScrollerFactory = splitShadeLockScreenOverScrollerFactory,
        singleShadeOverScrollerFactory = singleShadeLockScreenOverScrollerFactory,
        activityStarter = activityStarter,
        wakefulnessLifecycle = wakefulnessLifecycle,
        configurationController = configurationController,
        falsingManager = falsingManager,
        dumpManager = dumpManager,
        qsTransitionControllerFactory = lockscreenShadeQsTransitionControllerFactory,
        shadeRepository = shadeRepository,
        shadeInteractor = shadeInteractor,
        powerInteractor = powerInteractor,
        splitShadeStateController = splitShadeStateController,
        naturalScrollingSettingObserver = naturalScrollingSettingObserver,
    )
}
