/*
 * Copyright (C) 2020 The Android Open Source Project
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

package android.view

import android.view.InputDevice.SOURCE_TOUCHSCREEN
import android.view.MotionEvent.ACTION_MOVE
import android.view.MotionEvent.FLAG_IS_ACCESSIBILITY_EVENT
import android.view.MotionEvent.FLAG_WINDOW_IS_OBSCURED
import android.view.MotionEvent.FLAG_WINDOW_IS_PARTIALLY_OBSCURED
import android.view.MotionEvent.FLAG_TAINTED
import android.os.Parcel

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.runner.RunWith
import org.junit.Test

@RunWith(AndroidJUnit4::class)
@SmallTest
class VerifiedMotionEventTest {

    @Test
    fun testConstructor() {
        val event = createVerifiedMotionEvent()

        assertEquals(DEVICE_ID, event.deviceId)
        assertEquals(EVENT_TIME_NANOS, event.eventTimeNanos)
        assertEquals(SOURCE, event.source)
        assertEquals(DISPLAY_ID, event.displayId)

        assertEquals(RAW_X, event.rawX, 0f)
        assertEquals(RAW_Y, event.rawY, 0f)
        assertEquals(ACTION_MASKED, event.actionMasked)
        assertEquals(DOWN_TIME_NANOS, event.downTimeNanos)
        assertEquals(FLAGS, event.flags)
        assertEquals(META_STATE, event.metaState)
        assertEquals(BUTTON_STATE, event.buttonState)
    }

    /**
     * Write to parcel as a MotionEvent, read back as a MotionEvent
     */
    @Test
    fun testParcelUnparcel() {
        val motionEvent = createVerifiedMotionEvent()
        val parcel = Parcel.obtain()
        motionEvent.writeToParcel(parcel, 0 /*flags*/)
        parcel.setDataPosition(0)

        val unparceledMotionEvent = VerifiedMotionEvent.CREATOR.createFromParcel(parcel)
        parcel.recycle()

        compareVerifiedMotionEvents(motionEvent, unparceledMotionEvent)
    }

    /**
     * Write to parcel as an InputEvent, read back as an InputEvent
     */
    @Test
    fun testParcelInputEvent() {
        val motionEvent = createVerifiedMotionEvent()
        val inputEvent: VerifiedInputEvent = motionEvent
        val parcel = Parcel.obtain()
        inputEvent.writeToParcel(parcel, 0 /*flags*/)
        parcel.setDataPosition(0)

        val unparceledEvent = VerifiedInputEvent.CREATOR.createFromParcel(parcel)
        parcel.recycle()

        assertTrue(unparceledEvent is VerifiedMotionEvent)
        compareVerifiedMotionEvents(motionEvent, unparceledEvent as VerifiedMotionEvent)
    }

    /**
     * Write to parcel as a MotionEvent, read back as an InputEvent
     */
    @Test
    fun testParcelMotionEvent() {
        val motionEvent = createVerifiedMotionEvent()
        val parcel = Parcel.obtain()
        motionEvent.writeToParcel(parcel, 0 /*flags*/)
        parcel.setDataPosition(0)

        val unparceledEvent = VerifiedInputEvent.CREATOR.createFromParcel(parcel)
        parcel.recycle()

        assertTrue(unparceledEvent is VerifiedMotionEvent)
        compareVerifiedMotionEvents(motionEvent, unparceledEvent as VerifiedMotionEvent)
    }

    /**
     * Write to parcel as an InputEvent, read back as a MotionEvent
     */
    @Test
    fun testParcelInputToMotionEvent() {
        val motionEvent = createVerifiedMotionEvent()
        val inputEvent: VerifiedInputEvent = motionEvent
        val parcel = Parcel.obtain()
        inputEvent.writeToParcel(parcel, 0 /*flags*/)
        parcel.setDataPosition(0)

        val unparceledEvent = VerifiedMotionEvent.CREATOR.createFromParcel(parcel)
        parcel.recycle()

        compareVerifiedMotionEvents(motionEvent, unparceledEvent)
    }

    @Test
    fun testGetFlag() {
        val motionEvent = createVerifiedMotionEvent()
        // Invalid value of a flag
        assertNull(motionEvent.getFlag(0))
        // Flag that was not set
        assertEquals(false, motionEvent.getFlag(FLAG_WINDOW_IS_PARTIALLY_OBSCURED))
        // Flags that were set
        assertEquals(true, motionEvent.getFlag(FLAG_WINDOW_IS_OBSCURED))
        assertEquals(true, motionEvent.getFlag(FLAG_IS_ACCESSIBILITY_EVENT))
        // Only 1 flag at a time is accepted
        assertNull(motionEvent.getFlag(
                FLAG_WINDOW_IS_PARTIALLY_OBSCURED or FLAG_WINDOW_IS_OBSCURED))
        // Flag that is not verified returns null
        assertNull(motionEvent.getFlag(FLAG_TAINTED))
    }

    @Test
    fun testEqualsHashcode() {
        val motionEvent1 = createVerifiedMotionEvent()
        val motionEvent2 = createVerifiedMotionEvent()
        compareVerifiedMotionEvents(motionEvent1, motionEvent2)
    }

    companion object {
        private const val DEVICE_ID = 0
        private const val EVENT_TIME_NANOS: Long = 2000
        private const val SOURCE = SOURCE_TOUCHSCREEN
        private const val DISPLAY_ID = 2
        private const val RAW_X = 100f
        private const val RAW_Y = 200f
        private const val ACTION_MASKED = ACTION_MOVE
        private const val DOWN_TIME_NANOS: Long = 1000
        private const val FLAGS = FLAG_WINDOW_IS_OBSCURED or FLAG_IS_ACCESSIBILITY_EVENT
        private const val META_STATE = 11
        private const val BUTTON_STATE = 22

        private fun createVerifiedMotionEvent(): VerifiedMotionEvent {
            return VerifiedMotionEvent(DEVICE_ID, EVENT_TIME_NANOS, SOURCE, DISPLAY_ID,
                    RAW_X, RAW_Y, ACTION_MASKED, DOWN_TIME_NANOS, FLAGS, META_STATE, BUTTON_STATE)
        }

        private fun compareVerifiedMotionEvents(
            event1: VerifiedMotionEvent,
            event2: VerifiedMotionEvent
        ) {
            assertEquals(event1, event2)
            assertEquals(event1.hashCode(), event2.hashCode())

            assertEquals(event1.deviceId, event2.deviceId)
            assertEquals(event1.eventTimeNanos, event2.eventTimeNanos)
            assertEquals(event1.source, event2.source)
            assertEquals(event1.displayId, event2.displayId)

            assertEquals(event1.rawX, event2.rawX, 0f)
            assertEquals(event1.rawY, event2.rawY, 0f)
            assertEquals(event1.actionMasked, event2.actionMasked)
            assertEquals(event1.downTimeNanos, event2.downTimeNanos)
            assertEquals(event1.flags, event2.flags)
            assertEquals(event1.metaState, event2.metaState)
            assertEquals(event1.buttonState, event2.buttonState)
        }
    }
}
