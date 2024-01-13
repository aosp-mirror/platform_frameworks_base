package com.android.systemui.biometrics.domain.model

import android.hardware.biometrics.PromptContentView
import android.hardware.biometrics.PromptInfo
import com.android.systemui.biometrics.shared.model.BiometricModalities
import com.android.systemui.biometrics.shared.model.BiometricUserInfo

/**
 * Preferences for BiometricPrompt, such as title & description, that are immutable while the prompt
 * is showing.
 *
 * This roughly corresponds to a "request" by the system or an app to show BiometricPrompt and it
 * contains a subset of the information in a [PromptInfo] that is relevant to SysUI.
 */
sealed class BiometricPromptRequest(
    val title: String,
    val subtitle: String,
    val description: String,
    val userInfo: BiometricUserInfo,
    val operationInfo: BiometricOperationInfo,
    val showEmergencyCallButton: Boolean,
) {
    /** Prompt using one or more biometrics. */
    class Biometric(
        info: PromptInfo,
        userInfo: BiometricUserInfo,
        operationInfo: BiometricOperationInfo,
        val modalities: BiometricModalities,
    ) :
        BiometricPromptRequest(
            title = info.title?.toString() ?: "",
            subtitle = info.subtitle?.toString() ?: "",
            description = info.description?.toString() ?: "",
            userInfo = userInfo,
            operationInfo = operationInfo,
            showEmergencyCallButton = info.isShowEmergencyCallButton
        ) {
        val contentView: PromptContentView? = info.contentView
        val negativeButtonText: String = info.negativeButtonText?.toString() ?: ""
    }

    /** Prompt using a credential (pin, pattern, password). */
    sealed class Credential(
        info: PromptInfo,
        userInfo: BiometricUserInfo,
        operationInfo: BiometricOperationInfo,
    ) :
        BiometricPromptRequest(
            title = (info.deviceCredentialTitle ?: info.title)?.toString() ?: "",
            subtitle = (info.deviceCredentialSubtitle ?: info.subtitle)?.toString() ?: "",
            description = (info.deviceCredentialDescription ?: info.description)?.toString() ?: "",
            userInfo = userInfo,
            operationInfo = operationInfo,
            showEmergencyCallButton = info.isShowEmergencyCallButton
        ) {

        /** PIN prompt. */
        class Pin(
            info: PromptInfo,
            userInfo: BiometricUserInfo,
            operationInfo: BiometricOperationInfo,
        ) : Credential(info, userInfo, operationInfo)

        /** Password prompt. */
        class Password(
            info: PromptInfo,
            userInfo: BiometricUserInfo,
            operationInfo: BiometricOperationInfo,
        ) : Credential(info, userInfo, operationInfo)

        /** Pattern prompt. */
        class Pattern(
            info: PromptInfo,
            userInfo: BiometricUserInfo,
            operationInfo: BiometricOperationInfo,
            val stealthMode: Boolean,
        ) : Credential(info, userInfo, operationInfo)
    }
}
