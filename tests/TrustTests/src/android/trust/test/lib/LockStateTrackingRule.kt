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

package android.trust.test.lib

import android.app.KeyguardManager
import android.app.trust.TrustManager
import android.content.Context
import android.util.Log
import android.view.WindowManagerGlobal
import androidx.test.core.app.ApplicationProvider.getApplicationContext
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

/**
 * Rule for tracking the trusted state of the device based on events emitted to
 * [TrustListener].  Provides helper methods for verifying that the trusted
 * state has a particular value and is consistent with (a) the keyguard "locked"
 * (i.e. showing) value when applicable, and (b) the device locked value that is
 * tracked by TrustManagerService and is queryable via KeyguardManager.
 */
class LockStateTrackingRule : TestRule {
    private val context: Context = getApplicationContext()
    private val windowManager = checkNotNull(WindowManagerGlobal.getWindowManagerService())
    private val keyguardManager =
            context.getSystemService(KeyguardManager::class.java) as KeyguardManager

    @Volatile lateinit var trustState: TrustState
        private set

    override fun apply(base: Statement, description: Description) = object : Statement() {
        override fun evaluate() {
            trustState = TrustState()
            val trustManager = context.getSystemService(TrustManager::class.java) as TrustManager
            val listener = Listener()

            trustManager.registerTrustListener(listener)
            try {
                base.evaluate()
            } finally {
                trustManager.unregisterTrustListener(listener)
            }
        }
    }

    fun assertLocked() {
        wait("device locked") { keyguardManager.isDeviceLocked }
        // isDeviceLocked implies isKeyguardLocked && !trusted.
        wait("keyguard locked") { windowManager.isKeyguardLocked }
        wait("not trusted") { trustState.trusted == false }
    }

    // TODO(b/299298338) remove this when removing FLAG_FIX_UNLOCKED_DEVICE_REQUIRED_KEYS_V2
    fun assertUnlockedButNotReally() {
        wait("device unlocked") { !keyguardManager.isDeviceLocked }
        wait("not trusted") { trustState.trusted == false }
        wait("keyguard locked") { windowManager.isKeyguardLocked }
    }

    fun assertUnlockedAndTrusted() {
        wait("device unlocked") { !keyguardManager.isDeviceLocked }
        wait("trusted") { trustState.trusted == true }
        // Can't check for !isKeyguardLocked here, since isKeyguardLocked
        // returns true in the case where the keyguard is dismissible with
        // swipe, which is considered "device unlocked"!
    }

    inner class Listener : TestTrustListener() {
        override fun onTrustChanged(
            enabled: Boolean,
            newlyUnlocked: Boolean,
            userId: Int,
            flags: Int,
            trustGrantedMessages: MutableList<String>
        ) {
            Log.d(TAG, "Device became trusted=$enabled")
            trustState = trustState.copy(trusted = enabled)
        }
    }

    data class TrustState(
        val trusted: Boolean? = null
    )

    companion object {
        private const val TAG = "LockStateTrackingRule"
    }
}
