package com.android.keyguard

import android.annotation.CurrentTimeMillisLong

/** Verbose logging for various keyguard listening states. */
sealed class KeyguardListenModel {
    /** Timestamp of the state change. */
    abstract val timeMillis: Long
    /** Current user. */
    abstract val userId: Int
    /** If keyguard is listening for the modality represented by this model. */
    abstract val listening: Boolean
}

/**
 * Verbose debug information associated with [KeyguardUpdateMonitor.shouldListenForFingerprint].
 */
data class KeyguardFingerprintListenModel(
    @CurrentTimeMillisLong override val timeMillis: Long,
    override val userId: Int,
    override val listening: Boolean,
    // keep sorted
    val biometricEnabledForUser: Boolean,
    val bouncerIsOrWillShow: Boolean,
    val canSkipBouncer: Boolean,
    val credentialAttempted: Boolean,
    val deviceInteractive: Boolean,
    val dreaming: Boolean,
    val encryptedOrLockdown: Boolean,
    val fingerprintDisabled: Boolean,
    val fingerprintLockedOut: Boolean,
    val goingToSleep: Boolean,
    val keyguardGoingAway: Boolean,
    val keyguardIsVisible: Boolean,
    val keyguardOccluded: Boolean,
    val occludingAppRequestingFp: Boolean,
    val primaryUser: Boolean,
    val shouldListenForFingerprintAssistant: Boolean,
    val switchingUser: Boolean,
    val udfps: Boolean,
    val userDoesNotHaveTrust: Boolean
) : KeyguardListenModel()
/**
 * Verbose debug information associated with [KeyguardUpdateMonitor.shouldListenForFace].
 */
data class KeyguardFaceListenModel(
    @CurrentTimeMillisLong override val timeMillis: Long,
    override val userId: Int,
    override val listening: Boolean,
    // keep sorted
    val authInterruptActive: Boolean,
    val becauseCannotSkipBouncer: Boolean,
    val biometricSettingEnabledForUser: Boolean,
    val bouncerFullyShown: Boolean,
    val faceAuthenticated: Boolean,
    val faceDisabled: Boolean,
    val goingToSleep: Boolean,
    val keyguardAwake: Boolean,
    val keyguardGoingAway: Boolean,
    val listeningForFaceAssistant: Boolean,
    val lockIconPressed: Boolean,
    val occludingAppRequestingFaceAuth: Boolean,
    val primaryUser: Boolean,
    val scanningAllowedByStrongAuth: Boolean,
    val secureCameraLaunched: Boolean,
    val switchingUser: Boolean
) : KeyguardListenModel()
/**
 * Verbose debug information associated with [KeyguardUpdateMonitor.shouldTriggerActiveUnlock].
 */
data class KeyguardActiveUnlockModel(
    @CurrentTimeMillisLong override val timeMillis: Long,
    override val userId: Int,
    override val listening: Boolean,
    // keep sorted
    val authInterruptActive: Boolean,
    val encryptedOrTimedOut: Boolean,
    val fpLockout: Boolean,
    val lockDown: Boolean,
    val switchingUser: Boolean,
    val triggerActiveUnlockForAssistant: Boolean,
    val userCanDismissLockScreen: Boolean
) : KeyguardListenModel()
