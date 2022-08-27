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

package com.android.systemui.charging

import android.testing.AndroidTestingRunner
import android.testing.TestableLooper.RunWithLooper
import androidx.test.filters.SmallTest
import com.android.internal.logging.testing.UiEventLoggerFake
import com.android.systemui.SysuiTestCase
import com.android.systemui.dump.logcatLogBuffer
import com.android.systemui.ripple.RippleShader.RippleShape
import com.android.systemui.util.concurrency.FakeExecutor
import com.android.systemui.util.time.FakeSystemClock
import com.google.common.truth.Truth.assertThat
import junit.framework.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidTestingRunner::class)
@RunWithLooper
class WirelessChargingRippleControllerTest : SysuiTestCase() {

    private val duration: Long = 1000L
    private val fakeSystemClock: FakeSystemClock = FakeSystemClock()
    private lateinit var wirelessChargingRippleController: WirelessChargingRippleController
    private val fakeExecutor = FakeExecutor(fakeSystemClock)

    @Before
    fun setUp() {
        wirelessChargingRippleController = WirelessChargingRippleController(
                context, UiEventLoggerFake(), fakeExecutor, logcatLogBuffer())
        fakeSystemClock.setElapsedRealtime(0L)
    }

    @Test
    fun showWirelessChargingView_hasCorrectDuration() {
        val wirelessChargingView = WirelessChargingView.create(
                context, UNKNOWN_BATTERY_LEVEL, UNKNOWN_BATTERY_LEVEL, false,
                RippleShape.ROUNDED_BOX, duration
        )

        wirelessChargingRippleController.show(wirelessChargingView, 0)
        fakeExecutor.runAllReady()

        assertEquals(duration, wirelessChargingView.duration)
    }

    @Test
    fun showWirelessChargingView_triggerWhilePlayingAnim_doesNotShowRipple() {
        val wirelessChargingViewFirst = WirelessChargingView.create(
                context, UNKNOWN_BATTERY_LEVEL, UNKNOWN_BATTERY_LEVEL, false,
                RippleShape.ROUNDED_BOX, duration
        )
        wirelessChargingRippleController.show(wirelessChargingViewFirst, 0)
        assertThat(fakeExecutor.numPending()).isEqualTo(2)
        fakeExecutor.runNextReady() // run showInternal

        // ensure we haven't run hideInternal, to simulate the first one is still playing.
        assertThat(fakeExecutor.numPending()).isEqualTo(1)

        val wirelessChargingViewSecond = WirelessChargingView.create(
                context, UNKNOWN_BATTERY_LEVEL, UNKNOWN_BATTERY_LEVEL, false,
                RippleShape.ROUNDED_BOX, duration
        )
        wirelessChargingRippleController.show(wirelessChargingViewSecond, 0)

        assertEquals(wirelessChargingViewFirst,
                wirelessChargingRippleController.wirelessChargingView)
    }

    @Test
    fun hideWirelessChargingView_afterDuration() {
        fakeSystemClock.setElapsedRealtime(0L)
        val wirelessChargingView = WirelessChargingView.create(
                context, UNKNOWN_BATTERY_LEVEL, UNKNOWN_BATTERY_LEVEL, false,
                RippleShape.ROUNDED_BOX, duration
        )
        wirelessChargingRippleController.show(wirelessChargingView, 0)

        assertThat(fakeExecutor.numPending()).isEqualTo(2)
        with(fakeExecutor) {
            runNextReady() // run showInternal
            advanceClockToNext()
            runNextReady() // run hideInternal
        }

        assertEquals(null, wirelessChargingRippleController.wirelessChargingView)
    }

    @Test
    fun showWirelessChargingView_withCallback_triggersCallback() {
        val callback = object : WirelessChargingRippleController.Callback {
            var onAnimationStartingCalled = false
            var onAnimationEndedCalled = false

            override fun onAnimationStarting() {
                onAnimationStartingCalled = true
            }

            override fun onAnimationEnded() {
                onAnimationEndedCalled = true
            }
        }
        val wirelessChargingView = WirelessChargingView.create(
                context, UNKNOWN_BATTERY_LEVEL, UNKNOWN_BATTERY_LEVEL, false,
                RippleShape.CIRCLE, duration
        )
        wirelessChargingRippleController.show(wirelessChargingView, 0, callback)
        assertThat(fakeExecutor.numPending()).isEqualTo(2)

        fakeExecutor.runNextReady() // run showInternal
        assertEquals(true, callback.onAnimationStartingCalled)

        fakeExecutor.advanceClockToNext()

        fakeExecutor.runNextReady() // run hideInternal
        assertEquals(true, callback.onAnimationEndedCalled)
    }
}
