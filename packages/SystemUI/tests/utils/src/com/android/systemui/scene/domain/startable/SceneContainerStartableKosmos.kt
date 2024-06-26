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

package com.android.systemui.scene.domain.startable

import com.android.internal.logging.uiEventLogger
import com.android.systemui.authentication.domain.interactor.authenticationInteractor
import com.android.systemui.bouncer.domain.interactor.bouncerInteractor
import com.android.systemui.bouncer.domain.interactor.simBouncerInteractor
import com.android.systemui.classifier.falsingCollector
import com.android.systemui.classifier.falsingManager
import com.android.systemui.deviceentry.domain.interactor.deviceEntryFaceAuthInteractor
import com.android.systemui.deviceentry.domain.interactor.deviceEntryInteractor
import com.android.systemui.deviceentry.domain.interactor.deviceUnlockedInteractor
import com.android.systemui.keyguard.dismissCallbackRegistry
import com.android.systemui.keyguard.domain.interactor.keyguardEnabledInteractor
import com.android.systemui.keyguard.domain.interactor.keyguardInteractor
import com.android.systemui.keyguard.domain.interactor.windowManagerLockscreenVisibilityInteractor
import com.android.systemui.kosmos.Kosmos
import com.android.systemui.kosmos.Kosmos.Fixture
import com.android.systemui.kosmos.testScope
import com.android.systemui.model.sysUiState
import com.android.systemui.power.domain.interactor.powerInteractor
import com.android.systemui.scene.domain.interactor.sceneBackInteractor
import com.android.systemui.scene.domain.interactor.sceneContainerOcclusionInteractor
import com.android.systemui.scene.domain.interactor.sceneInteractor
import com.android.systemui.scene.session.shared.shadeSessionStorage
import com.android.systemui.scene.shared.logger.sceneLogger
import com.android.systemui.settings.displayTracker
import com.android.systemui.shade.domain.interactor.shadeInteractor
import com.android.systemui.statusbar.notification.stack.domain.interactor.headsUpNotificationInteractor
import com.android.systemui.statusbar.notificationShadeWindowController
import com.android.systemui.statusbar.phone.centralSurfacesOptional
import com.android.systemui.statusbar.policy.domain.interactor.deviceProvisioningInteractor

val Kosmos.sceneContainerStartable by Fixture {
    SceneContainerStartable(
        applicationScope = testScope.backgroundScope,
        sceneInteractor = sceneInteractor,
        deviceEntryInteractor = deviceEntryInteractor,
        deviceUnlockedInteractor = deviceUnlockedInteractor,
        bouncerInteractor = bouncerInteractor,
        keyguardInteractor = keyguardInteractor,
        sysUiState = sysUiState,
        displayId = displayTracker.defaultDisplayId,
        sceneLogger = sceneLogger,
        falsingCollector = falsingCollector,
        falsingManager = falsingManager,
        powerInteractor = powerInteractor,
        simBouncerInteractor = { simBouncerInteractor },
        authenticationInteractor = { authenticationInteractor },
        windowController = notificationShadeWindowController,
        deviceProvisioningInteractor = deviceProvisioningInteractor,
        centralSurfacesOptLazy = { centralSurfacesOptional },
        headsUpInteractor = headsUpNotificationInteractor,
        occlusionInteractor = sceneContainerOcclusionInteractor,
        faceUnlockInteractor = deviceEntryFaceAuthInteractor,
        shadeInteractor = shadeInteractor,
        uiEventLogger = uiEventLogger,
        sceneBackInteractor = sceneBackInteractor,
        shadeSessionStorage = shadeSessionStorage,
        windowMgrLockscreenVisInteractor = windowManagerLockscreenVisibilityInteractor,
        keyguardEnabledInteractor = keyguardEnabledInteractor,
        dismissCallbackRegistry = dismissCallbackRegistry,
    )
}
