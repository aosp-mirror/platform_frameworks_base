/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.systemui.keyguard.domain.interactor

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.provider.Settings
import android.provider.Settings.Secure
import com.android.internal.widget.LockPatternUtils
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.keyguard.KeyguardViewMediator
import com.android.systemui.keyguard.KeyguardWmStateRefactor
import com.android.systemui.keyguard.data.repository.KeyguardRepository
import com.android.systemui.keyguard.shared.model.BiometricUnlockMode
import com.android.systemui.keyguard.shared.model.KeyguardState
import com.android.systemui.keyguard.shared.model.KeyguardState.Companion.deviceIsAsleepInState
import com.android.systemui.keyguard.shared.model.KeyguardState.Companion.deviceIsAwakeInState
import com.android.systemui.power.domain.interactor.PowerInteractor
import com.android.systemui.power.shared.model.WakeSleepReason
import com.android.systemui.scene.shared.model.Scenes
import com.android.systemui.user.domain.interactor.SelectedUserInteractor
import com.android.systemui.util.kotlin.sample
import com.android.systemui.util.settings.SecureSettings
import com.android.systemui.util.settings.SystemSettings
import com.android.systemui.util.time.SystemClock
import javax.inject.Inject
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Logic related to the ability to wake directly to GONE from asleep (AOD/DOZING), without going
 * through LOCKSCREEN or a BOUNCER state.
 *
 * This is possible in the following scenarios:
 * - The lockscreen is disabled, either from an app request (SUW does this), or by the security
 *   "None" setting.
 * - A biometric authentication event occurred while we were asleep (fingerprint auth, etc). This
 *   specifically is referred to throughout the codebase as "wake and unlock".
 * - The screen timed out, but the "lock after screen timeout" duration has not elapsed.
 * - The power button was pressed, but "power button instantly locks" is disabled and the "lock
 *   after screen timeout" duration has not elapsed.
 *
 * In these cases, no (further) authentication is required, and we can transition directly from
 * AOD/DOZING -> GONE.
 */
@SysUISingleton
class KeyguardWakeDirectlyToGoneInteractor
@Inject
constructor(
    @Application private val scope: CoroutineScope,
    private val context: Context,
    private val repository: KeyguardRepository,
    private val systemClock: SystemClock,
    private val alarmManager: AlarmManager,
    private val transitionInteractor: KeyguardTransitionInteractor,
    private val powerInteractor: PowerInteractor,
    private val secureSettings: SecureSettings,
    private val lockPatternUtils: LockPatternUtils,
    private val systemSettings: SystemSettings,
    private val selectedUserInteractor: SelectedUserInteractor,
) {

    /**
     * Whether the lockscreen was disabled as of the last wake/sleep event, according to
     * LockPatternUtils.
     *
     * This will always be true if [repository.isKeyguardServiceEnabled]=false, but it can also be
     * true when the keyguard service is enabled if the lockscreen has been disabled via adb using
     * the `adb shell locksettings set-disabled true` command, which is often done in tests.
     *
     * Unlike keyguardServiceEnabled, changes to this value should *not* immediately show or hide
     * the keyguard. If the lockscreen is disabled in this way, it will just not show on the next
     * sleep/wake.
     */
    private val isLockscreenDisabled: Flow<Boolean> =
        powerInteractor.isAwake.map { isLockscreenDisabled() }

    /**
     * Whether we can wake from AOD/DOZING directly to GONE, bypassing LOCKSCREEN/BOUNCER states.
     *
     * This is possible in the following cases:
     * - Keyguard is disabled, either from an app request or from security being set to "None".
     * - We're wake and unlocking (fingerprint auth occurred while asleep).
     * - We're allowed to ignore auth and return to GONE, due to timeouts not elapsing.
     */
    val canWakeDirectlyToGone =
        combine(
                repository.isKeyguardEnabled,
                isLockscreenDisabled,
                repository.biometricUnlockState,
                repository.canIgnoreAuthAndReturnToGone,
            ) {
                keyguardEnabled,
                isLockscreenDisabled,
                biometricUnlockState,
                canIgnoreAuthAndReturnToGone ->
                (!keyguardEnabled || isLockscreenDisabled) ||
                    BiometricUnlockMode.isWakeAndUnlock(biometricUnlockState.mode) ||
                    canIgnoreAuthAndReturnToGone
            }
            .distinctUntilChanged()

    /**
     * Counter that is incremented every time we wake up or stop dreaming. Upon sleeping/dreaming,
     * we put the current value of this counter into the intent extras of the timeout alarm intent.
     * If this value has changed by the time we receive the intent, it is discarded since it's out
     * of date.
     */
    var timeoutCounter = 0

    var isAwake = false

    private val broadcastReceiver: BroadcastReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (DELAYED_KEYGUARD_ACTION == intent.action) {
                    val sequence = intent.getIntExtra(SEQ_EXTRA_KEY, 0)
                    synchronized(this) {
                        if (timeoutCounter == sequence) {
                            // If the sequence # matches, we have not woken up or stopped dreaming
                            // since
                            // the alarm was set. That means this is still relevant - the lock
                            // timeout
                            // has elapsed, so let the repository know that we can no longer return
                            // to
                            // GONE without authenticating.
                            repository.setCanIgnoreAuthAndReturnToGone(false)
                        }
                    }
                }
            }
        }

    init {
        setOrCancelAlarmFromWakefulness()
        listenForWakeToClearCanIgnoreAuth()
        registerBroadcastReceiver()
    }

    fun onDreamingStarted() {
        // If we start dreaming while awake, lock after the normal timeout.
        if (isAwake) {
            setResetCanIgnoreAuthAlarm()
        }
    }

    fun onDreamingStopped() {
        // Cancel the timeout if we stop dreaming while awake.
        if (isAwake) {
            cancelCanIgnoreAuthAlarm()
        }
    }

    private fun setOrCancelAlarmFromWakefulness() {
        scope.launch {
            powerInteractor.detailedWakefulness
                .distinctUntilChangedBy { it.isAwake() }
                .sample(
                    transitionInteractor.isCurrentlyIn(
                        Scenes.Gone,
                        stateWithoutSceneContainer = KeyguardState.GONE
                    ),
                    ::Pair
                )
                .collect { (wakefulness, finishedInGone) ->
                    // Save isAwake for use in onDreamingStarted/onDreamingStopped.
                    this@KeyguardWakeDirectlyToGoneInteractor.isAwake = wakefulness.isAwake()

                    // If we're sleeping from GONE, check the timeout and lock instantly settings.
                    // These are not relevant if we're coming from non-GONE states.
                    if (!isAwake && finishedInGone) {
                        val lockTimeoutDuration = getCanIgnoreAuthAndReturnToGoneDuration()

                        // If the screen timed out and went to sleep, and the lock timeout is > 0ms,
                        // then we can return to GONE until that duration elapses. If the power
                        // button was pressed but "instantly locks" is disabled, then we can also
                        // return to GONE until the timeout duration elapses.
                        if (
                            (wakefulness.lastSleepReason == WakeSleepReason.TIMEOUT &&
                                lockTimeoutDuration > 0) ||
                                (wakefulness.lastSleepReason == WakeSleepReason.POWER_BUTTON &&
                                    !willLockImmediately())
                        ) {

                            // Let the repository know that we can return to GONE until we notify
                            // it otherwise.
                            repository.setCanIgnoreAuthAndReturnToGone(true)
                            setResetCanIgnoreAuthAlarm()
                        }
                    } else if (isAwake) {
                        // If we're waking up, ignore the alarm if it goes off since it's no longer
                        // relevant. Once a wake KeyguardTransition is started, we'll also clear the
                        // canIgnoreAuthAndReturnToGone value in listenForWakeToClearCanIgnoreAuth.
                        cancelCanIgnoreAuthAlarm()
                    }
                }
        }
    }

    /** Clears the canIgnoreAuthAndReturnToGone value upon waking. */
    private fun listenForWakeToClearCanIgnoreAuth() {
        scope.launch {
            transitionInteractor
                .isInTransitionWhere(
                    fromStatePredicate = { deviceIsAsleepInState(it) },
                    toStatePredicate = { deviceIsAwakeInState(it) },
                )
                .collect {
                    // This value is reset when the timeout alarm fires, but if the device is woken
                    // back up before then, it needs to be reset here. The alarm is cancelled
                    // immediately upon waking up, but since this value is used by keyguard
                    // transition internals to decide whether we can transition to GONE, wait until
                    // that decision is made before resetting it.
                    repository.setCanIgnoreAuthAndReturnToGone(false)
                }
        }
    }

    /**
     * Registers the broadcast receiver to receive the alarm intent.
     *
     * TODO(b/351817381): Investigate using BroadcastDispatcher vs. ignoring this lint warning.
     */
    @SuppressLint("WrongConstant", "RegisterReceiverViaContext")
    private fun registerBroadcastReceiver() {
        val delayedActionFilter = IntentFilter()
        delayedActionFilter.addAction(KeyguardViewMediator.DELAYED_KEYGUARD_ACTION)
        // TODO(b/346803756): Listen for DELAYED_LOCK_PROFILE_ACTION.
        delayedActionFilter.priority = IntentFilter.SYSTEM_HIGH_PRIORITY
        context.registerReceiver(
            broadcastReceiver,
            delayedActionFilter,
            SYSTEMUI_PERMISSION,
            null /* scheduler */,
            Context.RECEIVER_EXPORTED_UNAUDITED
        )
    }

    /** Set an alarm for */
    private fun setResetCanIgnoreAuthAlarm() {
        if (!KeyguardWmStateRefactor.isEnabled) {
            return
        }

        val intent =
            Intent(DELAYED_KEYGUARD_ACTION).apply {
                setPackage(context.packageName)
                putExtra(SEQ_EXTRA_KEY, timeoutCounter)
                addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            }

        val sender =
            PendingIntent.getBroadcast(
                context,
                0,
                intent,
                PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

        val time = systemClock.elapsedRealtime() + getCanIgnoreAuthAndReturnToGoneDuration()
        alarmManager.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, time, sender)

        // TODO(b/346803756): Migrate support for child profiles.
    }

    /**
     * Cancel the timeout by incrementing the counter so that we ignore the intent when it's
     * received.
     */
    private fun cancelCanIgnoreAuthAlarm() {
        timeoutCounter++
    }

    /**
     * Whether pressing the power button locks the device immediately; vs. waiting for a specified
     * timeout first.
     */
    private fun willLockImmediately(
        userId: Int = selectedUserInteractor.getSelectedUserId()
    ): Boolean {
        return lockPatternUtils.getPowerButtonInstantlyLocks(userId) ||
            !lockPatternUtils.isSecure(userId)
    }

    /**
     * Returns whether the lockscreen is disabled, either because the keyguard service is disabled
     * or because an adb command has disabled the lockscreen.
     */
    private fun isLockscreenDisabled(
        userId: Int = selectedUserInteractor.getSelectedUserId()
    ): Boolean {
        return lockPatternUtils.isLockScreenDisabled(userId)
    }

    /**
     * Returns the duration within which we can return to GONE without auth after a screen timeout
     * (or power button press, if lock instantly is disabled).
     *
     * This takes into account the user's settings as well as device policy maximums.
     */
    private fun getCanIgnoreAuthAndReturnToGoneDuration(
        userId: Int = selectedUserInteractor.getSelectedUserId()
    ): Long {
        // The timeout duration from settings (Security > Device Unlock > Gear icon > "Lock after
        // screen timeout".
        val durationSetting: Long =
            secureSettings
                .getIntForUser(
                    Secure.LOCK_SCREEN_LOCK_AFTER_TIMEOUT,
                    KEYGUARD_CAN_IGNORE_AUTH_DURATION,
                    userId
                )
                .toLong()

        // Device policy maximum timeout.
        val durationDevicePolicyMax =
            lockPatternUtils.devicePolicyManager.getMaximumTimeToLock(null, userId)

        return if (durationDevicePolicyMax <= 0) {
            durationSetting
        } else {
            var displayTimeout =
                systemSettings
                    .getIntForUser(
                        Settings.System.SCREEN_OFF_TIMEOUT,
                        KEYGUARD_DISPLAY_TIMEOUT_DELAY_DEFAULT,
                        userId
                    )
                    .toLong()

            // Ignore negative values. I don't know why this would be negative, but this check has
            // been around since 2016 and I see no upside to removing it.
            displayTimeout = max(displayTimeout, 0)

            // Respect the shorter of: the device policy (maximum duration between last user action
            // and fully locking) or the "Lock after screen timeout" setting.
            max(min(durationDevicePolicyMax - displayTimeout, durationSetting), 0)
        }
    }

    companion object {
        private const val DELAYED_KEYGUARD_ACTION =
            "com.android.internal.policy.impl.PhoneWindowManager.DELAYED_KEYGUARD"
        private const val DELAYED_LOCK_PROFILE_ACTION =
            "com.android.internal.policy.impl.PhoneWindowManager.DELAYED_LOCK"
        private const val SYSTEMUI_PERMISSION = "com.android.systemui.permission.SELF"
        private const val SEQ_EXTRA_KEY = "count"

        private const val KEYGUARD_CAN_IGNORE_AUTH_DURATION = 5000
        private const val KEYGUARD_DISPLAY_TIMEOUT_DELAY_DEFAULT = 30000
    }
}
