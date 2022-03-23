package com.android.keyguard

import android.annotation.CurrentTimeMillisLong
import android.hardware.biometrics.BiometricAuthenticator.Modality
import android.hardware.biometrics.BiometricAuthenticator.TYPE_FACE
import android.hardware.biometrics.BiometricAuthenticator.TYPE_FINGERPRINT

/** Verbose logging for various keyguard listening states. */
sealed class KeyguardListenModel {
    /** Timestamp of the state change. */
    abstract val timeMillis: Long
    /** Current user */
    abstract val userId: Int
    /** If keyguard is listening for the given [modality]. */
    abstract val listening: Boolean
    /** Sensor type */
    @Modality abstract val modality: Int
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
    val bouncer: Boolean,
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
    val userDoesNotHaveTrust: Boolean,
    val userNeedsStrongAuth: Boolean
) : KeyguardListenModel() {
    override val modality: Int = TYPE_FACE
}

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
    val bouncer: Boolean,
    val faceAuthenticated: Boolean,
    val faceDisabled: Boolean,
    val keyguardAwake: Boolean,
    val keyguardGoingAway: Boolean,
    val listeningForFaceAssistant: Boolean,
    val lockIconPressed: Boolean,
    val occludingAppRequestingFaceAuth: Boolean,
    val primaryUser: Boolean,
    val scanningAllowedByStrongAuth: Boolean,
    val secureCameraLaunched: Boolean,
    val switchingUser: Boolean
) : KeyguardListenModel() {
    override val modality: Int = TYPE_FINGERPRINT
}
