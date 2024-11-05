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

import android.os.Bundle
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.keyguard.data.repository.BiometricSettingsRepository
import com.android.systemui.util.kotlin.sample
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge

/**
 * Emitted when we receive a [KeyguardLockWhileAwakeInteractor.onKeyguardServiceDoKeyguardTimeout]
 * call.
 *
 * Includes a timestamp so it's not conflated by the StateFlow.
 */
data class KeyguardTimeoutWhileAwakeEvent(val timestamp: Long, val options: Bundle?)

/** The reason we're locking while awake, used for logging. */
enum class LockWhileAwakeReason(private val logReason: String) {
    LOCKDOWN("Lockdown initiated."),
    KEYGUARD_REENABLED(
        "Keyguard was re-enabled. We weren't unlocked when it was disabled, " +
            "so we're returning to the lockscreen."
    ),
    KEYGUARD_TIMEOUT_WHILE_SCREEN_ON(
        "Timed out while the screen was kept on, or WM#lockNow() was called."
    );

    override fun toString(): String {
        return logReason
    }
}

/**
 * Logic around cases where the device locks while still awake (transitioning from GONE ->
 * LOCKSCREEN), vs. the more common cases of a power button press or screen timeout, which result in
 * the device going to sleep.
 *
 * This is possible in the following situations:
 * - The user initiates lockdown from the power menu.
 * - Theft detection, etc. has requested lockdown.
 * - The keyguard was disabled while visible, and has now been re-enabled, so it's re-showing.
 * - Someone called WM#lockNow().
 * - The screen timed out, but an activity with FLAG_ALLOW_LOCK_WHILE_SCREEN_ON is on top.
 */
@SysUISingleton
class KeyguardLockWhileAwakeInteractor
@Inject
constructor(
    biometricSettingsRepository: BiometricSettingsRepository,
    keyguardEnabledInteractor: KeyguardEnabledInteractor,
) {
    /** Emits whenever a timeout event is received by [KeyguardService]. */
    private val timeoutEvents: MutableStateFlow<KeyguardTimeoutWhileAwakeEvent?> =
        MutableStateFlow(null)

    /** Emits whenever the current user is in lockdown mode. */
    private val inLockdown: Flow<LockWhileAwakeReason> =
        biometricSettingsRepository.isCurrentUserInLockdown
            .distinctUntilChanged()
            .filter { inLockdown -> inLockdown }
            .map { LockWhileAwakeReason.LOCKDOWN }

    /**
     * Emits whenever the keyguard is re-enabled, and we need to return to lockscreen due to the
     * device being locked when the keyguard was originally disabled.
     */
    private val keyguardReenabled: Flow<LockWhileAwakeReason> =
        keyguardEnabledInteractor.isKeyguardEnabled
            .filter { enabled -> enabled }
            .sample(keyguardEnabledInteractor.showKeyguardWhenReenabled)
            .filter { reshow -> reshow }
            .map { LockWhileAwakeReason.KEYGUARD_REENABLED }

    /** Emits whenever we should lock while the screen is on, for any reason. */
    val lockWhileAwakeEvents: Flow<LockWhileAwakeReason> =
        merge(
            inLockdown,
            keyguardReenabled,
            timeoutEvents.filterNotNull().map {
                LockWhileAwakeReason.KEYGUARD_TIMEOUT_WHILE_SCREEN_ON
            },
        )

    /**
     * Called by [KeyguardService] when it receives a doKeyguardTimeout() call. This indicates that
     * the device locked while the screen was on.
     *
     * [options] appears to be no longer used, but we'll keep it in this interactor in case that
     * turns out not to be true.
     */
    fun onKeyguardServiceDoKeyguardTimeout(options: Bundle?) {
        timeoutEvents.value =
            KeyguardTimeoutWhileAwakeEvent(
                timestamp = System.currentTimeMillis(),
                options = options,
            )
    }
}
