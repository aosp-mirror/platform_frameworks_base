/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.systemui.biometrics

import android.graphics.Bitmap
import android.hardware.biometrics.BiometricManager.Authenticators
import android.hardware.biometrics.ComponentInfoInternal
import android.hardware.biometrics.PromptContentView
import android.hardware.biometrics.PromptInfo
import android.hardware.biometrics.SensorProperties
import android.hardware.biometrics.SensorPropertiesInternal
import android.hardware.face.FaceSensorProperties
import android.hardware.face.FaceSensorPropertiesInternal
import android.hardware.fingerprint.FingerprintSensorProperties
import android.hardware.fingerprint.FingerprintSensorPropertiesInternal
import com.android.keyguard.keyguardUpdateMonitor
import com.android.systemui.SysuiTestableContext
import com.android.systemui.biometrics.data.repository.biometricStatusRepository
import com.android.systemui.biometrics.shared.model.AuthenticationReason
import com.android.systemui.bouncer.data.repository.keyguardBouncerRepository
import com.android.systemui.kosmos.Kosmos
import com.android.systemui.res.R
import com.android.systemui.util.mockito.whenever
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent

/** Create [FingerprintSensorPropertiesInternal] for a test. */
internal fun fingerprintSensorPropertiesInternal(
    ids: List<Int> = listOf(0),
    strong: Boolean = true,
    sensorType: Int = FingerprintSensorProperties.TYPE_REAR,
): List<FingerprintSensorPropertiesInternal> {
    val componentInfo =
        listOf(
            ComponentInfoInternal(
                "fingerprintSensor" /* componentId */,
                "vendor/model/revision" /* hardwareVersion */,
                "1.01" /* firmwareVersion */,
                "00000001" /* serialNumber */,
                "", /* softwareVersion */
            ),
            ComponentInfoInternal(
                "matchingAlgorithm" /* componentId */,
                "" /* hardwareVersion */,
                "" /* firmwareVersion */,
                "" /* serialNumber */,
                "vendor/version/revision", /* softwareVersion */
            ),
        )
    return ids.map { id ->
        FingerprintSensorPropertiesInternal(
            id,
            if (strong) SensorProperties.STRENGTH_STRONG else SensorProperties.STRENGTH_WEAK,
            5 /* maxEnrollmentsPerUser */,
            componentInfo,
            sensorType,
            false, /* resetLockoutRequiresHardwareAuthToken */
        )
    }
}

/** Create [FaceSensorPropertiesInternal] for a test. */
internal fun faceSensorPropertiesInternal(
    ids: List<Int> = listOf(1),
    strong: Boolean = true,
): List<FaceSensorPropertiesInternal> {
    val componentInfo =
        listOf(
            ComponentInfoInternal(
                "faceSensor" /* componentId */,
                "vendor/model/revision" /* hardwareVersion */,
                "1.01" /* firmwareVersion */,
                "00000001" /* serialNumber */,
                "", /* softwareVersion */
            ),
            ComponentInfoInternal(
                "matchingAlgorithm" /* componentId */,
                "" /* hardwareVersion */,
                "" /* firmwareVersion */,
                "" /* serialNumber */,
                "vendor/version/revision", /* softwareVersion */
            ),
        )
    return ids.map { id ->
        FaceSensorPropertiesInternal(
            id,
            if (strong) SensorProperties.STRENGTH_STRONG else SensorProperties.STRENGTH_WEAK,
            2 /* maxEnrollmentsPerUser */,
            componentInfo,
            FaceSensorProperties.TYPE_RGB,
            true /* supportsFaceDetection */,
            true /* supportsSelfIllumination */,
            false, /* resetLockoutRequiresHardwareAuthToken */
        )
    }
}

@Authenticators.Types
internal fun Collection<SensorPropertiesInternal?>.extractAuthenticatorTypes(): Int {
    var authenticators = Authenticators.EMPTY_SET
    mapNotNull { it?.sensorStrength }
        .forEach { strength ->
            authenticators =
                authenticators or
                    when (strength) {
                        SensorProperties.STRENGTH_CONVENIENCE ->
                            Authenticators.BIOMETRIC_CONVENIENCE
                        SensorProperties.STRENGTH_WEAK -> Authenticators.BIOMETRIC_WEAK
                        SensorProperties.STRENGTH_STRONG -> Authenticators.BIOMETRIC_STRONG
                        else -> Authenticators.EMPTY_SET
                    }
        }
    return authenticators
}

internal fun promptInfo(
    logoRes: Int = -1,
    logoBitmap: Bitmap? = null,
    logoDescription: String? = null,
    title: String = "title",
    subtitle: String = "sub",
    description: String = "desc",
    contentView: PromptContentView? = null,
    credentialTitle: String? = "cred title",
    credentialSubtitle: String? = "cred sub",
    credentialDescription: String? = "cred desc",
    negativeButton: String = "neg",
): PromptInfo {
    val info = PromptInfo()
    if (logoBitmap != null) {
        info.setLogo(logoRes, logoBitmap)
    }
    info.logoDescription = logoDescription
    info.title = title
    info.subtitle = subtitle
    info.description = description
    info.contentView = contentView
    credentialTitle?.let { info.deviceCredentialTitle = it }
    credentialSubtitle?.let { info.deviceCredentialSubtitle = it }
    credentialDescription?.let { info.deviceCredentialDescription = it }
    info.negativeButtonText = negativeButton
    return info
}

@OptIn(ExperimentalCoroutinesApi::class)
internal fun TestScope.updateSfpsIndicatorRequests(
    kosmos: Kosmos,
    mContext: SysuiTestableContext,
    primaryBouncerRequest: Boolean? = null,
    alternateBouncerRequest: Boolean? = null,
    biometricPromptRequest: Boolean? = null,
    // TODO(b/365182034): update when rest to unlock feature is implemented
    //    progressBarShowing: Boolean? = null
) {
    biometricPromptRequest?.let { hasBiometricPromptRequest ->
        if (hasBiometricPromptRequest) {
            kosmos.biometricStatusRepository.setFingerprintAuthenticationReason(
                AuthenticationReason.BiometricPromptAuthentication
            )
        } else {
            kosmos.biometricStatusRepository.setFingerprintAuthenticationReason(
                AuthenticationReason.NotRunning
            )
        }
    }

    primaryBouncerRequest?.let { hasPrimaryBouncerRequest ->
        updatePrimaryBouncer(
            kosmos,
            mContext,
            isShowing = hasPrimaryBouncerRequest,
            isAnimatingAway = false,
            fpsDetectionRunning = true,
            isUnlockingWithFpAllowed = true,
        )
    }

    alternateBouncerRequest?.let { hasAlternateBouncerRequest ->
        kosmos.keyguardBouncerRepository.setAlternateVisible(hasAlternateBouncerRequest)
    }

    // TODO(b/365182034): set progress bar visibility when rest to unlock feature is implemented

    runCurrent()
}

internal fun updatePrimaryBouncer(
    kosmos: Kosmos,
    mContext: SysuiTestableContext,
    isShowing: Boolean,
    isAnimatingAway: Boolean,
    fpsDetectionRunning: Boolean,
    isUnlockingWithFpAllowed: Boolean,
) {
    kosmos.keyguardBouncerRepository.setPrimaryShow(isShowing)
    kosmos.keyguardBouncerRepository.setPrimaryStartingToHide(false)
    val primaryStartDisappearAnimation = if (isAnimatingAway) Runnable {} else null
    kosmos.keyguardBouncerRepository.setPrimaryStartDisappearAnimation(
        primaryStartDisappearAnimation
    )

    whenever(kosmos.keyguardUpdateMonitor.isFingerprintDetectionRunning)
        .thenReturn(fpsDetectionRunning)
    whenever(kosmos.keyguardUpdateMonitor.isUnlockingWithFingerprintAllowed)
        .thenReturn(isUnlockingWithFpAllowed)
    mContext.orCreateTestableResources.addOverride(R.bool.config_show_sidefps_hint_on_bouncer, true)
}
