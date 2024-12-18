package com.android.systemui.biometrics.ui.viewmodel

import android.graphics.drawable.Drawable
import android.hardware.biometrics.PromptContentView
import com.android.systemui.biometrics.shared.model.BiometricUserInfo

/** View model for the top-level header / info area of BiometricPrompt. */
interface CredentialHeaderViewModel {
    val user: BiometricUserInfo
    val title: String
    val subtitle: String
    val description: String
    val contentView: PromptContentView?
    val icon: Drawable
    val showEmergencyCallButton: Boolean
}
