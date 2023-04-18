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

package com.android.wm.shell.back;

import static org.junit.Assert.assertEquals;

import android.window.BackEvent;
import android.window.BackMotionEvent;

import org.junit.Before;
import org.junit.Test;

public class TouchTrackerTest {
    private static final float FAKE_THRESHOLD = 400;
    private static final float INITIAL_X_LEFT_EDGE = 5;
    private static final float INITIAL_X_RIGHT_EDGE = FAKE_THRESHOLD - INITIAL_X_LEFT_EDGE;
    private TouchTracker mTouchTracker;

    @Before
    public void setUp() throws Exception {
        mTouchTracker = new TouchTracker();
        mTouchTracker.setProgressThreshold(FAKE_THRESHOLD);
    }

    @Test
    public void generatesProgress_onStart() {
        mTouchTracker.setGestureStartLocation(INITIAL_X_LEFT_EDGE, 0, BackEvent.EDGE_LEFT);
        BackMotionEvent event = mTouchTracker.createStartEvent(null);
        assertEquals(event.getProgress(), 0f, 0f);
    }

    @Test
    public void generatesProgress_leftEdge() {
        mTouchTracker.setGestureStartLocation(INITIAL_X_LEFT_EDGE, 0, BackEvent.EDGE_LEFT);
        float touchX = 10;

        // Pre-commit
        mTouchTracker.update(touchX, 0);
        assertEquals(getProgress(), (touchX - INITIAL_X_LEFT_EDGE) / FAKE_THRESHOLD, 0f);

        // Post-commit
        touchX += 100;
        mTouchTracker.setTriggerBack(true);
        mTouchTracker.update(touchX, 0);
        assertEquals(getProgress(), (touchX - INITIAL_X_LEFT_EDGE) / FAKE_THRESHOLD, 0f);

        // Cancel
        touchX -= 10;
        mTouchTracker.setTriggerBack(false);
        mTouchTracker.update(touchX, 0);
        assertEquals(getProgress(), 0, 0f);

        // Cancel more
        touchX -= 10;
        mTouchTracker.update(touchX, 0);
        assertEquals(getProgress(), 0, 0f);

        // Restart
        touchX += 10;
        mTouchTracker.update(touchX, 0);
        assertEquals(getProgress(), 0, 0f);

        // Restarted, but pre-commit
        float restartX = touchX;
        touchX += 10;
        mTouchTracker.update(touchX, 0);
        assertEquals(getProgress(), (touchX - restartX) / FAKE_THRESHOLD, 0f);

        // Restarted, post-commit
        touchX += 10;
        mTouchTracker.setTriggerBack(true);
        mTouchTracker.update(touchX, 0);
        assertEquals(getProgress(), (touchX - INITIAL_X_LEFT_EDGE) / FAKE_THRESHOLD, 0f);
    }

    @Test
    public void generatesProgress_rightEdge() {
        mTouchTracker.setGestureStartLocation(INITIAL_X_RIGHT_EDGE, 0, BackEvent.EDGE_RIGHT);
        float touchX = INITIAL_X_RIGHT_EDGE - 10; // Fake right edge

        // Pre-commit
        mTouchTracker.update(touchX, 0);
        assertEquals(getProgress(), (INITIAL_X_RIGHT_EDGE - touchX) / FAKE_THRESHOLD, 0f);

        // Post-commit
        touchX -= 100;
        mTouchTracker.setTriggerBack(true);
        mTouchTracker.update(touchX, 0);
        assertEquals(getProgress(), (INITIAL_X_RIGHT_EDGE - touchX) / FAKE_THRESHOLD, 0f);

        // Cancel
        touchX += 10;
        mTouchTracker.setTriggerBack(false);
        mTouchTracker.update(touchX, 0);
        assertEquals(getProgress(), 0, 0f);

        // Cancel more
        touchX += 10;
        mTouchTracker.update(touchX, 0);
        assertEquals(getProgress(), 0, 0f);

        // Restart
        touchX -= 10;
        mTouchTracker.update(touchX, 0);
        assertEquals(getProgress(), 0, 0f);

        // Restarted, but pre-commit
        float restartX = touchX;
        touchX -= 10;
        mTouchTracker.update(touchX, 0);
        assertEquals(getProgress(), (restartX - touchX) / FAKE_THRESHOLD, 0f);

        // Restarted, post-commit
        touchX -= 10;
        mTouchTracker.setTriggerBack(true);
        mTouchTracker.update(touchX, 0);
        assertEquals(getProgress(), (INITIAL_X_RIGHT_EDGE - touchX) / FAKE_THRESHOLD, 0f);
    }

    private float getProgress() {
        return mTouchTracker.createProgressEvent().getProgress();
    }
}
