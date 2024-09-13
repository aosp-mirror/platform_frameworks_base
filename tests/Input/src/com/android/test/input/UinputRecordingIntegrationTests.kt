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

import android.Manifest.permission.ASSOCIATE_INPUT_DEVICE_TO_DISPLAY
import android.app.Instrumentation
import android.cts.input.EventVerifier
import android.graphics.PointF
import android.hardware.input.InputManager
import android.os.ParcelFileDescriptor
import android.util.Size
import android.view.MotionEvent
import androidx.test.platform.app.InstrumentationRegistry
import com.android.cts.input.BatchedEventSplitter
import com.android.cts.input.InputJsonParser
import com.android.cts.input.VirtualDisplayActivityScenario
import com.android.cts.input.inputeventmatchers.isResampled
import com.android.cts.input.inputeventmatchers.withButtonState
import com.android.cts.input.inputeventmatchers.withHistorySize
import com.android.cts.input.inputeventmatchers.withMotionAction
import com.android.cts.input.inputeventmatchers.withPressure
import com.android.cts.input.inputeventmatchers.withRawCoords
import com.android.cts.input.inputeventmatchers.withSource
import java.io.InputStream
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
                    "GooglePixelTabletTouchscreen", R.raw.google_pixel_tablet_touchscreen,
                    R.raw.google_pixel_tablet_touchscreen_events, Size(1600, 2560),
                    vendorId = 0x0603, productId = 0x7806
                ),
            )

        const val INPUT_DEVICE_SOURCE_ALL = -1
    }

    class TestData(
        val name: String,
        val uinputRecordingResource: Int,
        val expectedEventsResource: Int,
        val displaySize: Size,
        val vendorId: Int,
        val productId: Int,
    ) {
        override fun toString(): String = name
    }

    private lateinit var instrumentation: Instrumentation
    private lateinit var parser: InputJsonParser

    @get:Rule
    val testName = TestName()

    @Parameterized.Parameter(0)
    lateinit var testData: TestData

    @Before
    fun setUp() {
        instrumentation = InstrumentationRegistry.getInstrumentation()
        parser = InputJsonParser(instrumentation.context)
    }

    @Test
    fun testEvemuRecording() {
        VirtualDisplayActivityScenario.AutoClose<CaptureEventActivity>(
            testName,
            size = testData.displaySize
        ).use { scenario ->
            scenario.activity.window.decorView.requestUnbufferedDispatch(INPUT_DEVICE_SOURCE_ALL)

            try {
                instrumentation.uiAutomation.adoptShellPermissionIdentity(
                    ASSOCIATE_INPUT_DEVICE_TO_DISPLAY,
                )

                val inputPort = "uinput:1:${testData.vendorId}:${testData.productId}"
                val inputManager =
                    instrumentation.context.getSystemService(InputManager::class.java)!!
                try {
                    inputManager.addUniqueIdAssociationByPort(
                        inputPort,
                        scenario.virtualDisplay.display.uniqueId!!,
                    )

                    val verifier =
                        EventVerifier(BatchedEventSplitter { scenario.activity.getInputEvent() })
                    injectUinputEvents()
                    verifyEvents(verifier)
                    scenario.activity.assertNoEvents()
                } finally {
                    inputManager.removeUniqueIdAssociationByPort(inputPort)
                }
            } finally {
                instrumentation.uiAutomation.dropShellPermissionIdentity()
            }
        }
    }

    private fun injectUinputEvents() {
        val fds = instrumentation.uiAutomation!!.executeShellCommandRw("uinput -")

        ParcelFileDescriptor.AutoCloseOutputStream(fds[1]).use { stdIn ->
            val inputStream: InputStream = instrumentation.context.resources.openRawResource(
                testData.uinputRecordingResource,
            )
            stdIn.write(inputStream.readBytes())
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
