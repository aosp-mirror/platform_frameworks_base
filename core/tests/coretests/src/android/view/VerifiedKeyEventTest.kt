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

import android.view.InputDevice.SOURCE_KEYBOARD
import android.view.KeyEvent.ACTION_DOWN
import android.os.Parcel

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.runner.RunWith
import org.junit.Test

@RunWith(AndroidJUnit4::class)
@SmallTest
class VerifiedKeyEventTest {

    @Test
    fun testConstructor() {
        val event = createVerifiedKeyEvent()

        assertEquals(DEVICE_ID, event.deviceId)
        assertEquals(EVENT_TIME_NANOS, event.eventTimeNanos)
        assertEquals(SOURCE, event.source)
        assertEquals(DISPLAY_ID, event.displayId)

        assertEquals(ACTION, event.action)
        assertEquals(DOWN_TIME_NANOS, event.downTimeNanos)
        assertEquals(FLAGS, event.flags)
        assertEquals(KEY_CODE, event.keyCode)
        assertEquals(SCAN_CODE, event.scanCode)
        assertEquals(META_STATE, event.metaState)
        assertEquals(REPEAT_COUNT, event.repeatCount)
    }

    /**
     * Write to parcel as a KeyEvent, read back as a KeyEvent
     */
    @Test
    fun testParcelUnparcel() {
        val keyEvent = createVerifiedKeyEvent()
        val parcel = Parcel.obtain()
        keyEvent.writeToParcel(parcel, 0 /*flags*/)
        parcel.setDataPosition(0)

        val unparceledKeyEvent = VerifiedKeyEvent.CREATOR.createFromParcel(parcel)
        parcel.recycle()

        compareVerifiedKeyEvents(keyEvent, unparceledKeyEvent)
    }

    /**
     * Write to parcel as an InputEvent, read back as an InputEvent
     */
    @Test
    fun testParcelInputEvent() {
        val keyEvent = createVerifiedKeyEvent()
        val inputEvent: VerifiedInputEvent = keyEvent
        val parcel = Parcel.obtain()
        inputEvent.writeToParcel(parcel, 0 /*flags*/)
        parcel.setDataPosition(0)

        val unparceledEvent = VerifiedInputEvent.CREATOR.createFromParcel(parcel)
        parcel.recycle()

        assertTrue(unparceledEvent is VerifiedKeyEvent)
        compareVerifiedKeyEvents(keyEvent, unparceledEvent as VerifiedKeyEvent)
    }

    /**
     * Write to parcel as a KeyEvent, read back as an InputEvent
     */
    @Test
    fun testParcelKeyEvent() {
        val keyEvent = createVerifiedKeyEvent()
        val parcel = Parcel.obtain()
        keyEvent.writeToParcel(parcel, 0 /*flags*/)
        parcel.setDataPosition(0)

        val unparceledEvent = VerifiedInputEvent.CREATOR.createFromParcel(parcel)
        parcel.recycle()

        assertTrue(unparceledEvent is VerifiedKeyEvent)
        compareVerifiedKeyEvents(keyEvent, unparceledEvent as VerifiedKeyEvent)
    }

    /**
     * Write to parcel as an InputEvent, read back as a KeyEvent
     */
    @Test
    fun testParcelInputToKeyEvent() {
        val keyEvent = createVerifiedKeyEvent()
        val inputEvent: VerifiedInputEvent = keyEvent
        val parcel = Parcel.obtain()
        inputEvent.writeToParcel(parcel, 0 /*flags*/)
        parcel.setDataPosition(0)

        val unparceledEvent = VerifiedKeyEvent.CREATOR.createFromParcel(parcel)
        parcel.recycle()

        compareVerifiedKeyEvents(keyEvent, unparceledEvent)
    }

    @Test
    fun testEqualsHashcode() {
        val keyEvent1 = createVerifiedKeyEvent()
        val keyEvent2 = createVerifiedKeyEvent()
        compareVerifiedKeyEvents(keyEvent1, keyEvent2)
    }

    companion object {
        private const val DEVICE_ID = 0
        private const val EVENT_TIME_NANOS: Long = 2000
        private const val SOURCE = SOURCE_KEYBOARD
        private const val DISPLAY_ID = 2

        private const val ACTION = ACTION_DOWN
        private const val DOWN_TIME_NANOS: Long = 1000
        private const val FLAGS = 3
        private const val KEY_CODE = 4
        private const val SCAN_CODE = 5
        private const val META_STATE = 11
        private const val REPEAT_COUNT = 22

        private fun createVerifiedKeyEvent(): VerifiedKeyEvent {
            return VerifiedKeyEvent(DEVICE_ID, EVENT_TIME_NANOS, SOURCE, DISPLAY_ID, ACTION,
                    DOWN_TIME_NANOS, FLAGS, KEY_CODE, SCAN_CODE, META_STATE, REPEAT_COUNT)
        }

        private fun compareVerifiedKeyEvents(event1: VerifiedKeyEvent, event2: VerifiedKeyEvent) {
            assertEquals(event1, event2)
            assertEquals(event1.hashCode(), event2.hashCode())

            assertEquals(event1.deviceId, event2.deviceId)
            assertEquals(event1.eventTimeNanos, event2.eventTimeNanos)
            assertEquals(event1.source, event2.source)
            assertEquals(event1.displayId, event2.displayId)

            assertEquals(event1.action, event2.action)
            assertEquals(event1.downTimeNanos, event2.downTimeNanos)
            assertEquals(event1.flags, event2.flags)
            assertEquals(event1.keyCode, event2.keyCode)
            assertEquals(event1.scanCode, event2.scanCode)
            assertEquals(event1.metaState, event2.metaState)
            assertEquals(event1.repeatCount, event2.repeatCount)
        }
    }
}
