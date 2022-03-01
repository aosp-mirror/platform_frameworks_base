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
import android.trust.BaseTrustAgentService
import android.trust.TrustTestActivity
import android.trust.test.lib.ScreenLockRule
import android.trust.test.lib.TrustAgentRule
import android.util.Log
import androidx.test.core.app.ApplicationProvider.getApplicationContext
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

/**
 * Test for testing the user unlock trigger.
 *
 * atest TrustTests:UserUnlockRequestTest
 */
@RunWith(AndroidJUnit4::class)
class UserUnlockRequestTest {
    private val context: Context = getApplicationContext()
    private val trustManager = context.getSystemService(TrustManager::class.java) as TrustManager
    private val userId = context.userId
    private val activityScenarioRule = ActivityScenarioRule(TrustTestActivity::class.java)
    private val trustAgentRule = TrustAgentRule<UserUnlockRequestTrustAgent>()

    @get:Rule
    val rule: RuleChain = RuleChain
        .outerRule(activityScenarioRule)
        .around(ScreenLockRule())
        .around(trustAgentRule)

    @Test
    fun reportUserRequestedUnlock_propagatesToAgent() {
        val oldCount = trustAgentRule.agent.onUserRequestedUnlockCallCount
        trustManager.reportUserRequestedUnlock(userId)
        await()

        assertThat(trustAgentRule.agent.onUserRequestedUnlockCallCount)
            .isEqualTo(oldCount + 1)
    }

    companion object {
        private const val TAG = "UserUnlockRequestTest"
        private fun await() = Thread.sleep(250)
    }
}

class UserUnlockRequestTrustAgent : BaseTrustAgentService() {
    var onUserRequestedUnlockCallCount: Long = 0
        private set

    override fun onUserRequestedUnlock() {
        Log.i(TAG, "onUserRequestedUnlock")
        onUserRequestedUnlockCallCount++
    }

    companion object {
        private const val TAG = "UserUnlockRequestTrustAgent"
    }
}
