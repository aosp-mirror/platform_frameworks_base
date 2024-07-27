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

import static android.view.InputDevice.SOURCE_CLASS_POINTER;
import static android.view.MotionEvent.ACTION_DOWN;
import static android.view.MotionEvent.ACTION_POINTER_DOWN;
import static android.view.MotionEvent.TOOL_TYPE_FINGER;

import static junit.framework.Assert.assertTrue;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.fail;

import android.graphics.Matrix;
import android.platform.test.annotations.Presubmit;
import android.view.MotionEvent.PointerCoords;
import android.view.MotionEvent.PointerProperties;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.HashSet;
import java.util.Set;

@RunWith(AndroidJUnit4.class)
@SmallTest
@Presubmit
public class MotionEventTest {
    private static final int ID_SOURCE_MASK = 0x3 << 30;

    private PointerCoords pointerCoords(float x, float y) {
        final var coords = new PointerCoords();
        coords.x = x;
        coords.y = y;
        return coords;
    }

    private PointerProperties fingerProperties(int id) {
        final var props = new PointerProperties();
        props.id = id;
        props.toolType = TOOL_TYPE_FINGER;
        return props;
    }

    @Test
    public void testObtainWithDisplayId() {
        final int pointerCount = 1;
        final var properties = new PointerProperties[]{fingerProperties(0)};
        final var coords = new PointerCoords[]{pointerCoords(10, 20)};

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
        final var properties = new PointerProperties[]{fingerProperties(0), fingerProperties(1)};
        final var coords = new PointerCoords[]{pointerCoords(20, 60), pointerCoords(40, 40)};

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
        // The un-rotated frame size.
        final int width = 600;
        final int height = 1000;
        final MotionEvent event = MotionEvent.obtain(0 /* downTime */, 0 /* eventTime */,
                ACTION_DOWN, 30 /* x */, 50 /* y */, 0 /* metaState */);
        event.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        assertEquals(Surface.ROTATION_0, event.getSurfaceRotation());

        MotionEvent rot90 = MotionEvent.obtain(event);
        rot90.transform(MotionEvent.createRotateMatrix(Surface.ROTATION_90, height, width));
        assertEquals(50, (int) rot90.getX());
        assertEquals(570, (int) rot90.getY());
        assertEquals(Surface.ROTATION_90, rot90.getSurfaceRotation());

        MotionEvent rot180 = MotionEvent.obtain(event);
        rot180.transform(MotionEvent.createRotateMatrix(Surface.ROTATION_180, width, height));
        assertEquals(570, (int) rot180.getX());
        assertEquals(950, (int) rot180.getY());
        assertEquals(Surface.ROTATION_180, rot180.getSurfaceRotation());

        MotionEvent rot270 = MotionEvent.obtain(event);
        rot270.transform(MotionEvent.createRotateMatrix(Surface.ROTATION_270, height, width));
        assertEquals(950, (int) rot270.getX());
        assertEquals(30, (int) rot270.getY());
        assertEquals(Surface.ROTATION_270, rot270.getSurfaceRotation());

        MotionEvent compoundRot = MotionEvent.obtain(event);
        compoundRot.transform(MotionEvent.createRotateMatrix(Surface.ROTATION_90, height, width));
        compoundRot.transform(MotionEvent.createRotateMatrix(Surface.ROTATION_180, height, width));
        assertEquals(950, (int) compoundRot.getX());
        assertEquals(30, (int) compoundRot.getY());
        assertEquals(Surface.ROTATION_270, compoundRot.getSurfaceRotation());

        MotionEvent rotInvalid = MotionEvent.obtain(event);
        Matrix mat = new Matrix();
        mat.setValues(new float[]{1, 2, 3, -4, -5, -6, 0, 0, 1});
        rotInvalid.transform(mat);
        assertEquals(-1, rotInvalid.getSurfaceRotation());
    }

    @Test
    public void testUsesPointerSourceByDefault() {
        final MotionEvent event = MotionEvent.obtain(0 /* downTime */, 0 /* eventTime */,
                ACTION_DOWN, 0 /* x */, 0 /* y */, 0 /* metaState */);
        assertTrue(event.isFromSource(SOURCE_CLASS_POINTER));
    }

    @Test
    public void testLocationOffsetOnlyAppliedToNonPointerSources() {
        final MotionEvent event = MotionEvent.obtain(0 /* downTime */, 0 /* eventTime */,
                ACTION_DOWN, 10 /* x */, 20 /* y */, 0 /* metaState */);
        event.offsetLocation(40, 50);

        // The offset should be applied since a pointer source is used by default.
        assertEquals(50, (int) event.getX());
        assertEquals(70, (int) event.getY());

        // The offset should not be applied if the source is changed to a non-pointer source.
        event.setSource(InputDevice.SOURCE_JOYSTICK);
        assertEquals(10, (int) event.getX());
        assertEquals(20, (int) event.getY());
    }

    @Test
    public void testSplit() {
        final int pointerCount = 2;
        final var properties = new PointerProperties[]{fingerProperties(0), fingerProperties(1)};
        final var coords = new PointerCoords[]{pointerCoords(20, 60), pointerCoords(40, 40)};

        final MotionEvent event = MotionEvent.obtain(0 /* downTime */,
                0 /* eventTime */, MotionEvent.ACTION_MOVE, pointerCount, properties, coords,
                0 /* metaState */, 0 /* buttonState */, 1 /* xPrecision */, 1 /* yPrecision */,
                0 /* deviceId */, 0 /* edgeFlags */, InputDevice.SOURCE_TOUCHSCREEN,
                0 /* flags */);

        final int idBits = ~0b1 & event.getPointerIdBits();
        final MotionEvent splitEvent = event.split(idBits);
        assertEquals(1, splitEvent.getPointerCount());
        assertEquals(1, splitEvent.getPointerId(0));
        assertEquals(40, (int) splitEvent.getX());
        assertEquals(40, (int) splitEvent.getY());
    }

    @Test
    public void testSplitFailsWhenNoIdsSpecified() {
        final int pointerCount = 2;
        final var properties = new PointerProperties[]{fingerProperties(0), fingerProperties(1)};
        final var coords = new PointerCoords[]{pointerCoords(20, 60), pointerCoords(40, 40)};

        final MotionEvent event = MotionEvent.obtain(0 /* downTime */,
                0 /* eventTime */, MotionEvent.ACTION_MOVE, pointerCount, properties, coords,
                0 /* metaState */, 0 /* buttonState */, 1 /* xPrecision */, 1 /* yPrecision */,
                0 /* deviceId */, 0 /* edgeFlags */, InputDevice.SOURCE_TOUCHSCREEN,
                0 /* flags */);

        try {
            final MotionEvent splitEvent = event.split(0);
            fail("Splitting event with id bits 0 should throw: " + splitEvent);
        } catch (IllegalArgumentException e) {
            // Expected
        }
    }

    @Test
    public void testSplitFailsWhenIdBitsDoNotMatch() {
        final int pointerCount = 2;
        final var properties = new PointerProperties[]{fingerProperties(0), fingerProperties(1)};
        final var coords = new PointerCoords[]{pointerCoords(20, 60), pointerCoords(40, 40)};

        final MotionEvent event = MotionEvent.obtain(0 /* downTime */,
                0 /* eventTime */, MotionEvent.ACTION_MOVE, pointerCount, properties, coords,
                0 /* metaState */, 0 /* buttonState */, 1 /* xPrecision */, 1 /* yPrecision */,
                0 /* deviceId */, 0 /* edgeFlags */, InputDevice.SOURCE_TOUCHSCREEN,
                0 /* flags */);

        try {
            final int idBits = 0b100;
            final MotionEvent splitEvent = event.split(idBits);
            fail("Splitting event with id bits that do not match any pointers should throw: "
                    + splitEvent);
        } catch (IllegalArgumentException e) {
            // Expected
        }
    }
}
