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

package com.android.systemui.lowlightclock

import android.content.Intent
import android.os.UserManager
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.shared.condition.Condition
import com.android.systemui.util.mockito.whenever
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.mockito.Mock
import org.mockito.MockitoAnnotations

@SmallTest
@OptIn(ExperimentalCoroutinesApi::class)
class DirectBootConditionTest : SysuiTestCase() {
    @Mock private lateinit var userManager: UserManager
    @Mock private lateinit var callback: Condition.Callback

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
    }

    @Test
    fun receiverRegisteredOnStart() = runTest {
        val condition = buildCondition(this)
        // No receivers are registered yet
        assertThat(fakeBroadcastDispatcher.numReceiversRegistered).isEqualTo(0)
        condition.addCallback(callback)
        advanceUntilIdle()
        // Receiver is registered after a callback is added
        assertThat(fakeBroadcastDispatcher.numReceiversRegistered).isEqualTo(1)
        condition.removeCallback(callback)
    }

    @Test
    fun unregisterReceiverOnStop() = runTest {
        val condition = buildCondition(this)

        condition.addCallback(callback)
        advanceUntilIdle()

        assertThat(fakeBroadcastDispatcher.numReceiversRegistered).isEqualTo(1)

        condition.removeCallback(callback)
        advanceUntilIdle()

        // Receiver is unregistered when nothing is listening to the condition
        assertThat(fakeBroadcastDispatcher.numReceiversRegistered).isEqualTo(0)
    }

    @Test
    fun callbackTriggeredWhenUserUnlocked() = runTest {
        val condition = buildCondition(this)

        setUserUnlocked(false)
        condition.addCallback(callback)
        advanceUntilIdle()

        assertThat(condition.isConditionMet).isTrue()

        setUserUnlocked(true)
        advanceUntilIdle()

        assertThat(condition.isConditionMet).isFalse()
        condition.removeCallback(callback)
    }

    private fun buildCondition(scope: CoroutineScope): DirectBootCondition {
        return DirectBootCondition(fakeBroadcastDispatcher, userManager, scope)
    }

    private fun setUserUnlocked(unlocked: Boolean) {
        whenever(userManager.isUserUnlocked).thenReturn(unlocked)
        fakeBroadcastDispatcher.sendIntentToMatchingReceiversOnly(
            context,
            Intent(Intent.ACTION_USER_UNLOCKED),
        )
    }
}
