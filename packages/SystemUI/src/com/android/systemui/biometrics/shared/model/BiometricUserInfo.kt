package com.android.systemui.biometrics.shared.model

/**
 * Metadata about the current user BiometricPrompt is being shown to.
 *
 * If the user's fallback credential is owned by another profile user the [deviceCredentialOwnerId]
 * will differ from the user's [userId].
 */
data class BiometricUserInfo(
    val userId: Int,
    val deviceCredentialOwnerId: Int = userId,
)
