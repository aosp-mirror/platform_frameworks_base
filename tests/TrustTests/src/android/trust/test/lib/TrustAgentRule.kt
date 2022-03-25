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
import android.util.Log
import androidx.test.core.app.ApplicationProvider.getApplicationContext
import com.android.internal.widget.LockPatternUtils
import com.google.common.truth.Truth.assertWithMessage
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement
import kotlin.reflect.KClass

/**
 * Enables a trust agent and causes the system service to bind to it.
 *
 * The enabled agent can be accessed during the test via the [agent] property.
 *
 * @constructor Creates the rule. Do not use; instead, use [invoke].
 */
class TrustAgentRule<T : BaseTrustAgentService>(
    private val serviceClass: KClass<T>
) : TestRule {
    private val context: Context = getApplicationContext()
    private val trustManager = context.getSystemService(TrustManager::class.java) as TrustManager
    private val lockPatternUtils = LockPatternUtils(context)

    val agent get() = BaseTrustAgentService.instance(serviceClass) as T

    override fun apply(base: Statement, description: Description) = object : Statement() {
        override fun evaluate() {
            verifyTrustServiceRunning()
            unlockDeviceWithCredential()
            enableTrustAgent()

            try {
                verifyAgentIsRunning()
                base.evaluate()
            } finally {
                disableTrustAgent()
            }
        }
    }

    private fun verifyTrustServiceRunning() {
        assertWithMessage("Trust service is not running").that(trustManager).isNotNull()
    }

    private fun unlockDeviceWithCredential() {
        Log.d(TAG, "Unlocking device with credential")
        trustManager.reportUnlockAttempt(true, context.userId)
    }

    private fun enableTrustAgent() {
        val componentName = ComponentName(context, serviceClass.java)
        val userId = context.userId
        Log.i(TAG, "Enabling trust agent ${componentName.flattenToString()} for user $userId")
        val agents = mutableListOf(componentName)
            .plus(lockPatternUtils.getEnabledTrustAgents(userId))
            .distinct()
        lockPatternUtils.setEnabledTrustAgents(agents, userId)
    }

    private fun verifyAgentIsRunning() {
        wait("${serviceClass.simpleName} to be running") {
            BaseTrustAgentService.instance(serviceClass) != null
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
         * Creates a new rule for the specified agent class. Example usage:
         * ```
         *   @get:Rule val rule = TrustAgentRule<MyTestAgent>()
         * ```
         */
        inline operator fun <reified T : BaseTrustAgentService> invoke() =
            TrustAgentRule(T::class)

        private const val TAG = "TrustAgentRule"
    }
}
