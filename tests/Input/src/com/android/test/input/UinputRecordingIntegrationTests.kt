/*
 * Copyright 2024 The Android Open Source Project
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
package com.android.test.input

import android.app.Instrumentation
import android.cts.input.EventVerifier
import android.graphics.PointF
import android.util.Log
import android.util.Size
import android.view.InputDevice
import android.view.InputEvent
import android.view.MotionEvent
import androidx.test.platform.app.InstrumentationRegistry
import com.android.cts.input.BatchedEventSplitter
import com.android.cts.input.CaptureEventActivity
import com.android.cts.input.DebugInputRule
import com.android.cts.input.EvemuDevice
import com.android.cts.input.InputJsonParser
import com.android.cts.input.VirtualDisplayActivityScenario
import com.android.cts.input.inputeventmatchers.isResampled
import com.android.cts.input.inputeventmatchers.withButtonState
import com.android.cts.input.inputeventmatchers.withHistorySize
import com.android.cts.input.inputeventmatchers.withMotionAction
import com.android.cts.input.inputeventmatchers.withPressure
import com.android.cts.input.inputeventmatchers.withRawCoords
import com.android.cts.input.inputeventmatchers.withSource
import junit.framework.Assert.fail
import org.hamcrest.Matchers.allOf
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestName
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/**
 * Integration tests for the input pipeline that replays recording taken from physical input devices
 * at the evdev interface level, and makes assertions on the events that are received by a test app.
 *
 * These tests associate the playback input device with a virtual display to make these tests
 * agnostic to the device form factor.
 *
 * New recordings can be taken using the `evemu-record` shell command.
 */
@RunWith(Parameterized::class)
class UinputRecordingIntegrationTests {

    companion object {
        /**
         * Add new test cases by adding a new [TestData] to the following list.
         */
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun data(): Iterable<Any> =
            listOf(
                TestData(
                    "GooglePixelTabletTouchscreen",
                    R.raw.google_pixel_tablet_touchscreen,
                    R.raw.google_pixel_tablet_touchscreen_events,
                    Size(1600, 2560),
                    vendorId = 0x0603,
                    productId = 0x7806,
                    deviceSources = InputDevice.SOURCE_TOUCHSCREEN,
                ),
            )

        /**
         * Use the debug mode to see the JSON-encoded received events in logcat.
         */
        const val DEBUG_RECEIVED_EVENTS = false

        const val INPUT_DEVICE_SOURCE_ALL = -1
        val TAG = UinputRecordingIntegrationTests::class.java.simpleName
    }

    class TestData(
        val name: String,
        val uinputRecordingResource: Int,
        val expectedEventsResource: Int,
        val displaySize: Size,
        val vendorId: Int,
        val productId: Int,
        val deviceSources: Int,
    ) {
        override fun toString(): String = name
    }

    private lateinit var instrumentation: Instrumentation
    private lateinit var parser: InputJsonParser

    @get:Rule
    val debugInputRule = DebugInputRule()

    @get:Rule
    val testName = TestName()

    @Parameterized.Parameter(0)
    lateinit var testData: TestData

    @Before
    fun setUp() {
        instrumentation = InstrumentationRegistry.getInstrumentation()
        parser = InputJsonParser(instrumentation.context)
    }

    @DebugInputRule.DebugInput(bug = 389901828)
    @Test
    fun testEvemuRecording() {
        VirtualDisplayActivityScenario.AutoClose<CaptureEventActivity>(
            testName,
            size = testData.displaySize
        ).use { scenario ->
            scenario.activity.window.decorView.requestUnbufferedDispatch(INPUT_DEVICE_SOURCE_ALL)

            EvemuDevice(
                instrumentation,
                testData.deviceSources,
                testData.vendorId,
                testData.productId,
                testData.uinputRecordingResource,
                scenario.virtualDisplay.display
            ).use { evemuDevice ->

                evemuDevice.injectEvents()

                if (DEBUG_RECEIVED_EVENTS) {
                    printReceivedEventsToLogcat(scenario.activity)
                    fail("Test cannot pass in debug mode!")
                }

                val verifier = EventVerifier(
                    BatchedEventSplitter { scenario.activity.getInputEvent() }
                )
                verifyEvents(verifier)
                scenario.activity.assertNoEvents()
            }
        }
    }

    private fun printReceivedEventsToLogcat(activity: CaptureEventActivity) {
        val getNextEvent = BatchedEventSplitter { activity.getInputEvent() }
        var receivedEvent: InputEvent? = getNextEvent()
        while (receivedEvent != null) {
            Log.d(TAG,
                parser.encodeEvent(receivedEvent)?.toString()
                    ?: "(Failed to encode received event)"
            )
            receivedEvent = getNextEvent()
        }
    }

    private fun verifyEvents(verifier: EventVerifier) {
        val uinputTestData = parser.getUinputTestData(testData.expectedEventsResource)
        for (test in uinputTestData) {
            for ((index, expectedEvent) in test.events.withIndex()) {
                if (expectedEvent is MotionEvent) {
                    verifier.assertReceivedMotion(
                        allOf(
                            withMotionAction(expectedEvent.action),
                            withSource(expectedEvent.source),
                            withButtonState(expectedEvent.buttonState),
                            withRawCoords(PointF(expectedEvent.rawX, expectedEvent.rawY)),
                            withPressure(expectedEvent.pressure),
                            isResampled(false),
                            withHistorySize(0),
                        ),
                        "${test.name}: Expected event at index $index",
                    )
                }
            }
        }
    }
}
