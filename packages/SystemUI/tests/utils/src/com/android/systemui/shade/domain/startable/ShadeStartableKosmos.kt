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

package com.android.systemui.shade.domain.startable

import android.content.applicationContext
import com.android.systemui.common.ui.data.repository.configurationRepository
import com.android.systemui.kosmos.Kosmos
import com.android.systemui.kosmos.Kosmos.Fixture
import com.android.systemui.kosmos.applicationCoroutineScope
import com.android.systemui.log.LogBuffer
import com.android.systemui.scene.domain.interactor.sceneInteractor
import com.android.systemui.shade.ShadeExpansionStateManager
import com.android.systemui.shade.data.repository.shadeRepository
import com.android.systemui.shade.domain.interactor.panelExpansionInteractor
import com.android.systemui.shade.transition.ScrimShadeTransitionController
import com.android.systemui.statusbar.notification.stack.notificationStackScrollLayoutController
import com.android.systemui.statusbar.policy.splitShadeStateController
import com.android.systemui.statusbar.pulseExpansionHandler
import com.android.systemui.util.mockito.mock

@Deprecated("ShadeExpansionStateManager is deprecated. Remove your dependency on it instead.")
val Kosmos.shadeExpansionStateManager by Fixture { ShadeExpansionStateManager() }

val Kosmos.shadeStartable by Fixture {
    ShadeStartable(
        applicationScope = applicationCoroutineScope,
        applicationContext = applicationContext,
        touchLog = mock<LogBuffer>(),
        configurationRepository = configurationRepository,
        shadeRepository = shadeRepository,
        splitShadeStateController = splitShadeStateController,
        scrimShadeTransitionController = mock<ScrimShadeTransitionController>(),
        sceneInteractorProvider = { sceneInteractor },
        panelExpansionInteractorProvider = { panelExpansionInteractor },
        shadeExpansionStateManager = shadeExpansionStateManager,
        pulseExpansionHandler = pulseExpansionHandler,
        nsslc = notificationStackScrollLayoutController,
    )
}
