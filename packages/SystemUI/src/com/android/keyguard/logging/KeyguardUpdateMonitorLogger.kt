/*
 * Copyright (C) 2022 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.keyguard.logging

import android.hardware.biometrics.BiometricConstants.LockoutMode
import android.os.PowerManager
import android.os.PowerManager.WakeReason
import android.telephony.ServiceState
import android.telephony.SubscriptionInfo
import com.android.keyguard.ActiveUnlockConfig
import com.android.keyguard.FaceAuthUiEvent
import com.android.keyguard.KeyguardListenModel
import com.android.keyguard.KeyguardUpdateMonitorCallback
import com.android.keyguard.TrustGrantFlags
import com.android.systemui.plugins.log.LogBuffer
import com.android.systemui.plugins.log.LogLevel
import com.android.systemui.plugins.log.LogLevel.DEBUG
import com.android.systemui.plugins.log.LogLevel.ERROR
import com.android.systemui.plugins.log.LogLevel.INFO
import com.android.systemui.plugins.log.LogLevel.VERBOSE
import com.android.systemui.plugins.log.LogLevel.WARNING
import com.android.systemui.log.dagger.KeyguardUpdateMonitorLog
import com.google.errorprone.annotations.CompileTimeConstant
import javax.inject.Inject

private const val TAG = "KeyguardUpdateMonitorLog"

/**
 * Helper class for logging for [com.android.keyguard.KeyguardUpdateMonitor]
 */
class KeyguardUpdateMonitorLogger @Inject constructor(
        @KeyguardUpdateMonitorLog private val logBuffer: LogBuffer
) {
    fun d(@CompileTimeConstant msg: String) = log(msg, DEBUG)

    fun e(@CompileTimeConstant msg: String) = log(msg, ERROR)

    fun v(@CompileTimeConstant msg: String) = log(msg, VERBOSE)

    fun w(@CompileTimeConstant msg: String) = log(msg, WARNING)

    fun log(@CompileTimeConstant msg: String, level: LogLevel) = logBuffer.log(TAG, level, msg)

    fun logActiveUnlockTriggered(reason: String?) {
        logBuffer.log("ActiveUnlock", DEBUG,
                { str1 = reason },
                { "initiate active unlock triggerReason=$str1" })
    }

    fun logAuthInterruptDetected(active: Boolean) {
        logBuffer.log(TAG, DEBUG,
                { bool1 = active },
                { "onAuthInterruptDetected($bool1)" })
    }

    fun logBroadcastReceived(action: String?) {
        logBuffer.log(TAG, DEBUG, { str1 = action }, { "received broadcast $str1" })
    }

    fun logDeviceProvisionedState(deviceProvisioned: Boolean) {
        logBuffer.log(TAG, DEBUG,
                { bool1 = deviceProvisioned },
                { "DEVICE_PROVISIONED state = $bool1" })
    }

    fun logException(ex: Exception, @CompileTimeConstant logMsg: String) {
        logBuffer.log(TAG, ERROR, {}, { logMsg }, exception = ex)
    }

    fun logFaceAcquired(acquireInfo: Int) {
        logBuffer.log(TAG, DEBUG,
                { int1 = acquireInfo },
                { "Face acquired acquireInfo=$int1" })
    }

    fun logFaceAuthDisabledForUser(userId: Int) {
        logBuffer.log(TAG, DEBUG,
                { int1 = userId },
                { "Face authentication disabled by DPM for userId: $int1" })
    }
    fun logFaceAuthError(msgId: Int, originalErrMsg: String) {
        logBuffer.log(TAG, DEBUG, {
                    str1 = originalErrMsg
                    int1 = msgId
                }, { "Face error received: $str1 msgId= $int1" })
    }

    fun logFaceAuthForWrongUser(authUserId: Int) {
        logBuffer.log(TAG, DEBUG,
                { int1 = authUserId },
                { "Face authenticated for wrong user: $int1" })
    }

    fun logFaceAuthHelpMsg(msgId: Int, helpMsg: String?) {
        logBuffer.log(TAG, DEBUG, {
                    int1 = msgId
                    str1 = helpMsg
                }, { "Face help received, msgId: $int1 msg: $str1" })
    }

    fun logFaceAuthRequested(reason: String?) {
        logBuffer.log(TAG, DEBUG, {
            str1 = reason
        }, { "requestFaceAuth() reason=$str1" })
    }

    fun logFaceAuthSuccess(userId: Int) {
        logBuffer.log(TAG, DEBUG,
                { int1 = userId },
                { "Face auth succeeded for user $int1" })
    }

    fun logFaceLockoutReset(@LockoutMode mode: Int) {
        logBuffer.log(TAG, DEBUG, { int1 = mode }, { "handleFaceLockoutReset: $int1" })
    }

    fun logFaceRunningState(faceRunningState: Int) {
        logBuffer.log(TAG, DEBUG, { int1 = faceRunningState }, { "faceRunningState: $int1" })
    }

    fun logFaceUnlockPossible(isFaceUnlockPossible: Boolean) {
        logBuffer.log(TAG, DEBUG,
                { bool1 = isFaceUnlockPossible },
                {"isUnlockWithFacePossible: $bool1"})
    }

    fun logFingerprintAuthForWrongUser(authUserId: Int) {
        logBuffer.log(TAG, DEBUG,
                { int1 = authUserId },
                { "Fingerprint authenticated for wrong user: $int1" })
    }

    fun logFingerprintDisabledForUser(userId: Int) {
        logBuffer.log(TAG, DEBUG,
                { int1 = userId },
                { "Fingerprint disabled by DPM for userId: $int1" })
    }

    fun logFingerprintLockoutReset(@LockoutMode mode: Int) {
        logBuffer.log(TAG, DEBUG, { int1 = mode }, { "handleFingerprintLockoutReset: $int1" })
    }

    fun logFingerprintRunningState(fingerprintRunningState: Int) {
        logBuffer.log(TAG, DEBUG,
                { int1 = fingerprintRunningState },
                { "fingerprintRunningState: $int1" })
    }

    fun logFingerprintSuccess(userId: Int, isStrongBiometric: Boolean) {
        logBuffer.log(TAG, DEBUG, {
            int1 = userId
            bool1 = isStrongBiometric
        }, {"Fingerprint auth successful: userId: $int1, isStrongBiometric: $bool1"})
    }

    fun logFingerprintError(msgId: Int, originalErrMsg: String) {
        logBuffer.log(TAG, DEBUG, {
            str1 = originalErrMsg
            int1 = msgId
        }, { "Fingerprint error received: $str1 msgId= $int1" })
    }

    fun logInvalidSubId(subId: Int) {
        logBuffer.log(TAG, INFO,
                { int1 = subId },
                { "Previously active sub id $int1 is now invalid, will remove" })
    }

    fun logPrimaryKeyguardBouncerChanged(
            primaryBouncerIsOrWillBeShowing: Boolean,
            primaryBouncerFullyShown: Boolean
    ) {
        logBuffer.log(TAG, DEBUG, {
            bool1 = primaryBouncerIsOrWillBeShowing
            bool2 = primaryBouncerFullyShown
        }, {
            "handlePrimaryBouncerChanged " +
                    "primaryBouncerIsOrWillBeShowing=$bool1 primaryBouncerFullyShown=$bool2"
        })
    }

    fun logKeyguardListenerModel(model: KeyguardListenModel) {
        logBuffer.log(TAG, VERBOSE, { str1 = "$model" }, { str1!! })
    }

    fun logKeyguardShowingChanged(showing: Boolean, occluded: Boolean, visible: Boolean) {
        logBuffer.log(TAG, DEBUG, {
            bool1 = showing
            bool2 = occluded
            bool3 = visible
        }, {
            "keyguardShowingChanged(showing=$bool1 occluded=$bool2 visible=$bool3)"
        })
    }

    fun logMissingSupervisorAppError(userId: Int) {
        logBuffer.log(TAG, ERROR,
                { int1 = userId },
                { "No Profile Owner or Device Owner supervision app found for User $int1" })
    }

    fun logPhoneStateChanged(newState: String?) {
        logBuffer.log(TAG, DEBUG,
                { str1 = newState },
                { "handlePhoneStateChanged($str1)" })
    }

    fun logRegisterCallback(callback: KeyguardUpdateMonitorCallback?) {
        logBuffer.log(TAG, VERBOSE,
                { str1 = "$callback" },
                { "*** register callback for $str1" })
    }

    fun logRetryingAfterFaceHwUnavailable(retryCount: Int) {
        logBuffer.log(TAG, WARNING,
                { int1 = retryCount },
                { "Retrying face after HW unavailable, attempt $int1" })
    }

    fun logRetryAfterFpErrorWithDelay(msgId: Int, errString: String?, delay: Int) {
        logBuffer.log(TAG, DEBUG, {
            int1 = msgId
            int2 = delay
            str1 = "$errString"
        }, {
            "Fingerprint scheduling retry auth after $int2 ms due to($int1) -> $str1"
        })
    }

    fun logRetryAfterFpHwUnavailable(retryCount: Int) {
        logBuffer.log(TAG, WARNING,
                { int1 = retryCount },
                { "Retrying fingerprint attempt: $int1" })
    }

    fun logSendPrimaryBouncerChanged(
        primaryBouncerIsOrWillBeShowing: Boolean,
        primaryBouncerFullyShown: Boolean,
    ) {
        logBuffer.log(TAG, DEBUG, {
            bool1 = primaryBouncerIsOrWillBeShowing
            bool2 = primaryBouncerFullyShown
        }, {
            "sendPrimaryBouncerChanged primaryBouncerIsOrWillBeShowing=$bool1 " +
                    "primaryBouncerFullyShown=$bool2"
        })
    }

    fun logServiceStateChange(subId: Int, serviceState: ServiceState?) {
        logBuffer.log(TAG, DEBUG, {
            int1 = subId
            str1 = "$serviceState"
        }, { "handleServiceStateChange(subId=$int1, serviceState=$str1)" })
    }

    fun logServiceStateIntent(action: String?, serviceState: ServiceState?, subId: Int) {
        logBuffer.log(TAG, VERBOSE, {
            str1 = action
            str2 = "$serviceState"
            int1 = subId
        }, { "action $str1 serviceState=$str2 subId=$int1" })
    }

    fun logSimState(subId: Int, slotId: Int, state: Int) {
        logBuffer.log(TAG, DEBUG, {
            int1 = subId
            int2 = slotId
            long1 = state.toLong()
        }, { "handleSimStateChange(subId=$int1, slotId=$int2, state=$long1)" })
    }

    fun logSimStateFromIntent(action: String?, extraSimState: String?, slotId: Int, subId: Int) {
        logBuffer.log(TAG, VERBOSE, {
            str1 = action
            str2 = extraSimState
            int1 = slotId
            int2 = subId
        }, { "action $str1 state: $str2 slotId: $int1 subid: $int2" })
    }

    fun logSimUnlocked(subId: Int) {
        logBuffer.log(TAG, VERBOSE, { int1 = subId }, { "reportSimUnlocked(subId=$int1)" })
    }

    fun logStartedListeningForFace(faceRunningState: Int, faceAuthUiEvent: FaceAuthUiEvent) {
        logBuffer.log(TAG, VERBOSE, {
            int1 = faceRunningState
            str1 = faceAuthUiEvent.reason
            str2 = faceAuthUiEvent.extraInfoToString()
        }, { "startListeningForFace(): $int1, reason: $str1 $str2" })
    }

    fun logStartedListeningForFaceFromWakeUp(faceRunningState: Int, @WakeReason pmWakeReason: Int) {
        logBuffer.log(TAG, VERBOSE, {
            int1 = faceRunningState
            str1 = PowerManager.wakeReasonToString(pmWakeReason)
        }, { "startListeningForFace(): $int1, reason: wakeUp-$str1" })
    }

    fun logStoppedListeningForFace(faceRunningState: Int, faceAuthReason: String) {
        logBuffer.log(TAG, VERBOSE, {
            int1 = faceRunningState
            str1 = faceAuthReason
        }, { "stopListeningForFace(): currentFaceRunningState: $int1, reason: $str1" })
    }

    fun logSubInfo(subInfo: SubscriptionInfo?) {
        logBuffer.log(TAG, VERBOSE,
                { str1 = "$subInfo" },
                { "SubInfo:$str1" })
    }

    fun logTimeFormatChanged(newTimeFormat: String?) {
        logBuffer.log(TAG, DEBUG,
                { str1 = newTimeFormat },
                { "handleTimeFormatUpdate timeFormat=$str1" })
    }
    fun logUdfpsPointerDown(sensorId: Int) {
        logBuffer.log(TAG, DEBUG,
                { int1 = sensorId },
                { "onUdfpsPointerDown, sensorId: $int1" })
    }

    fun logUdfpsPointerUp(sensorId: Int) {
        logBuffer.log(TAG, DEBUG,
                { int1 = sensorId },
                { "onUdfpsPointerUp, sensorId: $int1" })
    }

    fun logUnexpectedFaceCancellationSignalState(faceRunningState: Int, unlockPossible: Boolean) {
        logBuffer.log(TAG, ERROR, {
                    int1 = faceRunningState
                    bool1 = unlockPossible
                }, {
                    "Cancellation signal is not null, high chance of bug in " +
                            "face auth lifecycle management. " +
                            "Face state: $int1, unlockPossible: $bool1"
                })
    }

    fun logUnexpectedFpCancellationSignalState(
        fingerprintRunningState: Int,
        unlockPossible: Boolean
    ) {
        logBuffer.log(TAG, ERROR, {
                    int1 = fingerprintRunningState
                    bool1 = unlockPossible
                }, {
                    "Cancellation signal is not null, high chance of bug in " +
                            "fp auth lifecycle management. FP state: $int1, unlockPossible: $bool1"
                })
    }

    fun logUnregisterCallback(callback: KeyguardUpdateMonitorCallback?) {
        logBuffer.log(TAG, VERBOSE,
                { str1 = "$callback" },
                { "*** unregister callback for $str1" })
    }

    fun logUserRequestedUnlock(
        requestOrigin: ActiveUnlockConfig.ACTIVE_UNLOCK_REQUEST_ORIGIN,
        reason: String?,
        dismissKeyguard: Boolean
    ) {
        logBuffer.log("ActiveUnlock", DEBUG, {
                    str1 = requestOrigin?.name
                    str2 = reason
                    bool1 = dismissKeyguard
                }, { "reportUserRequestedUnlock origin=$str1 reason=$str2 dismissKeyguard=$bool1" })
    }

    fun logTrustGrantedWithFlags(
            flags: Int,
            newlyUnlocked: Boolean,
            userId: Int,
            message: String?
    ) {
        logBuffer.log(TAG, DEBUG, {
            int1 = flags
            bool1 = newlyUnlocked
            int2 = userId
            str1 = message
        }, { "trustGrantedWithFlags[user=$int2] newlyUnlocked=$bool1 " +
                "flags=${TrustGrantFlags(int1)} message=$str1" })
    }

    fun logTrustChanged(
            wasTrusted: Boolean,
            isNowTrusted: Boolean,
            userId: Int
    ) {
        logBuffer.log(TAG, DEBUG, {
            bool1 = wasTrusted
            bool2 = isNowTrusted
            int1 = userId
        }, { "onTrustChanged[user=$int1] wasTrusted=$bool1 isNowTrusted=$bool2" })
    }

    fun logKeyguardStateUpdate(
            secure: Boolean,
            canDismissLockScreen: Boolean,
            trusted: Boolean,
            trustManaged: Boolean

    ) {
        logBuffer.log("KeyguardState", DEBUG, {
            bool1 = secure
            bool2 = canDismissLockScreen
            bool3 = trusted
            bool4 = trustManaged
        }, { "#update secure=$bool1 canDismissKeyguard=$bool2" +
                " trusted=$bool3 trustManaged=$bool4" })
    }

    fun logSkipUpdateFaceListeningOnWakeup(@WakeReason pmWakeReason: Int) {
        logBuffer.log(TAG, VERBOSE, {
            str1 = PowerManager.wakeReasonToString(pmWakeReason)
        }, { "Skip updating face listening state on wakeup from $str1"})
    }
}
