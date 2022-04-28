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

import android.trust.BaseTrustAgentService
import android.trust.TrustTestActivity
import android.trust.test.lib.LockStateTrackingRule
import android.trust.test.lib.ScreenLockRule
import android.trust.test.lib.TrustAgentRule
import android.util.Log
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

/**
 * Test for testing lockUser.
 *
 * atest TrustTests:LockUserTest
 */
@RunWith(AndroidJUnit4::class)
class LockUserTest {
    private val activityScenarioRule = ActivityScenarioRule(TrustTestActivity::class.java)
    private val lockStateTrackingRule = LockStateTrackingRule()
    private val trustAgentRule = TrustAgentRule<LockUserTrustAgent>()

    @get:Rule
    val rule: RuleChain = RuleChain
        .outerRule(activityScenarioRule)
        .around(ScreenLockRule())
        .around(lockStateTrackingRule)
        .around(trustAgentRule)

    @Test
    fun lockUser_locksTheDevice() {
        Log.i(TAG, "Locking user")
        trustAgentRule.agent.lockUser()

        lockStateTrackingRule.assertLocked()
    }

    companion object {
        private const val TAG = "LockUserTest"
    }
}

class LockUserTrustAgent : BaseTrustAgentService()
