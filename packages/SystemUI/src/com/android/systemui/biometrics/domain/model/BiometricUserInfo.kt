package com.android.systemui.biometrics.domain.model

/** Metadata about the current user BiometricPrompt is being shown to. */
data class BiometricUserInfo(
    val userId: Int,
    val deviceCredentialOwnerId: Int = userId,
)
