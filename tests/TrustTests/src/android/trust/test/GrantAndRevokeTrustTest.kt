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
import android.trust.BaseTrustAgentService
import android.trust.TrustTestActivity
import android.trust.test.lib.LockStateTrackingRule
import android.trust.test.lib.ScreenLockRule
import android.trust.test.lib.TrustAgentRule
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry.getInstrumentation
import androidx.test.uiautomator.UiDevice
import com.android.server.testutils.mock
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.mockito.Mockito.verifyZeroInteractions

/**
 * Test for testing revokeTrust & grantTrust for non-renewable trust.
 *
 * atest TrustTests:GrantAndRevokeTrustTest
 */
@RunWith(AndroidJUnit4::class)
class GrantAndRevokeTrustTest {
    private val uiDevice = UiDevice.getInstance(getInstrumentation())
    private val activityScenarioRule = ActivityScenarioRule(TrustTestActivity::class.java)
    private val lockStateTrackingRule = LockStateTrackingRule()
    private val trustAgentRule = TrustAgentRule<GrantAndRevokeTrustAgent>()

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
    fun grantKeepsDeviceUnlocked() {
        trustAgentRule.agent.grantTrust(GRANT_MESSAGE, 10000, 0) {}
        uiDevice.sleep()

        lockStateTrackingRule.assertUnlocked()
    }

    @Test
    fun grantKeepsDeviceUnlocked_untilRevoked() {
        trustAgentRule.agent.grantTrust(GRANT_MESSAGE, 0, 0) {}
        await()
        uiDevice.sleep()
        trustAgentRule.agent.revokeTrust()

        lockStateTrackingRule.assertLocked()
    }

    @Test
    fun grantDoesNotCallBack() {
        val callback = mock<(GrantTrustResult) -> Unit>()
        trustAgentRule.agent.grantTrust(GRANT_MESSAGE, 0, 0, callback)
        await()

        verifyZeroInteractions(callback)
    }

    companion object {
        private const val TAG = "GrantAndRevokeTrustTest"
        private const val GRANT_MESSAGE = "granted by test"
        private fun await() = Thread.sleep(250)
    }
}

class GrantAndRevokeTrustAgent : BaseTrustAgentService()
