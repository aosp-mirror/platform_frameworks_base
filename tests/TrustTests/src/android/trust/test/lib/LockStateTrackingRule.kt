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
        wait("un-locked per TrustListener") { lockState.locked == true }
        wait("keyguard lock") { windowManager.isKeyguardLocked }
    }

    fun assertUnlocked() {
        wait("locked per TrustListener") { lockState.locked == false }
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
