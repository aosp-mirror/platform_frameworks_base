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

import android.os.RemoteException
import android.view.WindowManagerPolicyConstants
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.log.LogBuffer
import com.android.systemui.log.LogLevel.DEBUG
import com.android.systemui.log.LogLevel.ERROR
import com.android.systemui.log.LogLevel.INFO
import com.android.systemui.log.LogLevel.WARNING
import com.android.systemui.log.LogLevel.WTF
import com.android.systemui.log.dagger.KeyguardViewMediatorLog
import javax.inject.Inject

private const val TAG = "KeyguardViewMediatorLog"

@SysUISingleton
class KeyguardViewMediatorLogger @Inject constructor(
        @KeyguardViewMediatorLog private val logBuffer: LogBuffer,
) {

    fun logFailedLoadLockSound(soundPath: String) {
        logBuffer.log(
                TAG,
                WARNING,
                { str1 = soundPath },
                { "failed to load lock sound from $str1" }
        )
    }

    fun logFailedLoadUnlockSound(soundPath: String) {
        logBuffer.log(
                TAG,
                WARNING,
                { str1 = soundPath },
                { "failed to load unlock sound from $str1" }
        )
    }

    fun logFailedLoadTrustedSound(soundPath: String) {
        logBuffer.log(
                TAG,
                WARNING,
                { str1 = soundPath },
                { "failed to load trusted sound from $str1" }
        )
    }

    fun logOnSystemReady() {
        logBuffer.log(TAG, DEBUG, "onSystemReady")
    }

    fun logOnStartedGoingToSleep(offReason: Int) {
        val offReasonString = WindowManagerPolicyConstants.offReasonToString(offReason)
        logBuffer.log(
                TAG,
                DEBUG,
                { str1 = offReasonString },
                { "onStartedGoingToSleep($str1)" }
        )
    }

    fun logPendingExitSecureCallbackCancelled() {
        logBuffer.log(TAG, DEBUG, "pending exit secure callback cancelled")
    }

    fun logFailedOnKeyguardExitResultFalse(remoteException: RemoteException) {
        logBuffer.log(
                TAG,
                WARNING,
                "Failed to call onKeyguardExitResult(false)",
                remoteException
        )
    }

    fun logOnFinishedGoingToSleep(offReason: Int) {
        val offReasonString = WindowManagerPolicyConstants.offReasonToString(offReason)
        logBuffer.log(
                TAG,
                DEBUG,
                { str1 = offReasonString },
                { "onFinishedGoingToSleep($str1)" }
        )
    }

    fun logPinLockRequestedStartingKeyguard() {
        logBuffer.log(TAG, INFO, "PIN lock requested, starting keyguard")
    }

    fun logUserSwitching(userId: Int) {
        logBuffer.log(
                TAG,
                DEBUG,
                { int1 = userId },
                { "onUserSwitching $int1" }
        )
    }

    fun logOnUserSwitchComplete(userId: Int) {
        logBuffer.log(
                TAG,
                DEBUG,
                { int1 = userId },
                { "onUserSwitchComplete $int1" }
        )
    }

    fun logOnSimStateChanged(subId: Int, slotId: Int, simState: String) {
        logBuffer.log(
                TAG,
                DEBUG,
                {
                    int1 = subId
                    int2 = slotId
                    str1 = simState
                },
                { "onSimStateChanged(subId=$int1, slotId=$int2, state=$str1)" }
        )
    }

    fun logFailedToCallOnSimSecureStateChanged(remoteException: RemoteException) {
        logBuffer.log(
                TAG,
                WARNING,
                "Failed to call onSimSecureStateChanged",
                remoteException
        )
    }

    fun logIccAbsentIsNotShowing() {
        logBuffer.log(TAG, DEBUG, "ICC_ABSENT isn't showing, we need to show the " +
                "keyguard since the device isn't provisioned yet.")
    }

    fun logSimMovedToAbsent() {
        logBuffer.log(TAG, DEBUG, "SIM moved to ABSENT when the " +
                "previous state was locked. Reset the state.")
    }

    fun logIntentValueIccLocked() {
        logBuffer.log(TAG, DEBUG, "INTENT_VALUE_ICC_LOCKED and keyguard isn't " +
                "showing; need to show keyguard so user can enter sim pin")
    }

    fun logPermDisabledKeyguardNotShowing() {
        logBuffer.log(TAG, DEBUG, "PERM_DISABLED and keyguard isn't showing.")
    }

    fun logPermDisabledResetStateLocked() {
        logBuffer.log(TAG, DEBUG, "PERM_DISABLED, resetStateLocked to show permanently " +
                "disabled message in lockscreen.")
    }

    fun logReadyResetState(showing: Boolean) {
        logBuffer.log(
                TAG,
                DEBUG,
                { bool1 = showing },
                { "READY, reset state? $bool1"}
        )
    }

    fun logSimMovedToReady() {
        logBuffer.log(TAG, DEBUG, "SIM moved to READY when the previously was locked. " +
                "Reset the state.")
    }

    fun logUnspecifiedSimState(simState: Int) {
        logBuffer.log(
                TAG,
                DEBUG,
                { int1 = simState },
                { "Unspecific state: $int1" }
        )
    }

    fun logOccludeLaunchAnimationCancelled(occluded: Boolean) {
        logBuffer.log(
                TAG,
                DEBUG,
                { bool1 = occluded },
                { "Occlude launch animation cancelled. Occluded state is now: $bool1"}
        )
    }

    fun logActivityLaunchAnimatorLaunchContainerChanged() {
        logBuffer.log(TAG, WTF, "Someone tried to change the launch container for the " +
                "ActivityLaunchAnimator, which should never happen.")
    }

    fun logVerifyUnlock() {
        logBuffer.log(TAG, DEBUG, "verifyUnlock")
    }

    fun logIgnoreUnlockDeviceNotProvisioned() {
        logBuffer.log(TAG, DEBUG, "ignoring because device isn't provisioned")
    }

    fun logFailedToCallOnKeyguardExitResultFalse(remoteException: RemoteException) {
        logBuffer.log(
                TAG,
                WARNING,
                "Failed to call onKeyguardExitResult(false)",
                remoteException
        )
    }

    fun logVerifyUnlockCalledNotExternallyDisabled() {
        logBuffer.log(TAG, WARNING, "verifyUnlock called when not externally disabled")
    }

    fun logSetOccluded(isOccluded: Boolean) {
        logBuffer.log(
                TAG,
                DEBUG,
                { bool1 = isOccluded },
                { "setOccluded($bool1)" }
        )
    }

    fun logHandleSetOccluded(isOccluded: Boolean) {
        logBuffer.log(
                TAG,
                DEBUG,
                { bool1 = isOccluded },
                { "handleSetOccluded($bool1)" }
        )
    }

    fun logIgnoreHandleShow() {
        logBuffer.log(TAG, DEBUG, "ignoring handleShow because system is not ready.")
    }

    fun logHandleShow() {
        logBuffer.log(TAG, DEBUG, "handleShow")
    }

    fun logHandleHide() {
        logBuffer.log(TAG, DEBUG, "handleHide")
    }

    fun logSplitSystemUserQuitUnlocking() {
        logBuffer.log(TAG, DEBUG, "Split system user, quit unlocking.")
    }

    fun logHandleStartKeyguardExitAnimation(startTime: Long, fadeoutDuration: Long) {
        logBuffer.log(
                TAG,
                DEBUG,
                {
                    long1 = startTime
                    long2 = fadeoutDuration
                },
                { "handleStartKeyguardExitAnimation startTime=$long1 fadeoutDuration=$long2" }
        )
    }

    fun logHandleVerifyUnlock() {
        logBuffer.log(TAG, DEBUG, "handleVerifyUnlock")
    }

    fun logHandleNotifyStartedGoingToSleep() {
        logBuffer.log(TAG, DEBUG, "handleNotifyStartedGoingToSleep")
    }

    fun logHandleNotifyFinishedGoingToSleep() {
        logBuffer.log(TAG, DEBUG, "handleNotifyFinishedGoingToSleep")
    }

    fun logHandleNotifyWakingUp() {
        logBuffer.log(TAG, DEBUG, "handleNotifyWakingUp")
    }

    fun logHandleReset() {
        logBuffer.log(TAG, DEBUG, "handleReset")
    }

    fun logKeyguardDone() {
        logBuffer.log(TAG, DEBUG, "keyguardDone")
    }

    fun logKeyguardDonePending() {
        logBuffer.log(TAG, DEBUG, "keyguardDonePending")
    }

    fun logKeyguardGone() {
        logBuffer.log(TAG, DEBUG, "keyguardGone")
    }

    fun logUnoccludeAnimationCancelled(isOccluded: Boolean) {
        logBuffer.log(
                TAG,
                DEBUG,
                { bool1 = isOccluded },
                { "Unocclude animation cancelled. Occluded state is now: $bool1" }
        )
    }

    fun logShowLocked() {
        logBuffer.log(TAG, DEBUG, "showLocked")
    }

    fun logHideLocked() {
        logBuffer.log(TAG, DEBUG, "hideLocked")
    }

    fun logResetStateLocked() {
        logBuffer.log(TAG, DEBUG, "resetStateLocked")
    }

    fun logNotifyStartedGoingToSleep() {
        logBuffer.log(TAG, DEBUG, "notifyStartedGoingToSleep")
    }

    fun logNotifyFinishedGoingToSleep() {
        logBuffer.log(TAG, DEBUG, "notifyFinishedGoingToSleep")
    }

    fun logNotifyStartedWakingUp() {
        logBuffer.log(TAG, DEBUG, "notifyStartedWakingUp")
    }

    fun logDoKeyguardShowingLockScreen() {
        logBuffer.log(TAG, DEBUG, "doKeyguard: showing the lock screen")
    }

    fun logDoKeyguardNotShowingLockScreenOff() {
        logBuffer.log(TAG, DEBUG, "doKeyguard: not showing because lockscreen is off")
    }

    fun logDoKeyguardNotShowingDeviceNotProvisioned() {
        logBuffer.log(TAG, DEBUG, "doKeyguard: not showing because device isn't " +
                "provisioned and the sim is not locked or missing")
    }

    fun logDoKeyguardNotShowingAlreadyShowing() {
        logBuffer.log(TAG, DEBUG, "doKeyguard: not showing because it is already showing")
    }

    fun logDoKeyguardNotShowingBootingCryptkeeper() {
        logBuffer.log(TAG, DEBUG, "doKeyguard: not showing because booting to cryptkeeper")
    }

    fun logDoKeyguardNotShowingExternallyDisabled() {
        logBuffer.log(TAG, DEBUG, "doKeyguard: not showing because externally disabled")
    }

    fun logFailedToCallOnDeviceProvisioned(remoteException: RemoteException) {
        logBuffer.log(
                TAG,
                WARNING,
                "Failed to call onDeviceProvisioned",
                remoteException
        )
    }

    fun logMaybeHandlePendingLockNotHandling() {
        logBuffer.log(TAG, DEBUG, "#maybeHandlePendingLock: not handling because the " +
                "screen off animation's isKeyguardShowDelayed() returned true. This should be " +
                "handled soon by #onStartedWakingUp, or by the end actions of the " +
                "screen off animation.")
    }

    fun logMaybeHandlePendingLockKeyguardGoingAway() {
        logBuffer.log(TAG, DEBUG, "#maybeHandlePendingLock: not handling because the " +
                "keyguard is going away. This should be handled shortly by " +
                "StatusBar#finishKeyguardFadingAway.")
    }

    fun logMaybeHandlePendingLockHandling() {
        logBuffer.log(TAG, DEBUG, "#maybeHandlePendingLock: handling pending lock; " +
                "locking keyguard.")
    }

    fun logSetAlarmToTurnOffKeyguard(delayedShowingSequence: Int) {
        logBuffer.log(
                TAG,
                DEBUG,
                { int1 = delayedShowingSequence },
                { "setting alarm to turn off keyguard, seq = $int1" }
        )
    }

    fun logOnStartedWakingUp(delayedShowingSequence: Int) {
        logBuffer.log(
                TAG,
                DEBUG,
                { int1 = delayedShowingSequence },
                { "onStartedWakingUp, seq = $int1" }
        )
    }

    fun logSetKeyguardEnabled(enabled: Boolean) {
        logBuffer.log(
                TAG,
                DEBUG,
                { bool1 = enabled },
                { "setKeyguardEnabled($bool1)" }
        )
    }

    fun logIgnoreVerifyUnlockRequest() {
        logBuffer.log(TAG, DEBUG, "in process of verifyUnlock request, ignoring")
    }

    fun logRememberToReshowLater() {
        logBuffer.log(TAG, DEBUG, "remembering to reshow, hiding keyguard, disabling " +
                "status bar expansion")
    }

    fun logPreviouslyHiddenReshow() {
        logBuffer.log(TAG, DEBUG, "previously hidden, reshowing, reenabling status " +
                "bar expansion")
    }

    fun logOnKeyguardExitResultFalseResetting() {
        logBuffer.log(TAG, DEBUG, "onKeyguardExitResult(false), resetting")
    }

    fun logWaitingUntilKeyguardVisibleIsFalse() {
        logBuffer.log(TAG, DEBUG, "waiting until mWaitingUntilKeyguardVisible is false")
    }

    fun logDoneWaitingUntilKeyguardVisible() {
        logBuffer.log(TAG, DEBUG, "done waiting for mWaitingUntilKeyguardVisible")
    }

    fun logUnoccludeAnimatorOnAnimationStart() {
        logBuffer.log(TAG, DEBUG, "UnoccludeAnimator#onAnimationStart. " +
                "Set occluded = false.")
    }

    fun logNoAppsProvidedToUnoccludeRunner() {
        logBuffer.log(TAG, DEBUG, "No apps provided to unocclude runner; " +
                "skipping animation and unoccluding.")
    }

    fun logReceivedDelayedKeyguardAction(sequence: Int, delayedShowingSequence: Int) {
        logBuffer.log(
                TAG,
                DEBUG,
                {
                    int1 = sequence
                    int2 = delayedShowingSequence
                },
                {
                    "received DELAYED_KEYGUARD_ACTION with seq = $int1 " +
                            "mDelayedShowingSequence = $int2"
                }
        )
    }

    fun logTimeoutWhileActivityDrawn() {
        logBuffer.log(TAG, WARNING, "Timeout while waiting for activity drawn")
    }

    fun logTryKeyguardDonePending(
            keyguardDonePending: Boolean,
            hideAnimationRun: Boolean,
            hideAnimationRunning: Boolean
    ) {
        logBuffer.log(TAG, DEBUG,
                {
                    bool1 = keyguardDonePending
                    bool2 = hideAnimationRun
                    bool3 = hideAnimationRunning
                },
                { "tryKeyguardDone: pending - $bool1, animRan - $bool2 animRunning - $bool3" }
        )
    }

    fun logTryKeyguardDonePreHideAnimation() {
        logBuffer.log(TAG, DEBUG, "tryKeyguardDone: starting pre-hide animation")
    }

    fun logHandleKeyguardDone() {
        logBuffer.log(TAG, DEBUG, "handleKeyguardDone")
    }

    fun logDeviceGoingToSleep() {
        logBuffer.log(TAG, INFO, "Device is going to sleep, aborting keyguardDone")
    }

    fun logFailedToCallOnKeyguardExitResultTrue(remoteException: RemoteException) {
        logBuffer.log(
                TAG,
                WARNING,
                "Failed to call onKeyguardExitResult(true)",
                remoteException
        )
    }

    fun logHandleKeyguardDoneDrawing() {
        logBuffer.log(TAG, DEBUG, "handleKeyguardDoneDrawing")
    }

    fun logHandleKeyguardDoneDrawingNotifyingKeyguardVisible() {
        logBuffer.log(TAG, DEBUG, "handleKeyguardDoneDrawing: notifying " +
                "mWaitingUntilKeyguardVisible")
    }

    fun logUpdateActivityLockScreenState(showing: Boolean, aodShowing: Boolean) {
        logBuffer.log(
                TAG,
                DEBUG,
                {
                    bool1 = showing
                    bool2 = aodShowing
                },
                { "updateActivityLockScreenState($bool1, $bool2)" }
        )
    }

    fun logFailedToCallSetLockScreenShown(remoteException: RemoteException) {
        logBuffer.log(
                TAG,
                WARNING,
                "Failed to call setLockScreenShown",
                remoteException
        )
    }

    fun logKeyguardGoingAway() {
        logBuffer.log(TAG, DEBUG, "keyguardGoingAway")
    }

    fun logFailedToCallKeyguardGoingAway(keyguardFlag: Int, remoteException: RemoteException) {
        logBuffer.log(
                TAG,
                ERROR,
                { int1 = keyguardFlag },
                { "Failed to call keyguardGoingAway($int1)" },
                remoteException
        )
    }

    fun logHideAnimationFinishedRunnable() {
        logBuffer.log(TAG, WARNING, "mHideAnimationFinishedRunnable#run")
    }

    fun logFailedToCallOnAnimationFinished(remoteException: RemoteException) {
        logBuffer.log(
                TAG,
                WARNING,
                "Failed to call onAnimationFinished",
                remoteException
        )
    }

    fun logFailedToCallOnAnimationStart(remoteException: RemoteException) {
        logBuffer.log(
                TAG,
                WARNING,
                "Failed to call onAnimationStart",
                remoteException
        )
    }

    fun logOnKeyguardExitRemoteAnimationFinished() {
        logBuffer.log(TAG, DEBUG, "onKeyguardExitRemoteAnimationFinished")
    }

    fun logSkipOnKeyguardExitRemoteAnimationFinished(
            cancelled: Boolean,
            surfaceBehindRemoteAnimationRunning: Boolean,
            surfaceBehindRemoteAnimationRequested: Boolean
    ) {
        logBuffer.log(
                TAG,
                DEBUG,
                {
                    bool1 = cancelled
                    bool2 = surfaceBehindRemoteAnimationRunning
                    bool3 = surfaceBehindRemoteAnimationRequested
                },
                {
                    "skip onKeyguardExitRemoteAnimationFinished cancelled=$bool1 " +
                            "surfaceAnimationRunning=$bool2 " +
                            "surfaceAnimationRequested=$bool3"
                }
        )
    }

    fun logOnKeyguardExitRemoteAnimationFinishedHideKeyguardView() {
        logBuffer.log(TAG, DEBUG, "onKeyguardExitRemoteAnimationFinished" +
                "#hideKeyguardViewAfterRemoteAnimation")
    }

    fun logSkipHideKeyguardViewAfterRemoteAnimation(
            dismissingFromSwipe: Boolean,
            wasShowing: Boolean
    ) {
        logBuffer.log(
                TAG,
                DEBUG,
                {
                    bool1 = dismissingFromSwipe
                    bool2 = wasShowing
                },
                {
                    "skip hideKeyguardViewAfterRemoteAnimation dismissFromSwipe=$bool1 " +
                            "wasShowing=$bool2"
                }
        )
    }

    fun logCouldNotGetStatusBarManager() {
        logBuffer.log(TAG, WARNING, "Could not get status bar manager")
    }

    fun logAdjustStatusBarLocked(
            showing: Boolean,
            occluded: Boolean,
            secure: Boolean,
            forceHideHomeRecentsButtons: Boolean,
            flags: String
    ) {
        logBuffer.log(
                TAG,
                DEBUG,
                {
                    bool1 = showing
                    bool2 = occluded
                    bool3 = secure
                    bool4 = forceHideHomeRecentsButtons
                    str3 = flags
                },
                {
                    "adjustStatusBarLocked: mShowing=$bool1 mOccluded=$bool2 isSecure=$bool3 " +
                            "force=$bool4 --> flags=0x$str3"
                }
        )
    }

    fun logFailedToCallOnShowingStateChanged(remoteException: RemoteException) {
        logBuffer.log(
                TAG,
                WARNING,
                "Failed to call onShowingStateChanged",
                remoteException
        )
    }

    fun logFailedToCallNotifyTrustedChangedLocked(remoteException: RemoteException) {
        logBuffer.log(
                TAG,
                WARNING,
                "Failed to call notifyTrustedChangedLocked",
                remoteException
        )
    }

    fun logFailedToCallIKeyguardStateCallback(remoteException: RemoteException) {
        logBuffer.log(
                TAG,
                WARNING,
                "Failed to call to IKeyguardStateCallback",
                remoteException
        )
    }

    fun logOccludeAnimatorOnAnimationStart() {
        logBuffer.log(TAG, DEBUG, "OccludeAnimator#onAnimationStart. Set occluded = true.")
    }

    fun logOccludeAnimationCancelledByWm(isKeyguardOccluded: Boolean) {
        logBuffer.log(
                TAG,
                DEBUG,
                { bool1 = isKeyguardOccluded },
                { "Occlude animation cancelled by WM. Setting occluded state to: $bool1" }
        )
    }
}