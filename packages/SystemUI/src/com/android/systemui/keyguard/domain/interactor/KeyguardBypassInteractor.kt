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

package com.android.systemui.keyguard.domain.interactor

import com.android.systemui.bouncer.domain.interactor.AlternateBouncerInteractor
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dump.DumpManager
import com.android.systemui.keyguard.data.repository.KeyguardBypassRepository
import com.android.systemui.scene.domain.interactor.SceneInteractor
import com.android.systemui.scene.shared.model.Scenes
import com.android.systemui.shade.domain.interactor.ShadeInteractor
import com.android.systemui.util.kotlin.FlowDumperImpl
import com.android.systemui.util.kotlin.combine
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

@SysUISingleton
class KeyguardBypassInteractor
@Inject
constructor(
    keyguardBypassRepository: KeyguardBypassRepository,
    alternateBouncerInteractor: AlternateBouncerInteractor,
    keyguardQuickAffordanceInteractor: KeyguardQuickAffordanceInteractor,
    pulseExpansionInteractor: PulseExpansionInteractor,
    sceneInteractor: SceneInteractor,
    shadeInteractor: ShadeInteractor,
    dumpManager: DumpManager,
) : FlowDumperImpl(dumpManager) {

    /**
     * Whether bypassing the keyguard is enabled by the user in user settings (skipping the
     * lockscreen when authenticating using secondary authentication types like face unlock).
     */
    val isBypassAvailable: Flow<Boolean> =
        keyguardBypassRepository.isBypassAvailable.dumpWhileCollecting("isBypassAvailable")

    /**
     * Models whether bypass is unavailable (no secondary authentication types enrolled), or if the
     * keyguard can be bypassed as a combination of the settings toggle value set by the user and
     * other factors related to device state.
     */
    val canBypass: Flow<Boolean> =
        isBypassAvailable
            .flatMapLatest { isBypassAvailable ->
                if (isBypassAvailable) {
                    combine(
                        sceneInteractor.currentScene.map { scene -> scene == Scenes.Bouncer },
                        alternateBouncerInteractor.isVisible,
                        sceneInteractor.currentScene.map { scene -> scene == Scenes.Lockscreen },
                        keyguardQuickAffordanceInteractor.launchingAffordance,
                        pulseExpansionInteractor.isPulseExpanding,
                        shadeInteractor.isQsExpanded,
                    ) {
                        isBouncerShowing,
                        isAlternateBouncerShowing,
                        isOnLockscreenScene,
                        isLaunchingAffordance,
                        isPulseExpanding,
                        isQsExpanded ->
                        when {
                            isBouncerShowing -> true
                            isAlternateBouncerShowing -> true
                            !isOnLockscreenScene -> false
                            isLaunchingAffordance -> false
                            isPulseExpanding -> false
                            isQsExpanded -> false
                            else -> true
                        }
                    }
                } else {
                    flowOf(false)
                }
            }
            .dumpWhileCollecting("canBypass")

    companion object {
        private const val TAG: String = "KeyguardBypassInteractor"
    }
}
