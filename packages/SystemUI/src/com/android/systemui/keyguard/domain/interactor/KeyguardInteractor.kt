/*
 *  Copyright (C) 2022 The Android Open Source Project
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */

package com.android.systemui.keyguard.domain.interactor

import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.keyguard.data.repository.KeyguardRepository
import com.android.systemui.keyguard.shared.model.BiometricUnlockModel
import com.android.systemui.keyguard.shared.model.StatusBarState
import com.android.systemui.keyguard.shared.model.WakefulnessModel
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow

/**
 * Encapsulates business-logic related to the keyguard but not to a more specific part within it.
 */
@SysUISingleton
class KeyguardInteractor
@Inject
constructor(
    private val repository: KeyguardRepository,
) {
    /**
     * The amount of doze the system is in, where `1.0` is fully dozing and `0.0` is not dozing at
     * all.
     */
    val dozeAmount: Flow<Float> = repository.dozeAmount
    /** Whether the system is in doze mode. */
    val isDozing: Flow<Boolean> = repository.isDozing
    /** Whether the keyguard is showing or not. */
    val isKeyguardShowing: Flow<Boolean> = repository.isKeyguardShowing
    /** Whether the keyguard is going away. */
    val isKeyguardGoingAway: Flow<Boolean> = repository.isKeyguardGoingAway
    /** Whether the bouncer is showing or not. */
    val isBouncerShowing: Flow<Boolean> = repository.isBouncerShowing
    /** The device wake/sleep state */
    val wakefulnessState: Flow<WakefulnessModel> = repository.wakefulnessState
    /** Observable for the [StatusBarState] */
    val statusBarState: Flow<StatusBarState> = repository.statusBarState
    /**
     * Observable for [BiometricUnlockModel] when biometrics like face or any fingerprint (rear,
     * side, under display) is used to unlock the device.
     */
    val biometricUnlockState: Flow<BiometricUnlockModel> = repository.biometricUnlockState

    fun isKeyguardShowing(): Boolean {
        return repository.isKeyguardShowing()
    }
}
