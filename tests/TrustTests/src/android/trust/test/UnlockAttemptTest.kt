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
package android.trust.test

import android.app.trust.TrustManager
import android.content.Context
import android.security.Flags.shouldTrustManagerListenForPrimaryAuth
import android.trust.BaseTrustAgentService
import android.trust.TrustTestActivity
import android.trust.test.lib.LockStateTrackingRule
import android.trust.test.lib.ScreenLockRule
import android.trust.test.lib.TestTrustListener
import android.trust.test.lib.TrustAgentRule
import android.util.Log
import androidx.test.core.app.ApplicationProvider.getApplicationContext
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

/**
 * Test for the impacts of reporting unlock attempts.
 *
 * atest TrustTests:UnlockAttemptTest
 */
@RunWith(AndroidJUnit4::class)
class UnlockAttemptTest {
    private val context = getApplicationContext<Context>()
    private val trustManager = context.getSystemService(TrustManager::class.java) as TrustManager
    private val userId = context.userId
    private val activityScenarioRule = ActivityScenarioRule(TrustTestActivity::class.java)
    private val screenLockRule = ScreenLockRule(requireStrongAuth = true)
    private val lockStateTrackingRule = LockStateTrackingRule()
    private val trustAgentRule =
        TrustAgentRule<UnlockAttemptTrustAgent>(startUnlocked = false, startEnabled = false)

    private val trustListener = UnlockAttemptTrustListener()
    private val agent get() = trustAgentRule.agent

    @get:Rule
    val rule: RuleChain =
        RuleChain.outerRule(activityScenarioRule)
            .around(screenLockRule)
            .around(lockStateTrackingRule)
            .around(trustAgentRule)

    @Before
    fun setUp() {
        trustManager.registerTrustListener(trustListener)
    }

    @Test
    fun successfulUnlockAttempt_allowsTrustAgentToStart() =
        runUnlockAttemptTest(enableAndVerifyTrustAgent = false, managingTrust = false) {
            trustAgentRule.enableTrustAgent()

            triggerSuccessfulUnlock()

            trustAgentRule.verifyAgentIsRunning(MAX_WAIT_FOR_ENABLED_TRUST_AGENT_TO_START)
        }

    @Test
    fun successfulUnlockAttempt_notifiesTrustAgent() =
        runUnlockAttemptTest(enableAndVerifyTrustAgent = true, managingTrust = true) {
            val oldSuccessfulCount = agent.successfulUnlockCallCount
            val oldFailedCount = agent.failedUnlockCallCount

            triggerSuccessfulUnlock()

            assertThat(agent.successfulUnlockCallCount).isEqualTo(oldSuccessfulCount + 1)
            assertThat(agent.failedUnlockCallCount).isEqualTo(oldFailedCount)
        }

    @Test
    fun successfulUnlockAttempt_notifiesTrustListenerOfManagedTrust() =
        runUnlockAttemptTest(enableAndVerifyTrustAgent = true, managingTrust = true) {
            val oldTrustManagedChangedCount = trustListener.onTrustManagedChangedCount[userId] ?: 0

            triggerSuccessfulUnlock()

            assertThat(trustListener.onTrustManagedChangedCount[userId] ?: 0).isEqualTo(
                oldTrustManagedChangedCount + 1
            )
        }

    @Test
    fun failedUnlockAttempt_doesNotAllowTrustAgentToStart() =
        runUnlockAttemptTest(enableAndVerifyTrustAgent = false, managingTrust = false) {
            trustAgentRule.enableTrustAgent()

            triggerFailedUnlock()

            trustAgentRule.ensureAgentIsNotRunning(MAX_WAIT_FOR_ENABLED_TRUST_AGENT_TO_START)
        }

    @Test
    fun failedUnlockAttempt_notifiesTrustAgent() =
        runUnlockAttemptTest(enableAndVerifyTrustAgent = true, managingTrust = true) {
            val oldSuccessfulCount = agent.successfulUnlockCallCount
            val oldFailedCount = agent.failedUnlockCallCount

            triggerFailedUnlock()

            assertThat(agent.successfulUnlockCallCount).isEqualTo(oldSuccessfulCount)
            assertThat(agent.failedUnlockCallCount).isEqualTo(oldFailedCount + 1)
        }

    @Test
    fun failedUnlockAttempt_doesNotNotifyTrustListenerOfManagedTrust() =
        runUnlockAttemptTest(enableAndVerifyTrustAgent = true, managingTrust = true) {
            val oldTrustManagedChangedCount = trustListener.onTrustManagedChangedCount[userId] ?: 0

            triggerFailedUnlock()

            assertThat(trustListener.onTrustManagedChangedCount[userId] ?: 0).isEqualTo(
                oldTrustManagedChangedCount
            )
        }

    private fun runUnlockAttemptTest(
        enableAndVerifyTrustAgent: Boolean,
        managingTrust: Boolean,
        testBlock: () -> Unit,
    ) {
        if (enableAndVerifyTrustAgent) {
            Log.i(TAG, "Triggering successful unlock")
            triggerSuccessfulUnlock()
            Log.i(TAG, "Enabling and waiting for trust agent")
            trustAgentRule.enableAndVerifyTrustAgentIsRunning(
                MAX_WAIT_FOR_ENABLED_TRUST_AGENT_TO_START
            )
            Log.i(TAG, "Managing trust: $managingTrust")
            agent.setManagingTrust(managingTrust)
            await()
        }
        testBlock()
    }

    private fun triggerSuccessfulUnlock() {
        screenLockRule.successfulScreenLockAttempt()
        if (!shouldTrustManagerListenForPrimaryAuth()) {
            trustAgentRule.reportSuccessfulUnlock()
        }
        await()
    }

    private fun triggerFailedUnlock() {
        screenLockRule.failedScreenLockAttempt()
        if (!shouldTrustManagerListenForPrimaryAuth()) {
            trustAgentRule.reportFailedUnlock()
        }
        await()
    }

    companion object {
        private const val TAG = "UnlockAttemptTest"
        private fun await(millis: Long = 500) = Thread.sleep(millis)
        private const val MAX_WAIT_FOR_ENABLED_TRUST_AGENT_TO_START = 10000L
    }
}

class UnlockAttemptTrustAgent : BaseTrustAgentService() {
    var successfulUnlockCallCount: Long = 0
        private set
    var failedUnlockCallCount: Long = 0
        private set

    override fun onUnlockAttempt(successful: Boolean) {
        super.onUnlockAttempt(successful)
        if (successful) {
            successfulUnlockCallCount++
        } else {
            failedUnlockCallCount++
        }
    }
}

private class UnlockAttemptTrustListener : TestTrustListener() {
    var enabledTrustAgentsChangedCount = mutableMapOf<Int, Int>()
    var onTrustManagedChangedCount = mutableMapOf<Int, Int>()

    override fun onEnabledTrustAgentsChanged(userId: Int) {
        enabledTrustAgentsChangedCount.compute(userId) { _: Int, curr: Int? ->
            if (curr == null) 0 else curr + 1
        }
    }

    data class TrustChangedParams(
        val enabled: Boolean,
        val newlyUnlocked: Boolean,
        val userId: Int,
        val flags: Int,
        val trustGrantedMessages: MutableList<String>?
    )

    val onTrustChangedCalls = mutableListOf<TrustChangedParams>()

    override fun onTrustChanged(
        enabled: Boolean,
        newlyUnlocked: Boolean,
        userId: Int,
        flags: Int,
        trustGrantedMessages: MutableList<String>
    ) {
        onTrustChangedCalls += TrustChangedParams(
            enabled, newlyUnlocked, userId, flags, trustGrantedMessages
        )
    }

    override fun onTrustManagedChanged(enabled: Boolean, userId: Int) {
        onTrustManagedChangedCount.compute(userId) { _: Int, curr: Int? ->
            if (curr == null) 0 else curr + 1
        }
    }
}
