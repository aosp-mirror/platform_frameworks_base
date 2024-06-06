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
import android.content.ComponentName
import android.content.Context
import android.trust.BaseTrustAgentService
import android.trust.test.lib.TrustAgentRule.Companion.invoke
import android.util.Log
import androidx.test.core.app.ApplicationProvider.getApplicationContext
import com.android.internal.widget.LockPatternUtils
import com.google.common.truth.Truth.assertWithMessage
import kotlin.reflect.KClass
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

/**
 * Enables a trust agent and causes the system service to bind to it.
 *
 * The enabled agent can be accessed during the test via the [agent] property.
 *
 * @constructor Creates the rule. Do not use; instead, use [invoke].
 */
class TrustAgentRule<T : BaseTrustAgentService>(
    private val serviceClass: KClass<T>,
    private val startUnlocked: Boolean,
    private val startEnabled: Boolean,
) : TestRule {
    private val context: Context = getApplicationContext()
    private val trustManager = context.getSystemService(TrustManager::class.java) as TrustManager
    private val lockPatternUtils = LockPatternUtils(context)

    val agent get() = BaseTrustAgentService.instance(serviceClass) as T

    override fun apply(base: Statement, description: Description) = object : Statement() {
        override fun evaluate() {
            verifyTrustServiceRunning()
            if (startUnlocked) {
                reportSuccessfulUnlock()
            } else {
                Log.i(TAG, "Trust manager not starting in unlocked state")
            }

            try {
                if (startEnabled) {
                    enableAndVerifyTrustAgentIsRunning()
                } else {
                    Log.i(TAG, "Trust agent ${serviceClass.simpleName} not enabled")
                }
                base.evaluate()
            } finally {
                disableTrustAgent()
            }
        }
    }

    private fun verifyTrustServiceRunning() {
        assertWithMessage("Trust service is not running").that(trustManager).isNotNull()
    }

    fun reportSuccessfulUnlock() {
        Log.i(TAG, "Reporting successful unlock")
        trustManager.reportUnlockAttempt(true, context.userId)
    }

    fun reportFailedUnlock() {
        Log.i(TAG, "Reporting failed unlock")
        trustManager.reportUnlockAttempt(false, context.userId)
    }

    fun enableAndVerifyTrustAgentIsRunning(maxWait: Long = 30000L) {
        enableTrustAgent()
        verifyAgentIsRunning(maxWait)
    }

    fun enableTrustAgent() {
        val componentName = ComponentName(context, serviceClass.java)
        val userId = context.userId
        Log.i(TAG, "Enabling trust agent ${componentName.flattenToString()} for user $userId")
        val agents = mutableListOf(componentName)
            .plus(lockPatternUtils.getEnabledTrustAgents(userId))
            .distinct()
        lockPatternUtils.setEnabledTrustAgents(agents, userId)
    }

    fun verifyAgentIsRunning(maxWait: Long = 30000L) {
        wait("${serviceClass.simpleName} to be running", maxWait) {
            BaseTrustAgentService.instance(serviceClass) != null
        }
    }

    fun ensureAgentIsNotRunning(window: Long = 30000L) {
        ensure("${serviceClass.simpleName} is not running", window) {
            BaseTrustAgentService.instance(serviceClass) == null
        }
    }

    private fun disableTrustAgent() {
        val componentName = ComponentName(context, serviceClass.java)
        val userId = context.userId
        Log.i(TAG, "Disabling trust agent ${componentName.flattenToString()} for user $userId")
        val agents = lockPatternUtils.getEnabledTrustAgents(userId).toMutableList()
            .distinct()
            .minus(componentName)
        lockPatternUtils.setEnabledTrustAgents(agents, userId)
    }

    companion object {
        /**
         * Creates a new rule for the specified agent class. Starts with the device unlocked and
         * the trust agent enabled. Example usage:
         * ```
         *   @get:Rule val rule = TrustAgentRule<MyTestAgent>()
         * ```
         *
         * Also supports setting different device lock and trust agent enablement states:
         * ```
         *   @get:Rule val rule = TrustAgentRule<MyTestAgent>(startUnlocked = false, startEnabled = false)
         * ```
         */
        inline operator fun <reified T : BaseTrustAgentService> invoke(
            startUnlocked: Boolean = true,
            startEnabled: Boolean = true,
        ) =
            TrustAgentRule(T::class, startUnlocked, startEnabled)


        private const val TAG = "TrustAgentRule"
    }
}
