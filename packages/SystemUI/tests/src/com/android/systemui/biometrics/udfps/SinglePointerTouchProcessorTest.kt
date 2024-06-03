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

package com.android.systemui.biometrics.udfps

import android.graphics.Rect
import android.view.MotionEvent
import android.view.MotionEvent.INVALID_POINTER_ID
import android.view.MotionEvent.PointerProperties
import android.view.Surface
import android.view.Surface.Rotation
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.biometrics.shared.model.UdfpsOverlayParams
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import org.junit.runners.Parameterized.Parameters

@SmallTest
@RunWith(Parameterized::class)
class SinglePointerTouchProcessorTest(val testCase: TestCase) : SysuiTestCase() {
    private val overlapDetector = FakeOverlapDetector()
    private val underTest = SinglePointerTouchProcessor(overlapDetector)

    @Test
    fun processTouch() {
        overlapDetector.shouldReturn =
            testCase.currentPointers.associate { pointer -> pointer.id to pointer.onSensor }

        val actual =
            underTest.processTouch(
                testCase.event,
                testCase.previousPointerOnSensorId,
                testCase.overlayParams,
            )

        assertThat(actual).isInstanceOf(testCase.expected.javaClass)
        if (actual is TouchProcessorResult.ProcessedTouch) {
            assertThat(actual).isEqualTo(testCase.expected)
        }
    }

    data class TestCase(
        val event: MotionEvent,
        val currentPointers: List<TestPointer>,
        val previousPointerOnSensorId: Int,
        val overlayParams: UdfpsOverlayParams,
        val expected: TouchProcessorResult,
    ) {
        override fun toString(): String {
            val expectedOutput =
                if (expected is TouchProcessorResult.ProcessedTouch) {
                    expected.event.toString() +
                        ", (x: ${expected.touchData.x}, y: ${expected.touchData.y})" +
                        ", pointerOnSensorId: ${expected.pointerOnSensorId}" +
                        ", ..."
                } else {
                    TouchProcessorResult.Failure().toString()
                }
            return "{" +
                MotionEvent.actionToString(event.action) +
                ", (x: ${event.x}, y: ${event.y})" +
                ", scale: ${overlayParams.scaleFactor}" +
                ", rotation: " +
                Surface.rotationToString(overlayParams.rotation) +
                ", previousPointerOnSensorId: $previousPointerOnSensorId" +
                ", ...} expected: {$expectedOutput}"
        }
    }

    companion object {
        @Parameters(name = "{0}")
        @JvmStatic
        fun data(): List<TestCase> =
            listOf(
                    // MotionEvent.ACTION_DOWN
                    genPositiveTestCases(
                        motionEventAction = MotionEvent.ACTION_DOWN,
                        previousPointerOnSensorId = INVALID_POINTER_ID,
                        currentPointers = listOf(TestPointer(id = POINTER_ID_1, onSensor = true)),
                        expectedInteractionEvent = InteractionEvent.DOWN,
                        expectedPointerOnSensorId = POINTER_ID_1,
                    ),
                    genPositiveTestCases(
                        motionEventAction = MotionEvent.ACTION_DOWN,
                        previousPointerOnSensorId = INVALID_POINTER_ID,
                        currentPointers = listOf(TestPointer(id = POINTER_ID_1, onSensor = false)),
                        expectedInteractionEvent = InteractionEvent.UNCHANGED,
                        expectedPointerOnSensorId = INVALID_POINTER_ID,
                    ),
                    genPositiveTestCases(
                        motionEventAction = MotionEvent.ACTION_DOWN,
                        previousPointerOnSensorId = POINTER_ID_1,
                        currentPointers = listOf(TestPointer(id = POINTER_ID_1, onSensor = false)),
                        expectedInteractionEvent = InteractionEvent.UP,
                        expectedPointerOnSensorId = INVALID_POINTER_ID,
                    ),
                    // MotionEvent.ACTION_HOVER_ENTER
                    genPositiveTestCases(
                        motionEventAction = MotionEvent.ACTION_HOVER_ENTER,
                        previousPointerOnSensorId = INVALID_POINTER_ID,
                        currentPointers = listOf(TestPointer(id = POINTER_ID_1, onSensor = true)),
                        expectedInteractionEvent = InteractionEvent.DOWN,
                        expectedPointerOnSensorId = POINTER_ID_1,
                    ),
                    genPositiveTestCases(
                        motionEventAction = MotionEvent.ACTION_HOVER_ENTER,
                        previousPointerOnSensorId = INVALID_POINTER_ID,
                        currentPointers = listOf(TestPointer(id = POINTER_ID_1, onSensor = false)),
                        expectedInteractionEvent = InteractionEvent.UNCHANGED,
                        expectedPointerOnSensorId = INVALID_POINTER_ID,
                    ),
                    genPositiveTestCases(
                        motionEventAction = MotionEvent.ACTION_HOVER_ENTER,
                        previousPointerOnSensorId = POINTER_ID_1,
                        currentPointers = listOf(TestPointer(id = POINTER_ID_1, onSensor = false)),
                        expectedInteractionEvent = InteractionEvent.UP,
                        expectedPointerOnSensorId = INVALID_POINTER_ID,
                    ),
                    // MotionEvent.ACTION_MOVE
                    genPositiveTestCases(
                        motionEventAction = MotionEvent.ACTION_MOVE,
                        previousPointerOnSensorId = INVALID_POINTER_ID,
                        currentPointers = listOf(TestPointer(id = POINTER_ID_1, onSensor = true)),
                        expectedInteractionEvent = InteractionEvent.DOWN,
                        expectedPointerOnSensorId = POINTER_ID_1,
                    ),
                    genPositiveTestCases(
                        motionEventAction = MotionEvent.ACTION_MOVE,
                        previousPointerOnSensorId = POINTER_ID_1,
                        currentPointers = listOf(TestPointer(id = POINTER_ID_1, onSensor = true)),
                        expectedInteractionEvent = InteractionEvent.UNCHANGED,
                        expectedPointerOnSensorId = POINTER_ID_1,
                    ),
                    genPositiveTestCases(
                        motionEventAction = MotionEvent.ACTION_MOVE,
                        previousPointerOnSensorId = INVALID_POINTER_ID,
                        currentPointers = listOf(TestPointer(id = POINTER_ID_1, onSensor = false)),
                        expectedInteractionEvent = InteractionEvent.UNCHANGED,
                        expectedPointerOnSensorId = INVALID_POINTER_ID,
                    ),
                    genPositiveTestCases(
                        motionEventAction = MotionEvent.ACTION_MOVE,
                        previousPointerOnSensorId = POINTER_ID_1,
                        currentPointers = listOf(TestPointer(id = POINTER_ID_1, onSensor = false)),
                        expectedInteractionEvent = InteractionEvent.UP,
                        expectedPointerOnSensorId = INVALID_POINTER_ID,
                    ),
                    genPositiveTestCases(
                        motionEventAction = MotionEvent.ACTION_MOVE,
                        previousPointerOnSensorId = INVALID_POINTER_ID,
                        currentPointers =
                            listOf(
                                TestPointer(id = POINTER_ID_1, onSensor = false),
                                TestPointer(id = POINTER_ID_2, onSensor = true)
                            ),
                        expectedInteractionEvent = InteractionEvent.DOWN,
                        expectedPointerOnSensorId = POINTER_ID_2,
                    ),
                    genPositiveTestCases(
                        motionEventAction = MotionEvent.ACTION_MOVE,
                        previousPointerOnSensorId = POINTER_ID_1,
                        currentPointers =
                            listOf(
                                TestPointer(id = POINTER_ID_1, onSensor = false),
                                TestPointer(id = POINTER_ID_2, onSensor = true)
                            ),
                        expectedInteractionEvent = InteractionEvent.UNCHANGED,
                        expectedPointerOnSensorId = POINTER_ID_2,
                    ),
                    // MotionEvent.ACTION_HOVER_MOVE
                    genPositiveTestCases(
                        motionEventAction = MotionEvent.ACTION_HOVER_MOVE,
                        previousPointerOnSensorId = INVALID_POINTER_ID,
                        currentPointers = listOf(TestPointer(id = POINTER_ID_1, onSensor = true)),
                        expectedInteractionEvent = InteractionEvent.DOWN,
                        expectedPointerOnSensorId = POINTER_ID_1,
                    ),
                    genPositiveTestCases(
                        motionEventAction = MotionEvent.ACTION_HOVER_MOVE,
                        previousPointerOnSensorId = POINTER_ID_1,
                        currentPointers = listOf(TestPointer(id = POINTER_ID_1, onSensor = true)),
                        expectedInteractionEvent = InteractionEvent.UNCHANGED,
                        expectedPointerOnSensorId = POINTER_ID_1,
                    ),
                    genPositiveTestCases(
                        motionEventAction = MotionEvent.ACTION_HOVER_MOVE,
                        previousPointerOnSensorId = INVALID_POINTER_ID,
                        currentPointers = listOf(TestPointer(id = POINTER_ID_1, onSensor = false)),
                        expectedInteractionEvent = InteractionEvent.UNCHANGED,
                        expectedPointerOnSensorId = INVALID_POINTER_ID,
                    ),
                    genPositiveTestCases(
                        motionEventAction = MotionEvent.ACTION_HOVER_MOVE,
                        previousPointerOnSensorId = POINTER_ID_1,
                        currentPointers = listOf(TestPointer(id = POINTER_ID_1, onSensor = false)),
                        expectedInteractionEvent = InteractionEvent.UP,
                        expectedPointerOnSensorId = INVALID_POINTER_ID,
                    ),
                    // MotionEvent.ACTION_UP
                    genPositiveTestCases(
                        motionEventAction = MotionEvent.ACTION_UP,
                        previousPointerOnSensorId = INVALID_POINTER_ID,
                        currentPointers = listOf(TestPointer(id = POINTER_ID_1, onSensor = true)),
                        expectedInteractionEvent = InteractionEvent.UP,
                        expectedPointerOnSensorId = INVALID_POINTER_ID,
                    ),
                    genPositiveTestCases(
                        motionEventAction = MotionEvent.ACTION_UP,
                        previousPointerOnSensorId = POINTER_ID_1,
                        currentPointers = listOf(TestPointer(id = POINTER_ID_1, onSensor = true)),
                        expectedInteractionEvent = InteractionEvent.UP,
                        expectedPointerOnSensorId = INVALID_POINTER_ID,
                    ),
                    genPositiveTestCases(
                        motionEventAction = MotionEvent.ACTION_UP,
                        previousPointerOnSensorId = INVALID_POINTER_ID,
                        currentPointers = listOf(TestPointer(id = POINTER_ID_1, onSensor = false)),
                        expectedInteractionEvent = InteractionEvent.UNCHANGED,
                        expectedPointerOnSensorId = INVALID_POINTER_ID,
                    ),
                    // MotionEvent.ACTION_HOVER_EXIT
                    genPositiveTestCases(
                        motionEventAction = MotionEvent.ACTION_HOVER_EXIT,
                        previousPointerOnSensorId = INVALID_POINTER_ID,
                        currentPointers = listOf(TestPointer(id = POINTER_ID_1, onSensor = true)),
                        expectedInteractionEvent = InteractionEvent.UP,
                        expectedPointerOnSensorId = INVALID_POINTER_ID,
                    ),
                    genPositiveTestCases(
                        motionEventAction = MotionEvent.ACTION_HOVER_EXIT,
                        previousPointerOnSensorId = POINTER_ID_1,
                        currentPointers = listOf(TestPointer(id = POINTER_ID_1, onSensor = true)),
                        expectedInteractionEvent = InteractionEvent.UP,
                        expectedPointerOnSensorId = INVALID_POINTER_ID,
                    ),
                    genPositiveTestCases(
                        motionEventAction = MotionEvent.ACTION_HOVER_EXIT,
                        previousPointerOnSensorId = INVALID_POINTER_ID,
                        currentPointers = listOf(TestPointer(id = POINTER_ID_1, onSensor = false)),
                        expectedInteractionEvent = InteractionEvent.UNCHANGED,
                        expectedPointerOnSensorId = INVALID_POINTER_ID,
                    ),
                    // MotionEvent.ACTION_CANCEL
                    genPositiveTestCases(
                        motionEventAction = MotionEvent.ACTION_CANCEL,
                        previousPointerOnSensorId = INVALID_POINTER_ID,
                        currentPointers = listOf(TestPointer(id = POINTER_ID_1, onSensor = true)),
                        expectedInteractionEvent = InteractionEvent.CANCEL,
                        expectedPointerOnSensorId = INVALID_POINTER_ID,
                    ),
                    genPositiveTestCases(
                        motionEventAction = MotionEvent.ACTION_CANCEL,
                        previousPointerOnSensorId = POINTER_ID_1,
                        currentPointers = listOf(TestPointer(id = POINTER_ID_1, onSensor = true)),
                        expectedInteractionEvent = InteractionEvent.CANCEL,
                        expectedPointerOnSensorId = INVALID_POINTER_ID,
                    ),
                    genPositiveTestCases(
                        motionEventAction = MotionEvent.ACTION_CANCEL,
                        previousPointerOnSensorId = INVALID_POINTER_ID,
                        currentPointers = listOf(TestPointer(id = POINTER_ID_1, onSensor = false)),
                        expectedInteractionEvent = InteractionEvent.CANCEL,
                        expectedPointerOnSensorId = INVALID_POINTER_ID,
                    ),
                    genPositiveTestCases(
                        motionEventAction = MotionEvent.ACTION_CANCEL,
                        previousPointerOnSensorId = POINTER_ID_1,
                        currentPointers = listOf(TestPointer(id = POINTER_ID_1, onSensor = false)),
                        expectedInteractionEvent = InteractionEvent.CANCEL,
                        expectedPointerOnSensorId = INVALID_POINTER_ID,
                    ),
                    // MotionEvent.ACTION_POINTER_DOWN
                    genPositiveTestCases(
                        motionEventAction =
                            MotionEvent.ACTION_POINTER_DOWN +
                                (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
                        previousPointerOnSensorId = INVALID_POINTER_ID,
                        currentPointers =
                            listOf(
                                TestPointer(id = POINTER_ID_1, onSensor = true),
                                TestPointer(id = POINTER_ID_2, onSensor = false)
                            ),
                        expectedInteractionEvent = InteractionEvent.DOWN,
                        expectedPointerOnSensorId = POINTER_ID_1,
                    ),
                    genPositiveTestCases(
                        motionEventAction =
                            MotionEvent.ACTION_POINTER_DOWN +
                                (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
                        previousPointerOnSensorId = INVALID_POINTER_ID,
                        currentPointers =
                            listOf(
                                TestPointer(id = POINTER_ID_1, onSensor = false),
                                TestPointer(id = POINTER_ID_2, onSensor = true)
                            ),
                        expectedInteractionEvent = InteractionEvent.DOWN,
                        expectedPointerOnSensorId = POINTER_ID_2
                    ),
                    genPositiveTestCases(
                        motionEventAction =
                            MotionEvent.ACTION_POINTER_DOWN +
                                (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
                        previousPointerOnSensorId = POINTER_ID_1,
                        currentPointers =
                            listOf(
                                TestPointer(id = POINTER_ID_1, onSensor = true),
                                TestPointer(id = POINTER_ID_2, onSensor = false)
                            ),
                        expectedInteractionEvent = InteractionEvent.UNCHANGED,
                        expectedPointerOnSensorId = POINTER_ID_1,
                    ),
                    // MotionEvent.ACTION_POINTER_UP
                    genPositiveTestCases(
                        motionEventAction =
                            MotionEvent.ACTION_POINTER_UP +
                                (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
                        previousPointerOnSensorId = INVALID_POINTER_ID,
                        currentPointers =
                            listOf(
                                TestPointer(id = POINTER_ID_1, onSensor = false),
                                TestPointer(id = POINTER_ID_2, onSensor = false)
                            ),
                        expectedInteractionEvent = InteractionEvent.UNCHANGED,
                        expectedPointerOnSensorId = INVALID_POINTER_ID
                    ),
                    genPositiveTestCases(
                        motionEventAction =
                            MotionEvent.ACTION_POINTER_UP +
                                (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
                        previousPointerOnSensorId = POINTER_ID_2,
                        currentPointers =
                            listOf(
                                TestPointer(id = POINTER_ID_1, onSensor = false),
                                TestPointer(id = POINTER_ID_2, onSensor = true)
                            ),
                        expectedInteractionEvent = InteractionEvent.UP,
                        expectedPointerOnSensorId = INVALID_POINTER_ID
                    ),
                    genPositiveTestCases(
                        motionEventAction = MotionEvent.ACTION_POINTER_UP,
                        previousPointerOnSensorId = POINTER_ID_1,
                        currentPointers =
                            listOf(
                                TestPointer(id = POINTER_ID_1, onSensor = true),
                                TestPointer(id = POINTER_ID_2, onSensor = false)
                            ),
                        expectedInteractionEvent = InteractionEvent.UP,
                        expectedPointerOnSensorId = INVALID_POINTER_ID
                    ),
                    genPositiveTestCases(
                        motionEventAction =
                            MotionEvent.ACTION_POINTER_UP +
                                (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
                        previousPointerOnSensorId = POINTER_ID_1,
                        currentPointers =
                            listOf(
                                TestPointer(id = POINTER_ID_1, onSensor = true),
                                TestPointer(id = POINTER_ID_2, onSensor = false)
                            ),
                        expectedInteractionEvent = InteractionEvent.UNCHANGED,
                        expectedPointerOnSensorId = POINTER_ID_1
                    ),
                    genPositiveTestCases(
                        motionEventAction = MotionEvent.ACTION_POINTER_UP,
                        previousPointerOnSensorId = POINTER_ID_2,
                        currentPointers =
                            listOf(
                                TestPointer(id = POINTER_ID_1, onSensor = false),
                                TestPointer(id = POINTER_ID_2, onSensor = true)
                            ),
                        expectedInteractionEvent = InteractionEvent.UNCHANGED,
                        expectedPointerOnSensorId = POINTER_ID_2
                    )
                )
                .flatten()
    }
}

data class TestPointer(val id: Int, val onSensor: Boolean)

/* Display dimensions in native resolution and natural orientation. */
private const val ROTATION_0_NATIVE_DISPLAY_WIDTH = 400
private const val ROTATION_0_NATIVE_DISPLAY_HEIGHT = 600

/* Placeholder touch parameters. */
private const val POINTER_ID_1 = 42
private const val POINTER_ID_2 = 43
private const val NATIVE_MINOR = 2.71828f
private const val NATIVE_MAJOR = 3.14f
private const val ORIENTATION = 1.2345f
private const val TIME = 12345699L
private const val GESTURE_START = 12345600L

/*
 * ROTATION_0 map:
 * _ _ _ _
 * _ _ O _
 * _ _ _ _
 * _ S _ _
 * _ S _ _
 * _ _ _ _
 *
 * (_) empty space
 * (S) sensor
 * (O) touch outside of the sensor
 */
private val ROTATION_0_NATIVE_SENSOR_BOUNDS =
    Rect(
        100, /* left */
        300, /* top */
        200, /* right */
        500, /* bottom */
    )
private val ROTATION_0_INPUTS =
    OrientationBasedInputs(
        rotation = Surface.ROTATION_0,
        nativeOrientation = ORIENTATION,
        nativeXWithinSensor = ROTATION_0_NATIVE_SENSOR_BOUNDS.exactCenterX(),
        nativeYWithinSensor = ROTATION_0_NATIVE_SENSOR_BOUNDS.exactCenterY(),
        nativeXOutsideSensor = 250f,
        nativeYOutsideSensor = 150f,
    )

/*
 * ROTATION_90 map:
 * _ _ _ _ _ _
 * _ O _ _ _ _
 * _ _ _ S S _
 * _ _ _ _ _ _
 *
 * (_) empty space
 * (S) sensor
 * (O) touch outside of the sensor
 */
private val ROTATION_90_NATIVE_SENSOR_BOUNDS =
    Rect(
        300, /* left */
        200, /* top */
        500, /* right */
        300, /* bottom */
    )
private val ROTATION_90_INPUTS =
    OrientationBasedInputs(
        rotation = Surface.ROTATION_90,
        nativeOrientation = (ORIENTATION - Math.PI.toFloat() / 2),
        nativeXWithinSensor = ROTATION_90_NATIVE_SENSOR_BOUNDS.exactCenterX(),
        nativeYWithinSensor = ROTATION_90_NATIVE_SENSOR_BOUNDS.exactCenterY(),
        nativeXOutsideSensor = 150f,
        nativeYOutsideSensor = 150f,
    )

/*
 * ROTATION_180 map:
 * _ _ _ _
 * _ _ s _
 * _ _ s _
 * _ _ _ _
 * _ O _ _
 * _ _ _ _
 *
 * (_) empty space
 * (S) sensor
 * (O) touch outside of the sensor
 */
private val ROTATION_180_NATIVE_SENSOR_BOUNDS =
    Rect(
        200, /* left */
        100, /* top */
        300, /* right */
        300, /* bottom */
    )
private val ROTATION_180_INPUTS =
    OrientationBasedInputs(
        rotation = Surface.ROTATION_180,
        nativeOrientation = (ORIENTATION - Math.PI.toFloat() / 2),
        nativeXWithinSensor = ROTATION_180_NATIVE_SENSOR_BOUNDS.exactCenterX(),
        nativeYWithinSensor = ROTATION_180_NATIVE_SENSOR_BOUNDS.exactCenterY(),
        nativeXOutsideSensor = 150f,
        nativeYOutsideSensor = 450f,
    )

/*
 * ROTATION_270 map:
 * _ _ _ _ _ _
 * _ S S _ _ _
 * _ _ _ _ O _
 * _ _ _ _ _ _
 *
 * (_) empty space
 * (S) sensor
 * (O) touch outside of the sensor
 */
private val ROTATION_270_NATIVE_SENSOR_BOUNDS =
    Rect(
        100, /* left */
        100, /* top */
        300, /* right */
        200, /* bottom */
    )
private val ROTATION_270_INPUTS =
    OrientationBasedInputs(
        rotation = Surface.ROTATION_270,
        nativeOrientation = (ORIENTATION + Math.PI.toFloat() / 2),
        nativeXWithinSensor = ROTATION_270_NATIVE_SENSOR_BOUNDS.exactCenterX(),
        nativeYWithinSensor = ROTATION_270_NATIVE_SENSOR_BOUNDS.exactCenterY(),
        nativeXOutsideSensor = 450f,
        nativeYOutsideSensor = 250f,
    )

/* Template [MotionEvent]. */
private val MOTION_EVENT =
    obtainMotionEvent(
        action = 0,
        pointerId = POINTER_ID_1,
        x = 0f,
        y = 0f,
        minor = 0f,
        major = 0f,
        orientation = ORIENTATION,
        time = TIME,
        gestureStart = GESTURE_START,
    )

/* Template [NormalizedTouchData]. */
private val NORMALIZED_TOUCH_DATA =
    NormalizedTouchData(
        POINTER_ID_1,
        x = 0f,
        y = 0f,
        NATIVE_MINOR,
        NATIVE_MAJOR,
        ORIENTATION,
        TIME,
        GESTURE_START
    )

/*
 * Contains test inputs that are tied to a particular device orientation.
 *
 * "native" means in native resolution (not scaled).
 */
private data class OrientationBasedInputs(
    @Rotation val rotation: Int,
    val nativeOrientation: Float,
    val nativeXWithinSensor: Float,
    val nativeYWithinSensor: Float,
    val nativeXOutsideSensor: Float,
    val nativeYOutsideSensor: Float,
) {

    fun toOverlayParams(scaleFactor: Float): UdfpsOverlayParams =
        UdfpsOverlayParams(
            sensorBounds = ROTATION_0_NATIVE_SENSOR_BOUNDS.scaled(scaleFactor),
            overlayBounds = ROTATION_0_NATIVE_SENSOR_BOUNDS.scaled(scaleFactor),
            naturalDisplayHeight = (ROTATION_0_NATIVE_DISPLAY_HEIGHT * scaleFactor).toInt(),
            naturalDisplayWidth = (ROTATION_0_NATIVE_DISPLAY_WIDTH * scaleFactor).toInt(),
            scaleFactor = scaleFactor,
            rotation = rotation
        )

    fun getNativeX(isWithinSensor: Boolean): Float {
        return if (isWithinSensor) nativeXWithinSensor else nativeXOutsideSensor
    }

    fun getNativeY(isWithinSensor: Boolean): Float {
        return if (isWithinSensor) nativeYWithinSensor else nativeYOutsideSensor
    }
}

private fun genPositiveTestCases(
    motionEventAction: Int,
    previousPointerOnSensorId: Int,
    currentPointers: List<TestPointer>,
    expectedInteractionEvent: InteractionEvent,
    expectedPointerOnSensorId: Int
): List<SinglePointerTouchProcessorTest.TestCase> {
    val scaleFactors = listOf(0.75f, 1f, 1.5f)
    val orientations =
        listOf(
            ROTATION_0_INPUTS,
            ROTATION_90_INPUTS,
            ROTATION_180_INPUTS,
            ROTATION_270_INPUTS,
        )
    return scaleFactors.flatMap { scaleFactor ->
        orientations.map { orientation ->
            val overlayParams = orientation.toOverlayParams(scaleFactor)

            val pointerProperties =
                currentPointers
                    .map { pointer ->
                        val pp = MotionEvent.PointerProperties()
                        pp.id = pointer.id
                        pp
                    }
                    .toList()

            val pointerCoords =
                currentPointers
                    .map { pointer ->
                        val pc = MotionEvent.PointerCoords()
                        pc.x = orientation.getNativeX(pointer.onSensor) * scaleFactor
                        pc.y = orientation.getNativeY(pointer.onSensor) * scaleFactor
                        pc.touchMinor = NATIVE_MINOR * scaleFactor
                        pc.touchMajor = NATIVE_MAJOR * scaleFactor
                        pc.orientation = orientation.nativeOrientation
                        pc
                    }
                    .toList()

            val event =
                MOTION_EVENT.copy(
                    action = motionEventAction,
                    pointerProperties = pointerProperties,
                    pointerCoords = pointerCoords
                )

            val expectedTouchDataPointer =
                currentPointers.find { it.id == expectedPointerOnSensorId }
                    ?: currentPointers.find { it.id == previousPointerOnSensorId }
                        ?: currentPointers[0]
            val expectedTouchData =
                if (motionEventAction != MotionEvent.ACTION_CANCEL) {
                    NORMALIZED_TOUCH_DATA.copy(
                        pointerId = expectedTouchDataPointer.id,
                        x = ROTATION_0_INPUTS.getNativeX(expectedTouchDataPointer.onSensor),
                        y = ROTATION_0_INPUTS.getNativeY(expectedTouchDataPointer.onSensor)
                    )
                } else {
                    NormalizedTouchData()
                }

            val expected =
                TouchProcessorResult.ProcessedTouch(
                    event = expectedInteractionEvent,
                    pointerOnSensorId = expectedPointerOnSensorId,
                    touchData = expectedTouchData,
                )
            SinglePointerTouchProcessorTest.TestCase(
                event = event,
                currentPointers = currentPointers,
                previousPointerOnSensorId = previousPointerOnSensorId,
                overlayParams = overlayParams,
                expected = expected,
            )
        }
    }
}

private fun obtainMotionEvent(
    action: Int,
    pointerId: Int,
    x: Float,
    y: Float,
    minor: Float,
    major: Float,
    orientation: Float,
    time: Long,
    gestureStart: Long,
): MotionEvent {
    val pp = PointerProperties()
    pp.id = pointerId
    val pc = MotionEvent.PointerCoords()
    pc.x = x
    pc.y = y
    pc.touchMinor = minor
    pc.touchMajor = major
    pc.orientation = orientation
    return obtainMotionEvent(action, arrayOf(pp), arrayOf(pc), time, gestureStart)
}

private fun obtainMotionEvent(
    action: Int,
    pointerProperties: Array<MotionEvent.PointerProperties>,
    pointerCoords: Array<MotionEvent.PointerCoords>,
    time: Long,
    gestureStart: Long,
): MotionEvent {
    return MotionEvent.obtain(
        gestureStart /* downTime */,
        time /* eventTime */,
        action /* action */,
        pointerCoords.size /* pointerCount */,
        pointerProperties /* pointerProperties */,
        pointerCoords /* pointerCoords */,
        0 /* metaState */,
        0 /* buttonState */,
        1f /* xPrecision */,
        1f /* yPrecision */,
        0 /* deviceId */,
        0 /* edgeFlags */,
        0 /* source */,
        0 /* flags */
    )
}

private fun MotionEvent.copy(
    action: Int = this.action,
    pointerId: Int = this.getPointerId(0),
    x: Float = this.rawX,
    y: Float = this.rawY,
    minor: Float = this.touchMinor,
    major: Float = this.touchMajor,
    orientation: Float = this.orientation,
    time: Long = this.eventTime,
    gestureStart: Long = this.downTime,
) = obtainMotionEvent(action, pointerId, x, y, minor, major, orientation, time, gestureStart)

private fun MotionEvent.copy(
    action: Int = this.action,
    pointerProperties: List<MotionEvent.PointerProperties>,
    pointerCoords: List<MotionEvent.PointerCoords>,
    time: Long = this.eventTime,
    gestureStart: Long = this.downTime
) =
    obtainMotionEvent(
        action,
        pointerProperties.toTypedArray(),
        pointerCoords.toTypedArray(),
        time,
        gestureStart
    )

private fun Rect.scaled(scaleFactor: Float) = Rect(this).apply { scale(scaleFactor) }
