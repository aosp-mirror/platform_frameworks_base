/*
 * Copyright (C) 2017 The Android Open Source Project
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

package com.android.server.accessibility;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.content.res.Resources;
import android.graphics.Point;
import android.graphics.PointF;
import android.util.DisplayMetrics;
import android.view.GestureDetector;
import android.view.MotionEvent;

import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;

/**
 * Tests for AccessibilityGestureDetector
 */
public class AccessibilityGestureDetectorTest {

    // Constants for testRecognizeGesturePath()
    private static final PointF PATH_START = new PointF(300f, 300f);
    private static final int PATH_STEP_PIXELS = 200;
    private static final long PATH_STEP_MILLISEC = 100;

    // Data used by all tests
    private AccessibilityGestureDetector mDetector;
    private AccessibilityGestureDetector.Listener mResultListener;

    @Before
    public void setUp() {
        // Construct a mock Context.
        DisplayMetrics displayMetricsMock = mock(DisplayMetrics.class);
        displayMetricsMock.xdpi = 500;
        displayMetricsMock.ydpi = 500;
        Resources mockResources = mock(Resources.class);
        when(mockResources.getDisplayMetrics()).thenReturn(displayMetricsMock);
        Context contextMock = mock(Context.class);
        when(contextMock.getResources()).thenReturn(mockResources);

        // Construct a testable AccessibilityGestureDetector.
        mResultListener = mock(AccessibilityGestureDetector.Listener.class);
        GestureDetector doubleTapDetectorMock = mock(GestureDetector.class);
        mDetector = new AccessibilityGestureDetector(contextMock, mResultListener, doubleTapDetectorMock);
    }


    @Test
    public void testRecognizeGesturePath() {
        final int d = 1000;  // Length of each segment in the test gesture, in pixels.

        testPath(p(-d, +0), AccessibilityService.GESTURE_SWIPE_LEFT);
        testPath(p(+d, +0), AccessibilityService.GESTURE_SWIPE_RIGHT);
        testPath(p(+0, -d), AccessibilityService.GESTURE_SWIPE_UP);
        testPath(p(+0, +d), AccessibilityService.GESTURE_SWIPE_DOWN);

        testPath(p(-d, +0), p((-d - d), +0), AccessibilityService.GESTURE_SWIPE_LEFT);
        testPath(p(-d, +0), p(+0, +0), AccessibilityService.GESTURE_SWIPE_LEFT_AND_RIGHT);
        testPath(p(-d, +0), p(-d, -d), AccessibilityService.GESTURE_SWIPE_LEFT_AND_UP);
        testPath(p(-d, +0), p(-d, +d), AccessibilityService.GESTURE_SWIPE_LEFT_AND_DOWN);

        testPath(p(+d, +0), p(+0, +0), AccessibilityService.GESTURE_SWIPE_RIGHT_AND_LEFT);
        testPath(p(+d, +0), p((+d + d), +0), AccessibilityService.GESTURE_SWIPE_RIGHT);
        testPath(p(+d, +0), p(+d, -d), AccessibilityService.GESTURE_SWIPE_RIGHT_AND_UP);
        testPath(p(+d, +0), p(+d, +d), AccessibilityService.GESTURE_SWIPE_RIGHT_AND_DOWN);

        testPath(p(+0, -d), p(-d, -d), AccessibilityService.GESTURE_SWIPE_UP_AND_LEFT);
        testPath(p(+0, -d), p(+d, -d), AccessibilityService.GESTURE_SWIPE_UP_AND_RIGHT);
        testPath(p(+0, -d), p(+0, (-d - d)), AccessibilityService.GESTURE_SWIPE_UP);
        testPath(p(+0, -d), p(+0, +0), AccessibilityService.GESTURE_SWIPE_UP_AND_DOWN);

        testPath(p(+0, +d), p(-d, +d), AccessibilityService.GESTURE_SWIPE_DOWN_AND_LEFT);
        testPath(p(+0, +d), p(+d, +d), AccessibilityService.GESTURE_SWIPE_DOWN_AND_RIGHT);
        testPath(p(+0, +d), p(+0, +0), AccessibilityService.GESTURE_SWIPE_DOWN_AND_UP);
        testPath(p(+0, +d), p(+0, (+d + d)), AccessibilityService.GESTURE_SWIPE_DOWN);
    }

    /** Convenient short alias to make a Point. */
    private static Point p(int x, int y) {
        return new Point(x, y);
    }

    /** Test recognizing path from PATH_START to PATH_START+delta. */
    private void testPath(Point delta, int gestureId) {
        ArrayList<PointF> path = new ArrayList<>();
        path.add(PATH_START);

        PointF segmentEnd = new PointF(PATH_START.x + delta.x, PATH_START.y + delta.y);
        fillPath(PATH_START, segmentEnd, path);

        testPath(path, gestureId);
    }

    /** Test recognizing path from PATH_START to PATH_START+delta1 to PATH_START+delta2. */
    private void testPath(Point delta1, Point delta2, int gestureId) {
        ArrayList<PointF> path = new ArrayList<>();
        path.add(PATH_START);

        PointF startPlusDelta1 = new PointF(PATH_START.x + delta1.x, PATH_START.y + delta1.y);
        fillPath(PATH_START, startPlusDelta1, path);

        PointF startPlusDelta2 = new PointF(PATH_START.x + delta2.x, PATH_START.y + delta2.y);
        fillPath(startPlusDelta1, startPlusDelta2, path);

        testPath(path, gestureId);
    }

    /** Fill in movement points from start to end, appending points to path. */
    private void fillPath(PointF start, PointF end, ArrayList<PointF> path) {
        // Calculate number of path steps needed.
        float deltaX = end.x - start.x;
        float deltaY = end.y - start.y;
        float distance = (float) Math.hypot(deltaX, deltaY);
        float numSteps = distance / (float) PATH_STEP_PIXELS;
        float stepX = (float) deltaX / numSteps;
        float stepY = (float) deltaY / numSteps;

        // For each path step from start (non-inclusive) to end ... add a motion point.
        for (int step = 1; step < numSteps; ++step) {
            path.add(new PointF(
                (start.x + (stepX * (float) step)),
                (start.y + (stepY * (float) step))));
        }
    }

    /** Test recognizing a path made of motion event points. */
    private void testPath(ArrayList<PointF> path, int gestureId) {
        // Clear last recognition result.
        reset(mResultListener);

        int policyFlags = 0;
        long eventDownTimeMs = 0;
        long eventTimeMs = eventDownTimeMs;

        // For each path point...
        for (int pointIndex = 0; pointIndex < path.size(); ++pointIndex) {

            // Create motion event.
            PointF point = path.get(pointIndex);
            int action = MotionEvent.ACTION_MOVE;
            if (pointIndex == 0) {
                action = MotionEvent.ACTION_DOWN;
            } else if (pointIndex == path.size() - 1) {
                action = MotionEvent.ACTION_UP;
            }
            MotionEvent event = MotionEvent.obtain(eventDownTimeMs, eventTimeMs, action,
                    point.x, point.y, 0);

            // Send event.
            mDetector.onMotionEvent(event, event, policyFlags);
            eventTimeMs += PATH_STEP_MILLISEC;
        }

        // Check that correct gesture was recognized.
        verify(mResultListener).onGestureCompleted(gestureId);
    }
}
