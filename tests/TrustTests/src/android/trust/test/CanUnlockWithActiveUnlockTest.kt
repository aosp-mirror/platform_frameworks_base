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

package android.trust.test

import android.app.trust.TrustManager
import android.content.Context
import android.service.trust.TrustAgentService.FLAG_GRANT_TRUST_TEMPORARY_AND_RENEWABLE
import android.trust.BaseTrustAgentService
import android.trust.TrustTestActivity
import android.trust.test.lib.LockStateTrackingRule
import android.trust.test.lib.ScreenLockRule
import android.trust.test.lib.TestTrustListener
import android.trust.test.lib.TrustAgentRule
import androidx.test.core.app.ApplicationProvider.getApplicationContext
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry.getInstrumentation
import androidx.test.uiautomator.UiDevice
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

/**
 * Test for testing isActiveUnlockRunning.
 *
 * atest TrustTests:IsActiveUnlockRunningTest
 */
@RunWith(AndroidJUnit4::class)
class IsActiveUnlockRunningTest {
    private val uiDevice = UiDevice.getInstance(getInstrumentation())
    private val context: Context = getApplicationContext()
    private val userId = context.userId
    private val trustManager = context.getSystemService(TrustManager::class.java) as TrustManager
    private val activityScenarioRule = ActivityScenarioRule(TrustTestActivity::class.java)
    private val lockStateTrackingRule = LockStateTrackingRule()
    private val trustAgentRule = TrustAgentRule<IsActiveUnlockRunningTrustAgent>()

    private val listener = object : TestTrustListener() {
        var isRunning = false
            private set

        override fun onIsActiveUnlockRunningChanged(isRunning: Boolean, userId: Int) {
            this.isRunning = isRunning
        }
    }

    @get:Rule
    val rule: RuleChain = RuleChain
        .outerRule(activityScenarioRule)
        .around(ScreenLockRule())
        .around(lockStateTrackingRule)
        .around(trustAgentRule)

    @Before
    fun manageTrust() {
        trustAgentRule.agent.setManagingTrust(true)
        trustManager.registerTrustListener(listener)
    }

    @After
    fun unregisterListener() {
        trustManager.unregisterTrustListener(listener)
    }

    @Test
    fun defaultState_isActiveUnlockRunningIsFalse() {
        assertThat(trustManager.isActiveUnlockRunning(userId)).isFalse()
        assertThat(listener.isRunning).isFalse()
    }

    @Test
    fun grantTrustLockedDevice_isActiveUnlockRunningIsFalse() {
        uiDevice.sleep()
        lockStateTrackingRule.assertLocked()

        uiDevice.wakeUp()
        trustAgentRule.agent.grantTrust(
            GRANT_MESSAGE, 0, FLAG_GRANT_TRUST_TEMPORARY_AND_RENEWABLE) {}

        assertThat(trustManager.isActiveUnlockRunning(userId)).isFalse()
        assertThat(listener.isRunning).isFalse()
    }

    @Test
    fun grantTrustUnlockedDevice_isActiveUnlockRunningIsTrueWhileLocked() {
        trustAgentRule.agent.grantTrust(
            GRANT_MESSAGE, 0, FLAG_GRANT_TRUST_TEMPORARY_AND_RENEWABLE) {}
        uiDevice.sleep()

        lockStateTrackingRule.assertLocked()

        assertThat(trustManager.isActiveUnlockRunning(userId)).isTrue()
        assertThat(listener.isRunning).isTrue()
    }

    @Test
    fun trustRevoked_isActiveUnlockRunningIsFalse() {
        trustAgentRule.agent.grantTrust(
            GRANT_MESSAGE, 0, FLAG_GRANT_TRUST_TEMPORARY_AND_RENEWABLE) {}

        trustAgentRule.agent.revokeTrust()

        assertThat(trustManager.isActiveUnlockRunning(userId)).isFalse()
        assertThat(listener.isRunning).isFalse()
    }

    companion object {
        private const val GRANT_MESSAGE = "granted by test"
        private fun await(millis: Long) = Thread.sleep(millis)
    }
}

class IsActiveUnlockRunningTrustAgent : BaseTrustAgentService()
