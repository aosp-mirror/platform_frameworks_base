package com.android.systemui.biometrics.domain.interactor

import com.android.internal.widget.LockscreenCredential
import com.android.systemui.biometrics.domain.model.BiometricPromptRequest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/** Fake implementation of [CredentialInteractor] for tests. */
class FakeCredentialInteractor : CredentialInteractor {

    /** Sets return value for [isStealthModeActive]. */
    var stealthMode: Boolean = false

    /** Sets return value for [getCredentialOwnerOrSelfId]. */
    var credentialOwnerId: Int? = null

    /** Sets return value for [getParentProfileIdOrSelfId]. */
    var userIdForPasswordEntry: Int? = null

    override fun isStealthModeActive(userId: Int): Boolean = stealthMode

    override fun getCredentialOwnerOrSelfId(userId: Int): Int = credentialOwnerId ?: userId

    override fun getParentProfileIdOrSelfId(userId: Int): Int = userIdForPasswordEntry ?: userId

    override fun verifyCredential(
        request: BiometricPromptRequest.Credential,
        credential: LockscreenCredential,
    ): Flow<CredentialStatus> = verifyCredentialResponse(credential)

    /** Sets the result value for [verifyCredential]. */
    var verifyCredentialResponse: (credential: LockscreenCredential) -> Flow<CredentialStatus> =
        { _ ->
            flowOf(CredentialStatus.Fail.Error("invalid"))
        }
}
