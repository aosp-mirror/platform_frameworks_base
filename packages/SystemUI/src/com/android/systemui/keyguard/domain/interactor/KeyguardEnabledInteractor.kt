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

import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.keyguard.data.repository.BiometricSettingsRepository
import com.android.systemui.keyguard.data.repository.KeyguardRepository
import com.android.systemui.keyguard.shared.model.KeyguardState
import com.android.systemui.scene.shared.flag.SceneContainerFlag
import com.android.systemui.util.kotlin.Utils.Companion.sample as sampleCombine
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

/**
 * Logic around the keyguard being enabled/disabled, per [KeyguardService]. If the keyguard is not
 * enabled, the lockscreen cannot be shown and the device will go from AOD/DOZING directly to GONE.
 *
 * Keyguard can be disabled by selecting Security: "None" in settings, or by apps that hold
 * permission to do so (such as Phone). Some CTS tests also disable keyguard in onCreate or onStart
 * rather than simply dismissing the keyguard or setting up the device to have Security: None, for
 * reasons unknown.
 */
@SysUISingleton
class KeyguardEnabledInteractor
@Inject
constructor(
    @Application scope: CoroutineScope,
    val repository: KeyguardRepository,
    val biometricSettingsRepository: BiometricSettingsRepository,
    keyguardDismissTransitionInteractor: KeyguardDismissTransitionInteractor,
    internalTransitionInteractor: InternalKeyguardTransitionInteractor,
) {

    /**
     * Whether the keyguard is enabled, per [KeyguardService]. If the keyguard is not enabled, the
     * lockscreen cannot be shown and the device will go from AOD/DOZING directly to GONE.
     *
     * Keyguard can be disabled by selecting Security: "None" in settings, or by apps that hold
     * permission to do so (such as Phone).
     *
     * If the keyguard is disabled while we're locked, we will transition to GONE unless we're in
     * lockdown mode. If the keyguard is re-enabled, we'll transition back to LOCKSCREEN if we were
     * locked when it was disabled.
     */
    val isKeyguardEnabled: StateFlow<Boolean> = repository.isKeyguardEnabled

    /**
     * Whether we need to show the keyguard when the keyguard is re-enabled, since we hid it when it
     * became disabled.
     */
    val showKeyguardWhenReenabled: Flow<Boolean> =
        repository.isKeyguardEnabled
            .onEach { SceneContainerFlag.assertInLegacyMode() }
            // Whenever the keyguard is disabled...
            .filter { enabled -> !enabled }
            .sampleCombine(
                internalTransitionInteractor.currentTransitionInfoInternal,
                biometricSettingsRepository.isCurrentUserInLockdown,
            )
            .map { (_, transitionInfo, inLockdown) ->
                // ...we hide the keyguard, if it's showing and we're not in lockdown. In that case,
                // we want to remember that and re-show it when keyguard is enabled again.
                transitionInfo.to != KeyguardState.GONE && !inLockdown
            }

    init {
        /**
         * Whenever keyguard is disabled, transition to GONE unless we're in lockdown or already
         * GONE.
         */
        scope.launch {
            if (!SceneContainerFlag.isEnabled) {
                repository.isKeyguardEnabled
                    .filter { enabled -> !enabled }
                    .sampleCombine(
                        biometricSettingsRepository.isCurrentUserInLockdown,
                        internalTransitionInteractor.currentTransitionInfoInternal,
                    )
                    .collect { (_, inLockdown, currentTransitionInfo) ->
                        if (currentTransitionInfo.to != KeyguardState.GONE && !inLockdown) {
                            keyguardDismissTransitionInteractor.startDismissKeyguardTransition(
                                "keyguard disabled"
                            )
                        }
                    }
            }
        }
    }

    fun notifyKeyguardEnabled(enabled: Boolean) {
        repository.setKeyguardEnabled(enabled)
    }

    fun setShowKeyguardWhenReenabled(isShowKeyguardWhenReenabled: Boolean) {
        repository.setShowKeyguardWhenReenabled(isShowKeyguardWhenReenabled)
    }

    fun isShowKeyguardWhenReenabled(): Boolean {
        return repository.isShowKeyguardWhenReenabled()
    }
}
