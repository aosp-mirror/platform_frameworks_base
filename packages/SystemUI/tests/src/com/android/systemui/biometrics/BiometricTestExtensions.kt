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

import android.annotation.IdRes
import android.content.Context
import android.hardware.biometrics.BiometricManager.Authenticators
import android.hardware.biometrics.ComponentInfoInternal
import android.hardware.biometrics.PromptInfo
import android.hardware.biometrics.SensorProperties
import android.hardware.biometrics.SensorPropertiesInternal
import android.hardware.face.FaceSensorProperties
import android.hardware.face.FaceSensorPropertiesInternal
import android.hardware.fingerprint.FingerprintSensorProperties
import android.hardware.fingerprint.FingerprintSensorPropertiesInternal
import android.os.Bundle
import android.testing.ViewUtils
import android.view.LayoutInflater

/**
 * Inflate the given BiometricPrompt layout and initialize it with test parameters.
 *
 * This attaches the view so be sure to call [destroyDialog] at the end of the test.
 */
@IdRes
internal fun <T : AuthBiometricView> Int.asTestAuthBiometricView(
    context: Context,
    callback: AuthBiometricView.Callback,
    panelController: AuthPanelController,
    allowDeviceCredential: Boolean = false,
    savedState: Bundle? = null,
    hideDelay: Int = 0
): T {
    val view = LayoutInflater.from(context).inflate(this, null, false) as T
    view.mAnimationDurationLong = 0
    view.mAnimationDurationShort = 0
    view.mAnimationDurationHideDialog = hideDelay
    view.setPromptInfo(buildPromptInfo(allowDeviceCredential))
    view.setCallback(callback)
    view.restoreState(savedState)
    view.setPanelController(panelController)

    ViewUtils.attachView(view)

    return view
}

private fun buildPromptInfo(allowDeviceCredential: Boolean): PromptInfo {
    val promptInfo = PromptInfo()
    promptInfo.title = "Title"
    var authenticators = Authenticators.BIOMETRIC_WEAK
    if (allowDeviceCredential) {
        authenticators = authenticators or Authenticators.DEVICE_CREDENTIAL
    } else {
        promptInfo.negativeButtonText = "Negative"
    }
    promptInfo.authenticators = authenticators
    return promptInfo
}

/** Detach the view, if needed. */
internal fun AuthBiometricView?.destroyDialog() {
    if (this != null && isAttachedToWindow) {
        ViewUtils.detachView(this)
    }
}

/** Create [FingerprintSensorPropertiesInternal] for a test. */
internal fun fingerprintSensorPropertiesInternal(
    ids: List<Int> = listOf(0),
    strong: Boolean = true,
): List<FingerprintSensorPropertiesInternal> {
    val componentInfo =
        listOf(
            ComponentInfoInternal(
                "fingerprintSensor" /* componentId */,
                "vendor/model/revision" /* hardwareVersion */,
                "1.01" /* firmwareVersion */,
                "00000001" /* serialNumber */,
                "" /* softwareVersion */
            ),
            ComponentInfoInternal(
                "matchingAlgorithm" /* componentId */,
                "" /* hardwareVersion */,
                "" /* firmwareVersion */,
                "" /* serialNumber */,
                "vendor/version/revision" /* softwareVersion */
            )
        )
    return ids.map { id ->
        FingerprintSensorPropertiesInternal(
            id,
            if (strong) SensorProperties.STRENGTH_STRONG else SensorProperties.STRENGTH_WEAK,
            5 /* maxEnrollmentsPerUser */,
            componentInfo,
            FingerprintSensorProperties.TYPE_REAR,
            false /* resetLockoutRequiresHardwareAuthToken */
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
                "" /* softwareVersion */
            ),
            ComponentInfoInternal(
                "matchingAlgorithm" /* componentId */,
                "" /* hardwareVersion */,
                "" /* firmwareVersion */,
                "" /* serialNumber */,
                "vendor/version/revision" /* softwareVersion */
            )
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
            false /* resetLockoutRequiresHardwareAuthToken */
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
    title: String = "title",
    subtitle: String = "sub",
    description: String = "desc",
    credentialTitle: String? = "cred title",
    credentialSubtitle: String? = "cred sub",
    credentialDescription: String? = "cred desc",
    negativeButton: String = "neg",
): PromptInfo {
    val info = PromptInfo()
    info.title = title
    info.subtitle = subtitle
    info.description = description
    credentialTitle?.let { info.deviceCredentialTitle = it }
    credentialSubtitle?.let { info.deviceCredentialSubtitle = it }
    credentialDescription?.let { info.deviceCredentialDescription = it }
    info.negativeButtonText = negativeButton
    return info
}
