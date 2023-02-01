package com.android.systemui.biometrics.domain.model

/** Metadata about an in-progress biometric operation. */
data class BiometricOperationInfo(val gatekeeperChallenge: Long = -1)
