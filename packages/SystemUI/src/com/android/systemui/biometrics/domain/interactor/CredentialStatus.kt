package com.android.systemui.biometrics.domain.interactor

/** Result of a [CredentialInteractor.verifyCredential] check. */
sealed interface CredentialStatus {
    /** A successful result. */
    sealed interface Success : CredentialStatus {
        /** The credential is valid and a [hat] has been generated. */
        data class Verified(val hat: ByteArray) : Success
    }
    /** A failed result. */
    sealed interface Fail : CredentialStatus {
        val error: String?

        /** The credential check failed with an [error]. */
        data class Error(
            override val error: String? = null,
            val remainingAttempts: Int? = null,
            val urgentMessage: String? = null,
        ) : Fail
        /** The credential check failed with an [error] and is temporarily locked out. */
        data class Throttled(override val error: String) : Fail
    }
}
