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

package com.android.systemui.bouncer.domain.interactor

import com.android.keyguard.KeyguardUpdateMonitor
import com.android.systemui.biometrics.data.repository.FingerprintPropertyRepository
import com.android.systemui.bouncer.data.repository.KeyguardBouncerRepository
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.deviceentry.shared.DeviceEntryUdfpsRefactor
import com.android.systemui.keyguard.data.repository.BiometricSettingsRepository
import com.android.systemui.plugins.statusbar.StatusBarStateController
import com.android.systemui.statusbar.policy.KeyguardStateController
import com.android.systemui.util.time.SystemClock
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Encapsulates business logic for interacting with the lock-screen alternate bouncer. */
@SysUISingleton
class AlternateBouncerInteractor
@Inject
constructor(
    private val statusBarStateController: StatusBarStateController,
    private val keyguardStateController: KeyguardStateController,
    private val bouncerRepository: KeyguardBouncerRepository,
    fingerprintPropertyRepository: FingerprintPropertyRepository,
    private val biometricSettingsRepository: BiometricSettingsRepository,
    private val systemClock: SystemClock,
    private val keyguardUpdateMonitor: KeyguardUpdateMonitor,
    @Application scope: CoroutineScope,
) {
    var receivedDownTouch = false
    val isVisible: Flow<Boolean> = bouncerRepository.alternateBouncerVisible
    private val alternateBouncerUiAvailableFromSource: HashSet<String> = HashSet()
    private val alternateBouncerSupported: StateFlow<Boolean> =
        if (DeviceEntryUdfpsRefactor.isEnabled) {
            // The device entry udfps refactor doesn't currently support the alternate bouncer.
            // TODO: Re-enable when b/287599719 is ready.
            MutableStateFlow(false).asStateFlow()
            //            fingerprintPropertyRepository.sensorType
            //                .map { sensorType ->
            //                    sensorType.isUdfps() || sensorType ==
            // FingerprintSensorType.POWER_BUTTON
            //                }
            //                .stateIn(
            //                    scope = scope,
            //                    started = SharingStarted.Eagerly,
            //                    initialValue = false,
            //                )
        } else {
            bouncerRepository.alternateBouncerUIAvailable
        }

    /**
     * Sets the correct bouncer states to show the alternate bouncer if it can show.
     *
     * @return whether alternateBouncer is visible
     */
    fun show(): Boolean {
        bouncerRepository.setAlternateVisible(canShowAlternateBouncerForFingerprint())
        return isVisibleState()
    }

    /**
     * Sets the correct bouncer states to hide the bouncer. Should only be called through
     * StatusBarKeyguardViewManager until ScrimController is refactored to use
     * alternateBouncerInteractor.
     *
     * @return true if the alternate bouncer was newly hidden, else false.
     */
    fun hide(): Boolean {
        receivedDownTouch = false
        val wasAlternateBouncerVisible = isVisibleState()
        bouncerRepository.setAlternateVisible(false)
        return wasAlternateBouncerVisible && !isVisibleState()
    }

    fun isVisibleState(): Boolean {
        return bouncerRepository.alternateBouncerVisible.value
    }

    fun setAlternateBouncerUIAvailable(isAvailable: Boolean, token: String) {
        DeviceEntryUdfpsRefactor.assertInLegacyMode()
        if (isAvailable) {
            alternateBouncerUiAvailableFromSource.add(token)
        } else {
            alternateBouncerUiAvailableFromSource.remove(token)
        }
        bouncerRepository.setAlternateBouncerUIAvailable(
            alternateBouncerUiAvailableFromSource.isNotEmpty()
        )
    }

    fun canShowAlternateBouncerForFingerprint(): Boolean {
        return alternateBouncerSupported.value &&
            biometricSettingsRepository.isFingerprintAuthCurrentlyAllowed.value &&
            !keyguardUpdateMonitor.isFingerprintLockedOut &&
            !keyguardStateController.isUnlocked &&
            !statusBarStateController.isDozing
    }

    /**
     * Whether the alt bouncer has shown for a minimum time before allowing touches to dismiss the
     * alternate bouncer and show the primary bouncer.
     */
    fun hasAlternateBouncerShownWithMinTime(): Boolean {
        return (systemClock.uptimeMillis() - bouncerRepository.lastAlternateBouncerVisibleTime) >
            MIN_VISIBILITY_DURATION_UNTIL_TOUCHES_DISMISS_ALTERNATE_BOUNCER_MS
    }
    /**
     * Should only be called through StatusBarKeyguardViewManager which propagates the source of
     * truth to other concerned controllers. Will hide the alternate bouncer if it's no longer
     * allowed to show.
     *
     * @return true if the alternate bouncer was newly hidden, else false.
     */
    fun maybeHide(): Boolean {
        if (isVisibleState() && !canShowAlternateBouncerForFingerprint()) {
            return hide()
        }
        return false
    }

    companion object {
        private const val MIN_VISIBILITY_DURATION_UNTIL_TOUCHES_DISMISS_ALTERNATE_BOUNCER_MS = 200L
    }
}
