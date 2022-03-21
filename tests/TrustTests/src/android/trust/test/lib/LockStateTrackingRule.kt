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

import android.app.trust.TrustManager
import android.app.trust.TrustManager.TrustListener
import android.content.Context
import android.util.Log
import android.view.WindowManagerGlobal
import androidx.test.core.app.ApplicationProvider.getApplicationContext
import com.google.common.truth.Truth.assertThat
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

/**
 * Rule for tracking the lock state of the device based on events emitted to [TrustListener].
 */
class LockStateTrackingRule : TestRule {
    private val context: Context = getApplicationContext()
    private val windowManager = WindowManagerGlobal.getWindowManagerService()

    @Volatile lateinit var lockState: LockState
        private set

    override fun apply(base: Statement, description: Description) = object : Statement() {
        override fun evaluate() {
            lockState = LockState(locked = windowManager.isKeyguardLocked)
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
        val maxWaits = 50
        var waitCount = 0

        // First verify we get the call in LockState via TrustListener
        while ((lockState.locked == false) && waitCount < maxWaits) {
            Log.i(TAG, "phone still unlocked (TrustListener), wait 50ms more ($waitCount)")
            Thread.sleep(50)
            waitCount++
        }
        assertThat(lockState.locked).isTrue()

        // TODO(b/225231929): refactor checks into one loop and re-use for assertUnlocked
        // Then verify we get the window manager locked
        while (!windowManager.isKeyguardLocked && waitCount < maxWaits) {
            Log.i(TAG, "phone still unlocked (WindowManager), wait 50ms more ($waitCount)")
            Thread.sleep(50)
            waitCount++
        }
        assertThat(windowManager.isKeyguardLocked).isTrue()
    }

    fun assertUnlocked() {
        val maxWaits = 50
        var waitCount = 0

        while ((lockState.locked == true) && waitCount < maxWaits) {
            Log.i(TAG, "phone still unlocked, wait 50ms more ($waitCount)")
            Thread.sleep(50)
            waitCount++
        }
        assertThat(lockState.locked).isFalse()
    }

    inner class Listener : TrustListener {
        override fun onTrustChanged(
            enabled: Boolean,
            userId: Int,
            flags: Int,
            trustGrantedMessages: MutableList<String>
        ) {
            Log.d(TAG, "Device became trusted=$enabled")
            lockState = lockState.copy(locked = !enabled)
        }

        override fun onTrustManagedChanged(enabled: Boolean, userId: Int) {
        }

        override fun onTrustError(message: CharSequence) {
        }
    }

    data class LockState(
        val locked: Boolean? = null
    )

    companion object {
        private const val TAG = "LockStateTrackingRule"
    }
}
