/*
 * Copyright (C) 2018 The Android Open Source Project
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

package android.view;

import static android.view.MotionEvent.ACTION_DOWN;
import static android.view.MotionEvent.ACTION_POINTER_DOWN;
import static android.view.MotionEvent.TOOL_TYPE_FINGER;

import static junit.framework.Assert.assertTrue;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;

import android.view.MotionEvent.PointerCoords;
import android.view.MotionEvent.PointerProperties;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.HashSet;
import java.util.Set;

@RunWith(AndroidJUnit4.class)
@SmallTest
public class MotionEventTest {
    private static final int ID_SOURCE_MASK = 0x3 << 30;

    @Test
    public void testObtainWithDisplayId() {
        final int pointerCount = 1;
        PointerProperties[] properties = new PointerProperties[pointerCount];
        final PointerCoords[] coords = new PointerCoords[pointerCount];
        for (int i = 0; i < pointerCount; i++) {
            final PointerCoords c = new PointerCoords();
            c.x = i * 10;
            c.y = i * 20;
            coords[i] = c;
            final PointerProperties p = new PointerProperties();
            p.id = i;
            p.toolType = TOOL_TYPE_FINGER;
            properties[i] = p;
        }

        int displayId = 2;
        MotionEvent motionEvent = MotionEvent.obtain(0, 0, ACTION_DOWN,
                pointerCount, properties, coords,
                0, 0, 0, 0, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, displayId, 0);

        MotionEvent motionEvent_Single = MotionEvent.obtain(0 /* downTime */, 0 /* eventTime */,
                ACTION_DOWN /* action */, 0f /* x */, 0f /* y */, 0/* pressure */, 0 /* size */,
                0 /* metaState */, 0 /* xPrecision */, 0 /* yPrecision */,
                0 /* deviceId */, 0 /* edgeFlags */, InputDevice.SOURCE_TOUCHSCREEN, displayId);

        assertEquals(displayId, motionEvent_Single.getDisplayId());
        assertEquals(displayId, motionEvent.getDisplayId());

        displayId = 5;
        motionEvent.setDisplayId(displayId);
        assertEquals(displayId, motionEvent.getDisplayId());
        motionEvent.recycle();

        // If invalid PointerProperties object is passed to obtain,
        // there should not be a native crash, and instead it should just return null
        properties[0] = null;
        motionEvent = MotionEvent.obtain(0, 0, ACTION_DOWN,
                pointerCount, properties, coords,
                0, 0, 0, 0, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, displayId, 0);
        assertNull(motionEvent);
    }

    @Test
    public void testCalculatesCursorPositionForTouchscreenEvents() {
        final MotionEvent event = MotionEvent.obtain(0 /* downTime */, 0 /* eventTime */,
                ACTION_DOWN, 30 /* x */, 50 /* y */, 0 /* metaState */);
        event.setSource(InputDevice.SOURCE_TOUCHSCREEN);

        assertTrue(Float.isNaN(event.getXCursorPosition()));
        assertTrue(Float.isNaN(event.getYCursorPosition()));
    }

    @Test
    public void testCalculatesCursorPositionForSimpleMouseEvents() {
        final MotionEvent event = MotionEvent.obtain(0 /* downTime */, 0 /* eventTime */,
                ACTION_DOWN, 30 /* x */, 50 /* y */, 0 /* metaState */);
        event.setSource(InputDevice.SOURCE_MOUSE);

        assertEquals(30, event.getXCursorPosition(), 0.1);
        assertEquals(50, event.getYCursorPosition(), 0.1);
    }

    @Test
    public void testCalculatesCursorPositionForSimpleMouseEventsWithOffset() {
        final MotionEvent event = MotionEvent.obtain(0 /* downTime */, 0 /* eventTime */,
                ACTION_DOWN, 30 /* x */, 50 /* y */, 0 /* metaState */);
        event.offsetLocation(10 /* deltaX */, 20 /* deltaY */);
        event.setSource(InputDevice.SOURCE_MOUSE);

        assertEquals(40, event.getXCursorPosition(), 0.1);
        assertEquals(70, event.getYCursorPosition(), 0.1);
    }


    @Test
    public void testCalculatesCursorPositionForMultiTouchMouseEvents() {
        final int pointerCount = 2;
        final PointerProperties[] properties = new PointerProperties[pointerCount];
        final PointerCoords[] coords = new PointerCoords[pointerCount];

        for (int i = 0; i < pointerCount; ++i) {
            properties[i] = new PointerProperties();
            properties[i].id = i;
            properties[i].toolType = MotionEvent.TOOL_TYPE_FINGER;

            coords[i] = new PointerCoords();
            coords[i].x = 20 + i * 20;
            coords[i].y = 60 - i * 20;
        }

        final MotionEvent event = MotionEvent.obtain(0 /* downTime */,
                0 /* eventTime */, ACTION_POINTER_DOWN, pointerCount, properties, coords,
                0 /* metaState */, 0 /* buttonState */, 1 /* xPrecision */, 1 /* yPrecision */,
                0 /* deviceId */, 0 /* edgeFlags */, InputDevice.SOURCE_MOUSE,
                0 /* flags */);

        assertEquals(30, event.getXCursorPosition(), 0.1);
        assertEquals(50, event.getYCursorPosition(), 0.1);
    }

    /**
     * Tests that it can generate 500 consecutive distinct numbers. This is a non-deterministic test
     * but with 30 bits randomness the failure rate is roughly 4.52e-5, which is negligible enough.
     * Probability formula: N * (N - 1) * ... * (N - n + 1) / N^n, where N = 2^30 and n = 500 for
     * this test.
     */
    @Test
    public void testObtainGeneratesUniqueId() {
        Set<Integer> set = new HashSet<>();
        for (int i = 0; i < 500; ++i) {
            final MotionEvent event = MotionEvent.obtain(0 /* downTime */, 0 /* eventTime */,
                    ACTION_DOWN, 30 /* x */, 50 /* y */, 0 /* metaState */);
            assertFalse("Found duplicate ID in round " + i, set.contains(event.getId()));
            set.add(event.getSequenceNumber());
        }
    }

    @Test
    public void testObtainGeneratesIdWithRightSource() {
        for (int i = 0; i < 500; ++i) {
            final MotionEvent event = MotionEvent.obtain(0 /* downTime */, 0 /* eventTime */,
                    ACTION_DOWN, 30 /* x */, 50 /* y */, 0 /* metaState */);
            assertEquals(0x3 << 30, ID_SOURCE_MASK & event.getId());
        }
    }

    @Test
    public void testEventRotation() {
        final MotionEvent event = MotionEvent.obtain(0 /* downTime */, 0 /* eventTime */,
                    ACTION_DOWN, 30 /* x */, 50 /* y */, 0 /* metaState */);
        MotionEvent rot90 = MotionEvent.obtain(event);
        rot90.transform(MotionEvent.createRotateMatrix(/* 90 deg */1, 1000, 600));
        assertEquals(50, (int) rot90.getX());
        assertEquals(570, (int) rot90.getY());

        MotionEvent rot180 = MotionEvent.obtain(event);
        rot180.transform(MotionEvent.createRotateMatrix(/* 180 deg */2, 1000, 600));
        assertEquals(970, (int) rot180.getX());
        assertEquals(550, (int) rot180.getY());

        MotionEvent rot270 = MotionEvent.obtain(event);
        rot270.transform(MotionEvent.createRotateMatrix(/* 270 deg */3, 1000, 600));
        assertEquals(950, (int) rot270.getX());
        assertEquals(30, (int) rot270.getY());
    }
}
