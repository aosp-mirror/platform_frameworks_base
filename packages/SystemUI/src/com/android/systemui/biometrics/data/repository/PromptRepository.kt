package com.android.systemui.biometrics.data.repository

import android.hardware.biometrics.PromptInfo
import com.android.systemui.biometrics.AuthController
import com.android.systemui.biometrics.shared.model.PromptKind
import com.android.systemui.common.coroutine.ChannelExt.trySendWithFailureLogging
import com.android.systemui.common.coroutine.ConflatedCallbackFlow.conflatedCallbackFlow
import com.android.systemui.dagger.SysUISingleton
import javax.inject.Inject
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * A repository for the global state of BiometricPrompt.
 *
 * There is never more than one instance of the prompt at any given time.
 */
interface PromptRepository {

    /** If the prompt is showing. */
    val isShowing: Flow<Boolean>

    /** The app-specific details to show in the prompt. */
    val promptInfo: StateFlow<PromptInfo?>

    /** The user that the prompt is for. */
    val userId: StateFlow<Int?>

    /** The gatekeeper challenge, if one is associated with this prompt. */
    val challenge: StateFlow<Long?>

    /** The kind of credential to use (biometric, pin, pattern, etc.). */
    val kind: StateFlow<PromptKind>

    /**
     * If explicit confirmation is required.
     *
     * Note: overlaps/conflicts with [PromptInfo.isConfirmationRequested], which needs clean up.
     */
    val isConfirmationRequired: StateFlow<Boolean>

    /** Update the prompt configuration, which should be set before [isShowing]. */
    fun setPrompt(
        promptInfo: PromptInfo,
        userId: Int,
        gatekeeperChallenge: Long?,
        kind: PromptKind,
        requireConfirmation: Boolean = false,
    )

    /** Unset the prompt info. */
    fun unsetPrompt()
}

@SysUISingleton
class PromptRepositoryImpl @Inject constructor(private val authController: AuthController) :
    PromptRepository {

    override val isShowing: Flow<Boolean> = conflatedCallbackFlow {
        val callback =
            object : AuthController.Callback {
                override fun onBiometricPromptShown() =
                    trySendWithFailureLogging(true, TAG, "set isShowing")

                override fun onBiometricPromptDismissed() =
                    trySendWithFailureLogging(false, TAG, "unset isShowing")
            }
        authController.addCallback(callback)
        trySendWithFailureLogging(authController.isShowing, TAG, "update isShowing")
        awaitClose { authController.removeCallback(callback) }
    }

    private val _promptInfo: MutableStateFlow<PromptInfo?> = MutableStateFlow(null)
    override val promptInfo = _promptInfo.asStateFlow()

    private val _challenge: MutableStateFlow<Long?> = MutableStateFlow(null)
    override val challenge: StateFlow<Long?> = _challenge.asStateFlow()

    private val _userId: MutableStateFlow<Int?> = MutableStateFlow(null)
    override val userId = _userId.asStateFlow()

    private val _kind: MutableStateFlow<PromptKind> = MutableStateFlow(PromptKind.Biometric())
    override val kind = _kind.asStateFlow()

    private val _isConfirmationRequired: MutableStateFlow<Boolean> = MutableStateFlow(false)
    override val isConfirmationRequired = _isConfirmationRequired.asStateFlow()

    override fun setPrompt(
        promptInfo: PromptInfo,
        userId: Int,
        gatekeeperChallenge: Long?,
        kind: PromptKind,
        requireConfirmation: Boolean,
    ) {
        _kind.value = kind
        _userId.value = userId
        _challenge.value = gatekeeperChallenge
        _promptInfo.value = promptInfo
        _isConfirmationRequired.value = requireConfirmation
    }

    override fun unsetPrompt() {
        _promptInfo.value = null
        _userId.value = null
        _challenge.value = null
        _kind.value = PromptKind.Biometric()
        _isConfirmationRequired.value = false
    }

    companion object {
        private const val TAG = "PromptRepositoryImpl"
    }
}
