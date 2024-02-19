package com.android.systemui.biometrics.domain.interactor

import android.app.admin.DevicePolicyManager
import android.app.admin.DevicePolicyResources
import android.content.Context
import android.os.UserManager
import com.android.internal.widget.LockPatternUtils
import com.android.internal.widget.LockscreenCredential
import com.android.internal.widget.VerifyCredentialResponse
import com.android.systemui.biometrics.domain.model.BiometricPromptRequest
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.res.R
import com.android.systemui.util.time.SystemClock
import javax.inject.Inject
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * A wrapper for [LockPatternUtils] to verify PIN, pattern, or password credentials.
 *
 * This class also uses the [DevicePolicyManager] to generate appropriate error messages when policy
 * exceptions are raised (i.e. wipe device due to excessive failed attempts, etc.).
 */
interface CredentialInteractor {
    /** If the user's pattern credential should be hidden */
    fun isStealthModeActive(userId: Int): Boolean

    /** Get the effective user id (profile owner, if one exists) */
    fun getCredentialOwnerOrSelfId(userId: Int): Int

    /** Get parent user profile (if exists) */
    fun getParentProfileIdOrSelfId(userId: Int): Int

    /**
     * Verifies a credential and returns a stream of results.
     *
     * The final emitted value will either be a [CredentialStatus.Fail.Error] or a
     * [CredentialStatus.Success.Verified].
     */
    fun verifyCredential(
        request: BiometricPromptRequest.Credential,
        credential: LockscreenCredential,
    ): Flow<CredentialStatus>
}

/** Standard implementation of [CredentialInteractor]. */
class CredentialInteractorImpl
@Inject
constructor(
    @Application private val applicationContext: Context,
    private val lockPatternUtils: LockPatternUtils,
    private val userManager: UserManager,
    private val devicePolicyManager: DevicePolicyManager,
    private val systemClock: SystemClock,
) : CredentialInteractor {

    override fun isStealthModeActive(userId: Int): Boolean =
        !lockPatternUtils.isVisiblePatternEnabled(userId)

    override fun getCredentialOwnerOrSelfId(userId: Int): Int =
        userManager.getCredentialOwnerProfile(userId)

    override fun getParentProfileIdOrSelfId(userId: Int): Int =
        userManager.getProfileParent(userId)?.id ?: userManager.getCredentialOwnerProfile(userId)

    override fun verifyCredential(
        request: BiometricPromptRequest.Credential,
        credential: LockscreenCredential,
    ): Flow<CredentialStatus> = flow {
        // Request LockSettingsService to return the Gatekeeper Password in the
        // VerifyCredentialResponse so that we can request a Gatekeeper HAT with the
        // Gatekeeper Password and operationId.
        val effectiveUserId = request.userInfo.deviceCredentialOwnerId
        val response =
            lockPatternUtils.verifyCredential(
                credential,
                effectiveUserId,
                LockPatternUtils.VERIFY_FLAG_REQUEST_GK_PW_HANDLE
            )

        if (response.isMatched) {
            lockPatternUtils.userPresent(effectiveUserId)

            // The response passed into this method contains the Gatekeeper
            // Password. We still have to request Gatekeeper to create a
            // Hardware Auth Token with the Gatekeeper Password and Challenge
            // (keystore operationId in this case)
            val pwHandle = response.gatekeeperPasswordHandle
            val gkResponse: VerifyCredentialResponse =
                lockPatternUtils.verifyGatekeeperPasswordHandle(
                    pwHandle,
                    request.operationInfo.gatekeeperChallenge,
                    effectiveUserId
                )
            val hat = gkResponse.gatekeeperHAT
            lockPatternUtils.removeGatekeeperPasswordHandle(pwHandle)
            emit(CredentialStatus.Success.Verified(checkNotNull(hat)))
        } else if (response.timeout > 0) {
            // if requests are being throttled, update the error message every
            // second until the temporary lock has expired
            val deadline: Long =
                lockPatternUtils.setLockoutAttemptDeadline(effectiveUserId, response.timeout)
            val interval = LockPatternUtils.FAILED_ATTEMPT_COUNTDOWN_INTERVAL_MS
            var remaining = deadline - systemClock.elapsedRealtime()
            while (remaining > 0) {
                emit(
                    CredentialStatus.Fail.Throttled(
                        applicationContext.getString(
                            R.string.biometric_dialog_credential_too_many_attempts,
                            remaining / 1000
                        )
                    )
                )
                delay(interval)
                remaining -= interval
            }
            emit(CredentialStatus.Fail.Error(""))
        } else { // bad request, but not throttled
            val numAttempts = lockPatternUtils.getCurrentFailedPasswordAttempts(effectiveUserId) + 1
            val maxAttempts = lockPatternUtils.getMaximumFailedPasswordsForWipe(effectiveUserId)
            if (maxAttempts <= 0 || numAttempts <= 0) {
                // use a generic message if there's no maximum number of attempts
                emit(CredentialStatus.Fail.Error())
            } else {
                val remainingAttempts = (maxAttempts - numAttempts).coerceAtLeast(0)
                emit(
                    CredentialStatus.Fail.Error(
                        applicationContext.getString(
                            R.string.biometric_dialog_credential_attempts_before_wipe,
                            numAttempts,
                            maxAttempts
                        ),
                        remainingAttempts,
                        fetchFinalAttemptMessageOrNull(request, remainingAttempts)
                    )
                )
            }
            lockPatternUtils.reportFailedPasswordAttempt(effectiveUserId)
        }
    }

    private fun fetchFinalAttemptMessageOrNull(
        request: BiometricPromptRequest.Credential,
        remainingAttempts: Int?,
    ): String? =
        if (remainingAttempts != null && remainingAttempts <= 1) {
            applicationContext.getFinalAttemptMessageOrBlank(
                request,
                devicePolicyManager,
                userManager.getUserTypeForWipe(
                    devicePolicyManager,
                    request.userInfo.deviceCredentialOwnerId
                ),
                remainingAttempts
            )
        } else {
            null
        }
}

private enum class UserType {
    PRIMARY,
    MANAGED_PROFILE,
    SECONDARY,
}

private fun UserManager.getUserTypeForWipe(
    devicePolicyManager: DevicePolicyManager,
    effectiveUserId: Int,
): UserType {
    val userToBeWiped =
        getUserInfo(
            devicePolicyManager.getProfileWithMinimumFailedPasswordsForWipe(effectiveUserId)
        )
    return when {
        userToBeWiped == null || userToBeWiped.isPrimary -> UserType.PRIMARY
        userToBeWiped.isManagedProfile -> UserType.MANAGED_PROFILE
        else -> UserType.SECONDARY
    }
}

private fun Context.getFinalAttemptMessageOrBlank(
    request: BiometricPromptRequest.Credential,
    devicePolicyManager: DevicePolicyManager,
    userType: UserType,
    remaining: Int,
): String =
    when {
        remaining == 1 -> getLastAttemptBeforeWipeMessage(request, devicePolicyManager, userType)
        remaining <= 0 -> getNowWipingMessage(devicePolicyManager, userType)
        else -> ""
    }

private fun Context.getLastAttemptBeforeWipeMessage(
    request: BiometricPromptRequest.Credential,
    devicePolicyManager: DevicePolicyManager,
    userType: UserType,
): String =
    when (userType) {
        UserType.PRIMARY -> getLastAttemptBeforeWipeDeviceMessage(request)
        UserType.MANAGED_PROFILE ->
            getLastAttemptBeforeWipeProfileMessage(request, devicePolicyManager)
        UserType.SECONDARY -> getLastAttemptBeforeWipeUserMessage(request)
    }

private fun Context.getLastAttemptBeforeWipeDeviceMessage(
    request: BiometricPromptRequest.Credential,
): String {
    val id =
        when (request) {
            is BiometricPromptRequest.Credential.Pin ->
                R.string.biometric_dialog_last_pin_attempt_before_wipe_device
            is BiometricPromptRequest.Credential.Pattern ->
                R.string.biometric_dialog_last_pattern_attempt_before_wipe_device
            is BiometricPromptRequest.Credential.Password ->
                R.string.biometric_dialog_last_password_attempt_before_wipe_device
        }
    return getString(id)
}

private fun Context.getLastAttemptBeforeWipeProfileMessage(
    request: BiometricPromptRequest.Credential,
    devicePolicyManager: DevicePolicyManager,
): String {
    val id =
        when (request) {
            is BiometricPromptRequest.Credential.Pin ->
                DevicePolicyResources.Strings.SystemUi.BIOMETRIC_DIALOG_WORK_PIN_LAST_ATTEMPT
            is BiometricPromptRequest.Credential.Pattern ->
                DevicePolicyResources.Strings.SystemUi.BIOMETRIC_DIALOG_WORK_PATTERN_LAST_ATTEMPT
            is BiometricPromptRequest.Credential.Password ->
                DevicePolicyResources.Strings.SystemUi.BIOMETRIC_DIALOG_WORK_PASSWORD_LAST_ATTEMPT
        }
    val getFallbackString = {
        val defaultId =
            when (request) {
                is BiometricPromptRequest.Credential.Pin ->
                    R.string.biometric_dialog_last_pin_attempt_before_wipe_profile
                is BiometricPromptRequest.Credential.Pattern ->
                    R.string.biometric_dialog_last_pattern_attempt_before_wipe_profile
                is BiometricPromptRequest.Credential.Password ->
                    R.string.biometric_dialog_last_password_attempt_before_wipe_profile
            }
        getString(defaultId)
    }

    return devicePolicyManager.resources?.getString(id, getFallbackString) ?: getFallbackString()
}

private fun Context.getLastAttemptBeforeWipeUserMessage(
    request: BiometricPromptRequest.Credential,
): String {
    val resId =
        when (request) {
            is BiometricPromptRequest.Credential.Pin ->
                R.string.biometric_dialog_last_pin_attempt_before_wipe_user
            is BiometricPromptRequest.Credential.Pattern ->
                R.string.biometric_dialog_last_pattern_attempt_before_wipe_user
            is BiometricPromptRequest.Credential.Password ->
                R.string.biometric_dialog_last_password_attempt_before_wipe_user
        }
    return getString(resId)
}

private fun Context.getNowWipingMessage(
    devicePolicyManager: DevicePolicyManager,
    userType: UserType,
): String {
    val id =
        when (userType) {
            UserType.MANAGED_PROFILE ->
                DevicePolicyResources.Strings.SystemUi.BIOMETRIC_DIALOG_WORK_LOCK_FAILED_ATTEMPTS
            else -> DevicePolicyResources.UNDEFINED
        }

    val getFallbackString = {
        val defaultId =
            when (userType) {
                UserType.PRIMARY ->
                    com.android.settingslib.R.string.failed_attempts_now_wiping_device
                UserType.MANAGED_PROFILE ->
                    com.android.settingslib.R.string.failed_attempts_now_wiping_profile
                UserType.SECONDARY ->
                    com.android.settingslib.R.string.failed_attempts_now_wiping_user
            }
        getString(defaultId)
    }

    return devicePolicyManager.resources?.getString(id, getFallbackString) ?: getFallbackString()
}
