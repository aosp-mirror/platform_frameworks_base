/*
 * Copyright (C) 2022 The Android Open Source Project
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

import com.android.keyguard.KeyguardUpdateMonitor
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.flags.FeatureFlags
import com.android.systemui.flags.Flags
import com.android.systemui.keyguard.data.repository.BiometricSettingsRepository
import com.android.systemui.keyguard.data.repository.DeviceEntryFingerprintAuthRepository
import com.android.systemui.keyguard.data.repository.KeyguardBouncerRepository
import com.android.systemui.plugins.statusbar.StatusBarStateController
import com.android.systemui.statusbar.phone.StatusBarKeyguardViewManager.LegacyAlternateBouncer
import com.android.systemui.statusbar.policy.KeyguardStateController
import com.android.systemui.util.time.SystemClock
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow

/** Encapsulates business logic for interacting with the lock-screen alternate bouncer. */
@SysUISingleton
class AlternateBouncerInteractor
@Inject
constructor(
    private val statusBarStateController: StatusBarStateController,
    private val keyguardStateController: KeyguardStateController,
    private val bouncerRepository: KeyguardBouncerRepository,
    private val biometricSettingsRepository: BiometricSettingsRepository,
    private val deviceEntryFingerprintAuthRepository: DeviceEntryFingerprintAuthRepository,
    private val systemClock: SystemClock,
    private val keyguardUpdateMonitor: KeyguardUpdateMonitor,
    featureFlags: FeatureFlags,
) {
    val isModernAlternateBouncerEnabled = featureFlags.isEnabled(Flags.MODERN_ALTERNATE_BOUNCER)
    var legacyAlternateBouncer: LegacyAlternateBouncer? = null
    var legacyAlternateBouncerVisibleTime: Long = NOT_VISIBLE

    val isVisible: Flow<Boolean> = bouncerRepository.alternateBouncerVisible

    private val keyguardStateControllerCallback: KeyguardStateController.Callback =
        object : KeyguardStateController.Callback {
            override fun onUnlockedChanged() {
                maybeHide()
            }
        }

    init {
        keyguardStateController.addCallback(keyguardStateControllerCallback)
    }

    /**
     * Sets the correct bouncer states to show the alternate bouncer if it can show.
     *
     * @return whether alternateBouncer is visible
     */
    fun show(): Boolean {
        return when {
            isModernAlternateBouncerEnabled -> {
                bouncerRepository.setAlternateVisible(canShowAlternateBouncerForFingerprint())
                isVisibleState()
            }
            canShowAlternateBouncerForFingerprint() -> {
                if (legacyAlternateBouncer?.showAlternateBouncer() == true) {
                    legacyAlternateBouncerVisibleTime = systemClock.uptimeMillis()
                    true
                } else {
                    false
                }
            }
            else -> false
        }
    }

    /**
     * Sets the correct bouncer states to hide the bouncer. Should only be called through
     * StatusBarKeyguardViewManager until ScrimController is refactored to use
     * alternateBouncerInteractor.
     *
     * @return true if the alternate bouncer was newly hidden, else false.
     */
    fun hide(): Boolean {
        return if (isModernAlternateBouncerEnabled) {
            val wasAlternateBouncerVisible = isVisibleState()
            bouncerRepository.setAlternateVisible(false)
            wasAlternateBouncerVisible && !isVisibleState()
        } else {
            legacyAlternateBouncer?.hideAlternateBouncer() ?: false
        }
    }

    fun isVisibleState(): Boolean {
        return if (isModernAlternateBouncerEnabled) {
            bouncerRepository.alternateBouncerVisible.value
        } else {
            legacyAlternateBouncer?.isShowingAlternateBouncer ?: false
        }
    }

    fun setAlternateBouncerUIAvailable(isAvailable: Boolean) {
        bouncerRepository.setAlternateBouncerUIAvailable(isAvailable)
    }

    fun canShowAlternateBouncerForFingerprint(): Boolean {
        return if (isModernAlternateBouncerEnabled) {
            bouncerRepository.alternateBouncerUIAvailable.value &&
                biometricSettingsRepository.isFingerprintEnrolled.value &&
                biometricSettingsRepository.isStrongBiometricAllowed.value &&
                biometricSettingsRepository.isFingerprintEnabledByDevicePolicy.value &&
                !deviceEntryFingerprintAuthRepository.isLockedOut.value &&
                !keyguardStateController.isUnlocked &&
                !statusBarStateController.isDozing
        } else {
            legacyAlternateBouncer != null &&
                keyguardUpdateMonitor.isUnlockingWithBiometricAllowed(true)
        }
    }

    /**
     * Whether the alt bouncer has shown for a minimum time before allowing touches to dismiss the
     * alternate bouncer and show the primary bouncer.
     */
    fun hasAlternateBouncerShownWithMinTime(): Boolean {
        return if (isModernAlternateBouncerEnabled) {
            (systemClock.uptimeMillis() - bouncerRepository.lastAlternateBouncerVisibleTime) >
                MIN_VISIBILITY_DURATION_UNTIL_TOUCHES_DISMISS_ALTERNATE_BOUNCER_MS
        } else {
            systemClock.uptimeMillis() - legacyAlternateBouncerVisibleTime > 200
        }
    }

    private fun maybeHide() {
        if (isVisibleState() && !canShowAlternateBouncerForFingerprint()) {
            hide()
        }
    }

    companion object {
        private const val MIN_VISIBILITY_DURATION_UNTIL_TOUCHES_DISMISS_ALTERNATE_BOUNCER_MS = 200L
        private const val NOT_VISIBLE = -1L
    }
}
