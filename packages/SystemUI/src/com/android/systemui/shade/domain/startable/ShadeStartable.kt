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

import android.content.Context
import com.android.systemui.CoreStartable
import com.android.systemui.common.ui.data.repository.ConfigurationRepository
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.log.LogBuffer
import com.android.systemui.log.dagger.ShadeTouchLog
import com.android.systemui.scene.domain.interactor.SceneInteractor
import com.android.systemui.scene.shared.flag.SceneContainerFlag
import com.android.systemui.shade.ShadeExpansionStateManager
import com.android.systemui.shade.TouchLogger.Companion.logTouchesTo
import com.android.systemui.shade.data.repository.ShadeRepository
import com.android.systemui.shade.domain.interactor.PanelExpansionInteractor
import com.android.systemui.shade.shared.flag.DualShade
import com.android.systemui.shade.shared.model.ShadeMode
import com.android.systemui.shade.transition.ScrimShadeTransitionController
import com.android.systemui.statusbar.policy.SplitShadeStateController
import javax.inject.Inject
import javax.inject.Provider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch

@SysUISingleton
class ShadeStartable
@Inject
constructor(
    @Application private val applicationScope: CoroutineScope,
    @Application private val applicationContext: Context,
    @ShadeTouchLog private val touchLog: LogBuffer,
    private val configurationRepository: ConfigurationRepository,
    private val shadeRepository: ShadeRepository,
    private val splitShadeStateController: SplitShadeStateController,
    private val scrimShadeTransitionController: ScrimShadeTransitionController,
    private val sceneInteractorProvider: Provider<SceneInteractor>,
    private val panelExpansionInteractorProvider: Provider<PanelExpansionInteractor>,
    private val shadeExpansionStateManager: ShadeExpansionStateManager,
) : CoreStartable {

    override fun start() {
        hydrateShadeMode()
        hydrateShadeExpansionStateManager()
        logTouchesTo(touchLog)
        scrimShadeTransitionController.init()
    }

    private fun hydrateShadeExpansionStateManager() {
        if (SceneContainerFlag.isEnabled) {
            combine(
                    panelExpansionInteractorProvider.get().legacyPanelExpansion,
                    sceneInteractorProvider.get().isTransitionUserInputOngoing,
                ) { panelExpansion, tracking ->
                    shadeExpansionStateManager.onPanelExpansionChanged(
                        fraction = panelExpansion,
                        expanded = panelExpansion > 0f,
                        tracking = tracking,
                    )
                }
                .launchIn(applicationScope)
        }
    }

    private fun hydrateShadeMode() {
        if (DualShade.isEnabled) {
            shadeRepository.setShadeMode(ShadeMode.Dual)
            return
        }
        applicationScope.launch {
            configurationRepository.onAnyConfigurationChange
                // Force initial collection.
                .onStart { emit(Unit) }
                .map { applicationContext.resources }
                .map { resources ->
                    splitShadeStateController.shouldUseSplitNotificationShade(resources)
                }
                .collect { isSplitShade ->
                    shadeRepository.setShadeMode(
                        if (isSplitShade) ShadeMode.Split else ShadeMode.Single
                    )
                }
        }
    }
}
