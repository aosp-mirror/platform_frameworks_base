package com.android.systemui.log

import android.hardware.face.FaceManager
import android.hardware.face.FaceSensorPropertiesInternal
import com.android.keyguard.FaceAuthUiEvent
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.keyguard.shared.model.ErrorAuthenticationStatus
import com.android.systemui.keyguard.shared.model.TransitionStep
import com.android.systemui.log.LogLevel.DEBUG
import com.android.systemui.log.dagger.FaceAuthLog
import javax.inject.Inject

private const val TAG = "DeviceEntryFaceAuthRepositoryLog"

/**
 * Helper class for logging for
 * [com.android.systemui.keyguard.data.repository.DeviceEntryFaceAuthRepository]
 *
 * To enable logcat echoing for an entire buffer:
 * ```
 *   adb shell settings put global systemui/buffer/DeviceEntryFaceAuthRepositoryLog <logLevel>
 *
 * ```
 */
@SysUISingleton
class FaceAuthenticationLogger
@Inject
constructor(
    @FaceAuthLog private val logBuffer: LogBuffer,
) {
    fun ignoredFaceAuthTrigger(uiEvent: FaceAuthUiEvent, ignoredReason: String) {
        logBuffer.log(
            TAG,
            DEBUG,
            {
                str1 = uiEvent.reason
                str2 = ignoredReason
            },
            { "Ignoring trigger because $str2, Trigger reason: $str1" }
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

    fun skippingDetection(isAuthRunning: Boolean, detectCancellationNotNull: Boolean) {
        logBuffer.log(
            TAG,
            DEBUG,
            {
                bool1 = isAuthRunning
                bool2 = detectCancellationNotNull
            },
            {
                "Skipping running detection: isAuthRunning: $bool1, " +
                    "detectCancellationNotNull: $bool2"
            }
        )
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

    fun observedConditionChanged(newValue: Boolean, context: String) {
        logBuffer.log(
            TAG,
            DEBUG,
            {
                bool1 = newValue
                str1 = context
            },
            { "Observed condition changed: $str1, new value: $bool1" }
        )
    }

    fun canFaceAuthRunChanged(canRun: Boolean) {
        logBuffer.log(TAG, DEBUG, { bool1 = canRun }, { "canFaceAuthRun value changed to $bool1" })
    }

    fun canRunDetectionChanged(canRunDetection: Boolean) {
        logBuffer.log(
            TAG,
            DEBUG,
            { bool1 = canRunDetection },
            { "canRunDetection value changed to $bool1" }
        )
    }

    fun cancellingFaceAuth() {
        logBuffer.log(TAG, DEBUG, "cancelling face auth because a gating condition became false")
    }

    fun interactorStarted() {
        logBuffer.log(TAG, DEBUG, "KeyguardFaceAuthInteractor started")
    }

    fun bouncerVisibilityChanged() {
        logBuffer.log(TAG, DEBUG, "Triggering face auth because primary bouncer is visible")
    }

    fun alternateBouncerVisibilityChanged() {
        logBuffer.log(TAG, DEBUG, "Triggering face auth because alternate bouncer is visible")
    }

    fun lockscreenBecameVisible(transitionStep: TransitionStep?) {
        logBuffer.log(
            TAG,
            DEBUG,
            { str1 = "$transitionStep" },
            { "Triggering face auth because lockscreen became visible due to transition: $str1" }
        )
    }

    fun authRequested(uiEvent: FaceAuthUiEvent) {
        logBuffer.log(
            TAG,
            DEBUG,
            { str1 = "$uiEvent" },
            { "Requesting face auth for trigger: $str1" }
        )
    }

    fun hardwareError(errorStatus: ErrorAuthenticationStatus) {
        logBuffer.log(
            TAG,
            DEBUG,
            {
                str1 = "${errorStatus.msg}"
                int1 = errorStatus.msgId
            },
            { "Received face hardware error: $str1 , code: $int1" }
        )
    }

    fun attemptingRetryAfterHardwareError(retryCount: Int) {
        logBuffer.log(
            TAG,
            DEBUG,
            { int1 = retryCount },
            { "Attempting face auth again because of HW error: retry attempt $int1" }
        )
    }

    fun watchdogScheduled() {
        logBuffer.log(TAG, DEBUG, "FaceManager Biometric watchdog scheduled.")
    }
}
