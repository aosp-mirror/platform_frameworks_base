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
import static android.view.MotionEvent.TOOL_TYPE_FINGER;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import android.view.MotionEvent.PointerCoords;
import android.view.MotionEvent.PointerProperties;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
@SmallTest
public class MotionEventTest {

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
}
