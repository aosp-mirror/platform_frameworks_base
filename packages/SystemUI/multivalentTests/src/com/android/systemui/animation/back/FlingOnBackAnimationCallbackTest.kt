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

package com.android.systemui.animation.back

import android.platform.test.annotations.RequiresFlagsDisabled
import android.platform.test.annotations.RequiresFlagsEnabled
import android.platform.test.flag.junit.CheckFlagsRule
import android.platform.test.flag.junit.DeviceFlagsValueProvider
import android.view.animation.Interpolator
import android.window.BackEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import androidx.test.platform.app.InstrumentationRegistry.getInstrumentation
import com.android.app.animation.Interpolators
import com.android.systemui.SysuiTestCase
import com.android.window.flags.Flags.FLAG_PREDICTIVE_BACK_TIMESTAMP_API
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito

@SmallTest
@RunWith(AndroidJUnit4::class)
class FlingOnBackAnimationCallbackTest : SysuiTestCase() {

    @get:Rule val checkFlagsRule: CheckFlagsRule = DeviceFlagsValueProvider.createCheckFlagsRule()

    @Test
    fun testProgressInterpolation() {
        val mockInterpolator = Mockito.mock(Interpolator::class.java)
        val backEvent = backEventOf(0.5f)
        Mockito.`when`(mockInterpolator.getInterpolation(0.5f)).thenReturn(0.8f)
        val callback = TestFlingOnBackAnimationCallback(mockInterpolator)
        callback.onBackStarted(backEvent)
        assertTrue("Assert onBackStartedCompat called", callback.backStartedCalled)
        callback.onBackProgressed(backEvent)
        assertTrue("Assert onBackProgressedCompat called", callback.backProgressedCalled)
        assertEquals("Assert interpolated progress", 0.8f, callback.progressEvent?.progress)
    }

    @Test
    @RequiresFlagsEnabled(FLAG_PREDICTIVE_BACK_TIMESTAMP_API)
    fun testFling() {
        val callback = TestFlingOnBackAnimationCallback(Interpolators.LINEAR)
        callback.onBackStarted(backEventOf(progress = 0f, frameTime = 0))
        assertTrue("Assert onBackStartedCompat called", callback.backStartedCalled)
        callback.onBackProgressed(backEventOf(0f, 8))
        callback.onBackProgressed(backEventOf(0.2f, 16))
        callback.onBackProgressed(backEventOf(0.4f, 24))
        callback.onBackProgressed(backEventOf(0.6f, 32))
        assertTrue("Assert onBackProgressedCompat called", callback.backProgressedCalled)
        assertEquals("Assert interpolated progress", 0.6f, callback.progressEvent?.progress)
        getInstrumentation().runOnMainSync { callback.onBackInvoked() }
        // Assert that onBackInvoked is not called immediately...
        assertFalse(callback.backInvokedCalled)
        // Instead the fling animation is played and eventually onBackInvoked is called.
        callback.backInvokedLatch.await(1000, TimeUnit.MILLISECONDS)
        assertTrue(callback.backInvokedCalled)
    }

    @Test
    @RequiresFlagsDisabled(FLAG_PREDICTIVE_BACK_TIMESTAMP_API)
    fun testCallbackWithoutTimestampApi() {
        // Assert that all callback methods are immediately forwarded
        val callback = TestFlingOnBackAnimationCallback(Interpolators.LINEAR)
        callback.onBackStarted(backEventOf(progress = 0f, frameTime = 0))
        assertTrue("Assert onBackStartedCompat called", callback.backStartedCalled)
        callback.onBackProgressed(backEventOf(0f, 8))
        assertTrue("Assert onBackProgressedCompat called", callback.backProgressedCalled)
        callback.onBackInvoked()
        assertTrue("Assert onBackInvoked called", callback.backInvokedCalled)
        callback.onBackCancelled()
        assertTrue("Assert onBackCancelled called", callback.backCancelledCalled)
    }

    private fun backEventOf(progress: Float, frameTime: Long = 0): BackEvent {
        return BackEvent(10f, 10f, progress, 0, frameTime)
    }

    /** Helper class to expose the compat functions for testing */
    private class TestFlingOnBackAnimationCallback(progressInterpolator: Interpolator) :
        FlingOnBackAnimationCallback(progressInterpolator) {
        var backStartedCalled = false
        var backProgressedCalled = false
        var backInvokedCalled = false
        val backInvokedLatch = CountDownLatch(1)
        var backCancelledCalled = false
        var progressEvent: BackEvent? = null

        override fun onBackStartedCompat(backEvent: BackEvent) {
            backStartedCalled = true
        }

        override fun onBackProgressedCompat(backEvent: BackEvent) {
            backProgressedCalled = true
            progressEvent = backEvent
        }

        override fun onBackInvokedCompat() {
            backInvokedCalled = true
            backInvokedLatch.countDown()
        }

        override fun onBackCancelledCompat() {
            backCancelledCalled = true
        }
    }
}
