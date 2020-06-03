package com.android.keyguard

import android.annotation.CurrentTimeMillisLong

/**
 * Data class for tracking information associated with [KeyguardUpdateMonitor.shouldListenForFace]
 * method calls.
 */
data class KeyguardFaceListenModel(
    @CurrentTimeMillisLong val timeMillis: Long,
    val userId: Int,
    val isListeningForFace: Boolean,
    val isBouncer: Boolean,
    val isAuthInterruptActive: Boolean,
    val isKeyguardAwake: Boolean,
    val isListeningForFaceAssistant: Boolean,
    val isSwitchingUser: Boolean,
    val isFaceDisabled: Boolean,
    val isBecauseCannotSkipBouncer: Boolean,
    val isKeyguardGoingAway: Boolean,
    val isFaceSettingEnabledForUser: Boolean,
    val isLockIconPressed: Boolean,
    val isScanningAllowedByStrongAuth: Boolean,
    val isPrimaryUser: Boolean,
    val isSecureCameraLaunched: Boolean
)
