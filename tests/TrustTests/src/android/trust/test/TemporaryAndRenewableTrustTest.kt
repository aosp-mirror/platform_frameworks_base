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

import android.service.trust.GrantTrustResult
import android.service.trust.GrantTrustResult.STATUS_UNLOCKED_BY_GRANT
import android.service.trust.TrustAgentService.FLAG_GRANT_TRUST_TEMPORARY_AND_RENEWABLE
import android.trust.BaseTrustAgentService
import android.trust.TrustTestActivity
import android.trust.test.lib.LockStateTrackingRule
import android.trust.test.lib.ScreenLockRule
import android.trust.test.lib.TrustAgentRule
import android.util.Log
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry.getInstrumentation
import androidx.test.uiautomator.UiDevice
import android.trust.test.lib.wait
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

/**
 * Test for testing revokeTrust & grantTrust for renewable trust.
 *
 * atest TrustTests:TemporaryAndRenewableTrustTest
 */
@RunWith(AndroidJUnit4::class)
class TemporaryAndRenewableTrustTest {
    private val uiDevice = UiDevice.getInstance(getInstrumentation())
    private val activityScenarioRule = ActivityScenarioRule(TrustTestActivity::class.java)
    private val lockStateTrackingRule = LockStateTrackingRule()
    private val trustAgentRule = TrustAgentRule<TemporaryAndRenewableTrustAgent>()

    @get:Rule
    val rule: RuleChain = RuleChain
        .outerRule(activityScenarioRule)
        .around(ScreenLockRule())
        .around(lockStateTrackingRule)
        .around(trustAgentRule)

    @Before
    fun manageTrust() {
        trustAgentRule.agent.setManagingTrust(true)
    }

    // This test serves a baseline for Grant tests, verifying that the default behavior of the
    // device is to lock when put to sleep
    @Test
    fun sleepingDeviceWithoutGrantLocksDevice() {
        uiDevice.sleep()

        lockStateTrackingRule.assertLocked()
    }

    @Test
    fun grantTrustLockedDevice_deviceStaysLocked() {
        uiDevice.sleep()
        lockStateTrackingRule.assertLocked()

        uiDevice.wakeUp()
        trustAgentRule.agent.grantTrust(
            GRANT_MESSAGE, 0, FLAG_GRANT_TRUST_TEMPORARY_AND_RENEWABLE) {}

        lockStateTrackingRule.assertLocked()
    }

    @Test
    fun grantTrustUnlockedDevice_deviceLocksOnScreenOff() {
        trustAgentRule.agent.grantTrust(
            GRANT_MESSAGE, 0, FLAG_GRANT_TRUST_TEMPORARY_AND_RENEWABLE) {}
        uiDevice.sleep()

        lockStateTrackingRule.assertLocked()
    }

    @Test
    fun grantTrustLockedDevice_grantTrustOnLockedDeviceUnlocksDevice() {
        trustAgentRule.agent.grantTrust(
            GRANT_MESSAGE, 0, FLAG_GRANT_TRUST_TEMPORARY_AND_RENEWABLE) {}
        uiDevice.sleep()

        lockStateTrackingRule.assertLocked()

        uiDevice.wakeUp()
        trustAgentRule.agent.grantTrust(
            GRANT_MESSAGE, 0, FLAG_GRANT_TRUST_TEMPORARY_AND_RENEWABLE) {}

        lockStateTrackingRule.assertUnlocked()
    }

    @Test
    fun grantTrustLockedDevice_callsBackWhenUnlocked() {
        Log.i(TAG, "Granting renewable trust while unlocked")
        trustAgentRule.agent.grantTrust(
            GRANT_MESSAGE, 0, FLAG_GRANT_TRUST_TEMPORARY_AND_RENEWABLE) {}
        await(1000)

        Log.i(TAG, "Locking device")
        uiDevice.sleep()

        lockStateTrackingRule.assertLocked()
        uiDevice.wakeUp()

        Log.i(TAG, "Renewing trust and unlocking")
        var result: GrantTrustResult? = null
        trustAgentRule.agent.grantTrust(
                GRANT_MESSAGE, 0, FLAG_GRANT_TRUST_TEMPORARY_AND_RENEWABLE) {
            Log.i(TAG, "Callback received; status=${it.status}")
            result = it
        }
        lockStateTrackingRule.assertUnlocked()

        wait("callback triggered") { result?.status == STATUS_UNLOCKED_BY_GRANT }
    }

    @Test
    fun grantTrustLockedDevice_revokeTrustPreventsSubsequentUnlock() {
        trustAgentRule.agent.grantTrust(
            GRANT_MESSAGE, 0, FLAG_GRANT_TRUST_TEMPORARY_AND_RENEWABLE) {}
        uiDevice.sleep()

        lockStateTrackingRule.assertLocked()

        trustAgentRule.agent.revokeTrust()
        await(500)
        uiDevice.wakeUp()

        trustAgentRule.agent.grantTrust(
            GRANT_MESSAGE, 0, FLAG_GRANT_TRUST_TEMPORARY_AND_RENEWABLE) {}

        lockStateTrackingRule.assertLocked()
    }

    companion object {
        private const val TAG = "TemporaryAndRenewableTrustTest"
        private const val GRANT_MESSAGE = "granted by test"
        private fun await(millis: Long) = Thread.sleep(millis)
    }
}

class TemporaryAndRenewableTrustAgent : BaseTrustAgentService()
