package com.android.systemui.log

import android.hardware.face.FaceManager
import android.hardware.face.FaceSensorPropertiesInternal
import com.android.keyguard.FaceAuthUiEvent
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.log.dagger.FaceAuthLog
import com.android.systemui.plugins.log.LogBuffer
import com.android.systemui.plugins.log.LogLevel.DEBUG
import com.google.errorprone.annotations.CompileTimeConstant
import javax.inject.Inject

private const val TAG = "KeyguardFaceAuthManagerLog"

/**
 * Helper class for logging for [com.android.keyguard.faceauth.KeyguardFaceAuthManager]
 *
 * To enable logcat echoing for an entire buffer:
 * ```
 *   adb shell settings put global systemui/buffer/KeyguardFaceAuthManagerLog <logLevel>
 *
 * ```
 */
@SysUISingleton
class FaceAuthenticationLogger
@Inject
constructor(
    @FaceAuthLog private val logBuffer: LogBuffer,
) {
    fun ignoredFaceAuthTrigger(uiEvent: FaceAuthUiEvent) {
        logBuffer.log(
            TAG,
            DEBUG,
            { str1 = uiEvent.reason },
            {
                "Ignoring trigger because face auth is currently running. " +
                    "Trigger reason: $str1"
            }
        )
    }

    fun queuingRequestWhileCancelling(
        alreadyQueuedRequest: FaceAuthUiEvent?,
        newRequest: FaceAuthUiEvent
    ) {
        logBuffer.log(
            TAG,
            DEBUG,
            {
                str1 = alreadyQueuedRequest?.reason
                str2 = newRequest.reason
            },
            {
                "Face auth requested while previous request is being cancelled, " +
                    "already queued request: $str1 queueing the new request: $str2"
            }
        )
    }

    fun authenticating(uiEvent: FaceAuthUiEvent) {
        logBuffer.log(TAG, DEBUG, { str1 = uiEvent.reason }, { "Running authenticate for $str1" })
    }

    fun detectionNotSupported(
        faceManager: FaceManager?,
        sensorPropertiesInternal: MutableList<FaceSensorPropertiesInternal>?
    ) {
        logBuffer.log(
            TAG,
            DEBUG,
            {
                bool1 = faceManager == null
                bool2 = sensorPropertiesInternal.isNullOrEmpty()
                bool2 = sensorPropertiesInternal?.firstOrNull()?.supportsFaceDetection ?: false
            },
            {
                "skipping detection request because it is not supported, " +
                    "faceManager isNull: $bool1, " +
                    "sensorPropertiesInternal isNullOrEmpty: $bool2, " +
                    "supportsFaceDetection: $bool3"
            }
        )
    }

    fun skippingBecauseAlreadyRunning(@CompileTimeConstant operation: String) {
        logBuffer.log(TAG, DEBUG, "isAuthRunning is true, skipping $operation")
    }

    fun faceDetectionStarted() {
        logBuffer.log(TAG, DEBUG, "Face detection started.")
    }

    fun faceDetected() {
        logBuffer.log(TAG, DEBUG, "Face detected")
    }

    fun cancelSignalNotReceived(
        isAuthRunning: Boolean,
        isLockedOut: Boolean,
        cancellationInProgress: Boolean,
        faceAuthRequestedWhileCancellation: FaceAuthUiEvent?
    ) {
        logBuffer.log(
            TAG,
            DEBUG,
            {
                bool1 = isAuthRunning
                bool2 = isLockedOut
                bool3 = cancellationInProgress
                str1 = "${faceAuthRequestedWhileCancellation?.reason}"
            },
            {
                "Cancel signal was not received, running timeout handler to reset state. " +
                    "State before reset: " +
                    "isAuthRunning: $bool1, " +
                    "isLockedOut: $bool2, " +
                    "cancellationInProgress: $bool3, " +
                    "faceAuthRequestedWhileCancellation: $str1"
            }
        )
    }

    fun authenticationFailed() {
        logBuffer.log(TAG, DEBUG, "Face authentication failed")
    }

    fun authenticationAcquired(acquireInfo: Int) {
        logBuffer.log(
            TAG,
            DEBUG,
            { int1 = acquireInfo },
            { "Face acquired during face authentication: acquireInfo: $int1 " }
        )
    }

    fun authenticationError(
        errorCode: Int,
        errString: CharSequence?,
        lockoutError: Boolean,
        cancellationError: Boolean
    ) {
        logBuffer.log(
            TAG,
            DEBUG,
            {
                int1 = errorCode
                str1 = "$errString"
                bool1 = lockoutError
                bool2 = cancellationError
            },
            {
                "Received authentication error: errorCode: $int1, " +
                    "errString: $str1, " +
                    "isLockoutError: $bool1, " +
                    "isCancellationError: $bool2"
            }
        )
    }

    fun launchingQueuedFaceAuthRequest(faceAuthRequestedWhileCancellation: FaceAuthUiEvent?) {
        logBuffer.log(
            TAG,
            DEBUG,
            { str1 = "${faceAuthRequestedWhileCancellation?.reason}" },
            { "Received cancellation error and starting queued face auth request: $str1" }
        )
    }

    fun faceAuthSuccess(result: FaceManager.AuthenticationResult) {
        logBuffer.log(
            TAG,
            DEBUG,
            {
                int1 = result.userId
                bool1 = result.isStrongBiometric
            },
            { "Face authenticated successfully: userId: $int1, isStrongBiometric: $bool1" }
        )
    }
}
