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


package android.view;

import static android.view.MotionEvent.ACTION_DOWN;
import static android.view.MotionEvent.ACTION_MOVE;
import static android.view.MotionEvent.ACTION_UP;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import android.content.Context;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.platform.test.annotations.Presubmit;

import androidx.test.filters.SmallTest;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Before;
import org.junit.Test;


import java.util.ArrayList;
import java.util.List;

/**
 * Tests for {@link LetterboxScrollProcessor}
 *
 * Build/Install/Run:
 *  atest FrameworksCoreTests:LetterboxScrollProcessorTest
 */
@SmallTest
@Presubmit
public class LetterboxScrollProcessorTest {

    private LetterboxScrollProcessor mLetterboxScrollProcessor;
    private Context mContext;

    // Constant delta used when comparing coordinates (floats)
    private static final float EPSILON = 0.1f;

    @Before
    public void setUp() {
        mContext = InstrumentationRegistry.getInstrumentation().getTargetContext();

        // Set app bounds as if it was letterboxed.
        mContext.getResources().getConfiguration().windowConfiguration
                .setBounds(new Rect(200, 200, 600, 1000));

        Handler handler = new Handler(Looper.getMainLooper());

        // Recreate to reset LetterboxScrollProcessor state.
        mLetterboxScrollProcessor = new LetterboxScrollProcessor(mContext, handler);
    }

    @Test
    public void testGestureInBoundsHasNoAdjustments() {
        // Tap-like gesture in bounds (non-scroll).
        List<MotionEvent> tapGestureEvents = createTapGestureEvents(0f, 0f);

        // Get processed events from Letterbox Scroll Processor.
        List<MotionEvent> processedEvents = processMotionEvents(tapGestureEvents);

        // Ensure no changes are made to events after processing - event locations should not be
        // adjusted because the gesture started in the app's bounds (for all gestures).
        assertEventLocationsAreNotAdjusted(tapGestureEvents, processedEvents);
        // Ensure all of these events should be finished (expect no generated events).
        assertMotionEventsShouldBeFinished(processedEvents);
    }

    @Test
    public void testGestureOutsideBoundsIsIgnored() {
        // Tap-like gesture outside bounds (non-scroll).
        List<MotionEvent> tapGestureEvents = createTapGestureEvents(-100f, -100f);

        // Get processed events from Letterbox Scroll Processor.
        List<MotionEvent> processedEvents = processMotionEvents(tapGestureEvents);

        // All events should be ignored since it was a non-scroll gesture and out of bounds.
        assertEquals(0, processedEvents.size());
    }

    @Test
    public void testScrollGestureInBoundsHasNoAdjustments() {
        // Scroll gesture in bounds (non-scroll).
        List<MotionEvent> scrollGestureEvents = createScrollGestureEvents(0f, 0f);

        // Get processed events from Letterbox Scroll Processor.
        List<MotionEvent> processedEvents = processMotionEvents(scrollGestureEvents);

        // Ensure no changes are made to events after processing - event locations should not be
        // adjusted because the gesture started in the app's bounds (for all gestures).
        assertEventLocationsAreNotAdjusted(scrollGestureEvents, processedEvents);
        // Ensure all of these events should be finished (expect no generated events).
        assertMotionEventsShouldBeFinished(processedEvents);
    }

    @Test
    public void testScrollGestureInBoundsThenLeavesBoundsHasNoAdjustments() {
        // Scroll gesture in bounds (non-scroll) that moves out of bounds.
        List<MotionEvent> scrollGestureEvents = createScrollGestureEvents(390f, 790f);

        // Get processed events from Letterbox Scroll Processor.
        List<MotionEvent> processedEvents = processMotionEvents(scrollGestureEvents);

        // Ensure no changes are made to events after processing - event locations should not be
        // adjusted because the gesture started in the app's bounds (for all gestures), even if it
        // leaves the apps bounds.
        assertEventLocationsAreNotAdjusted(scrollGestureEvents, processedEvents);
        // Ensure all of these events should be finished (expect no generated events).
        assertMotionEventsShouldBeFinished(processedEvents);
    }

    @Test
    public void testScrollGestureOutsideBoundsIsStartedInBounds() {
        // Scroll gesture outside bounds.
        List<MotionEvent> scrollGestureEvents = createScrollGestureEvents(-100f, 0f);

        // Get processed events from Letterbox Scroll Processor.
        List<MotionEvent> processedEvents = processMotionEvents(scrollGestureEvents);

        // When a scroll occurs outside bounds: once detected as a scroll, the ACTION_DOWN is
        // expected to be received again but with an offset so it is over the app's bounds.

        // Ensure offset ACTION_DOWN is first event received.
        MotionEvent firstProcessedEvent = processedEvents.getFirst();
        assertEquals(firstProcessedEvent.getAction(), ACTION_DOWN);
        assertEquals(firstProcessedEvent.getX(), 0, EPSILON);
        assertEquals(firstProcessedEvent.getY(), 0, EPSILON);
        // Ensure this event is not finished (because it was generated by LetterboxScrollProcessor).
        assertNull(mLetterboxScrollProcessor.processMotionEventBeforeFinish(firstProcessedEvent));
    }

    @Test
    public void testScrollGestureOutsideBoundsIsMovedInBounds() {
        // Scroll gesture outside bounds.
        List<MotionEvent> scrollGestureEvents = createScrollGestureEvents(-100f, 0f);

        // Get processed events from Letterbox Scroll Processor.
        List<MotionEvent> processedEvents = processMotionEvents(scrollGestureEvents);

        // When a scroll occurs outside bounds: once detected as a scroll, an offset ACTION_DOWN is
        // placed and then the rest of the gesture is offset also. Some ACTION_MOVE events may be
        // ignored until the gesture is 'detected as a scroll'.
        // For this test, we expect the first ACTION_MOVE event to be ignored:
        scrollGestureEvents.remove(1);

        // Ensure all processed events (that are not ignored) are offset over the app.
        assertXCoordinatesAdjustedToZero(scrollGestureEvents, processedEvents);
        // Except the first generated ACTION_DOWN event, ensure the following events should be
        // finished (these events should not be generated).
        assertMotionEventsShouldBeFinished(processedEvents.subList(1, processedEvents.size()));
    }

    private List<MotionEvent> processMotionEvents(List<MotionEvent> motionEvents) {
        List<MotionEvent> processedEvents = new ArrayList<>();
        for (MotionEvent motionEvent : motionEvents) {
            MotionEvent clonedEvent = MotionEvent.obtain(motionEvent);
            List<MotionEvent> letterboxScrollCompatEvents =
                    mLetterboxScrollProcessor.processMotionEvent(clonedEvent);
            if (letterboxScrollCompatEvents == null) {
                // Use original event if null returned (no adjustments made).
                processedEvents.add(clonedEvent);
            } else {
                // Otherwise, use adjusted events.
                processedEvents.addAll(letterboxScrollCompatEvents);
            }
        }
        return processedEvents;
    }

    private List<MotionEvent> createTapGestureEvents(float startX, float startY) {
        // Events for tap-like gesture (non-scroll)
        List<MotionEvent> motionEvents = new ArrayList<>();
        motionEvents.add(createBasicMotionEvent(0, ACTION_DOWN, startX, startY));
        motionEvents.add(createBasicMotionEvent(10, ACTION_UP, startX , startY));
        return motionEvents;
    }

    private List<MotionEvent> createScrollGestureEvents(float startX, float startY) {
        float touchSlop = (float) ViewConfiguration.get(mContext).getScaledTouchSlop();

        // Events for scroll gesture (starts at (startX, startY) then moves down-right
        List<MotionEvent> motionEvents = new ArrayList<>();
        motionEvents.add(createBasicMotionEvent(0, ACTION_DOWN, startX, startY));
        motionEvents.add(createBasicMotionEvent(10, ACTION_MOVE,
                startX + touchSlop / 2, startY + touchSlop / 2));
        // Below event is first event in the scroll gesture where distance > touchSlop
        motionEvents.add(createBasicMotionEvent(20, ACTION_MOVE,
                startX + touchSlop * 2, startY + touchSlop * 2));
        motionEvents.add(createBasicMotionEvent(30, ACTION_MOVE,
                startX + touchSlop * 3, startY + touchSlop * 3));
        motionEvents.add(createBasicMotionEvent(40, ACTION_UP,
                startX + touchSlop * 3, startY + touchSlop * 3));
        return motionEvents;
    }

    private MotionEvent createBasicMotionEvent(int downTime, int action, float x, float y) {
        return MotionEvent.obtain(0, downTime, action, x, y, 0);
    }

    private void assertEventLocationsAreNotAdjusted(
            List<MotionEvent> originalEvents,
            List<MotionEvent> processedEvents) {
        assertEquals("MotionEvent arrays are not the same size",
                originalEvents.size(), processedEvents.size());

        for (int i = 0; i < originalEvents.size(); i++) {
            assertEquals("X coordinates was unexpectedly adjusted at index " + i,
                    originalEvents.get(i).getX(), processedEvents.get(i).getX(), EPSILON);
            assertEquals("Y coordinates was unexpectedly adjusted at index " + i,
                    originalEvents.get(i).getY(), processedEvents.get(i).getY(), EPSILON);
        }
    }

    private void assertXCoordinatesAdjustedToZero(
            List<MotionEvent> originalEvents,
            List<MotionEvent> processedEvents) {
        assertEquals("MotionEvent arrays are not the same size",
                originalEvents.size(), processedEvents.size());

        for (int i = 0; i < originalEvents.size(); i++) {
            assertEquals("X coordinate was not adjusted to 0 at index " + i,
                    0, processedEvents.get(i).getX(), EPSILON);
            assertEquals("Y coordinate was unexpectedly adjusted at index " + i,
                    originalEvents.get(i).getY(), processedEvents.get(i).getY(), EPSILON);
        }
    }

    private void assertMotionEventsShouldBeFinished(List<MotionEvent> processedEvents) {
        for (MotionEvent processedEvent : processedEvents) {
            assertNotNull(mLetterboxScrollProcessor.processMotionEventBeforeFinish(processedEvent));
        }
    }
}
