/*
 ** Copyright 2012, The Android Open Source Project
 **
 ** Licensed under the Apache License, Version 2.0 (the "License");
 ** you may not use this file except in compliance with the License.
 ** You may obtain a copy of the License at
 **
 **     http://www.apache.org/licenses/LICENSE-2.0
 **
 ** Unless required by applicable law or agreed to in writing, software
 ** distributed under the License is distributed on an "AS IS" BASIS,
 ** WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 ** See the License for the specific language governing permissions and
 ** limitations under the License.
 */

package com.android.server.accessibility;

import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.accessibility.AccessibilityEvent;

/**
 * Interface for classes that can handle and potentially transform a stream of
 * motion and accessibility events. Instances implementing this interface are
 * ordered in a sequence to implement a transformation chain. An instance may
 * consume, modify, and generate events. It is responsible to deliver the
 * output events to the next transformation in the sequence set via
 * {@link #setNext(EventStreamTransformation)}.
 *
 * Note that since instances implementing this interface are transformations
 * of the event stream, an instance should work against the event stream
 * potentially modified by previous ones. Hence, the order of transformations
 * is important.
 *
 * It is a responsibility of each handler that decides to react to an event
 * sequence and prevent any subsequent ones from performing an action to send
 * the appropriate cancel event given it has delegated a part of the events
 * that belong to the current gesture. This will ensure that subsequent
 * transformations will not be left in an inconsistent state and the applications
 * see a consistent event stream.
 *
 * For example, to cancel a {@link KeyEvent} the handler has to emit an event
 * with action {@link KeyEvent#ACTION_UP} with the additional flag
 * {@link KeyEvent#FLAG_CANCELED}. To cancel a {@link MotionEvent} the handler
 * has to send an event with action {@link MotionEvent#ACTION_CANCEL}.
 *
 * It is a responsibility of each handler that received a cancel event to clear its
 * internal state and to propagate the event to the next one to enable subsequent
 * transformations to clear their internal state.
 *
 * It is a responsibility for each transformation to start handling events only
 * after an event that designates the start of a well-formed event sequence.
 * For example, if it received a down motion event followed by a cancel motion
 * event, it should not handle subsequent move and up events until it gets a down.
 */
public interface EventStreamTransformation {

    /**
     * Receives a motion event. Passed are the event transformed by previous
     * transformations and the raw event to which no transformations have
     * been applied.
     *
     * @param event The transformed motion event.
     * @param rawEvent The raw motion event.
     * @param policyFlags Policy flags for the event.
     */
    default void onMotionEvent(MotionEvent event, MotionEvent rawEvent, int policyFlags) {
        EventStreamTransformation next = getNext();
        if (next != null) {
            next.onMotionEvent(event, rawEvent, policyFlags);
        }
    }

    /**
     * Receives a key event.
     *
     * @param event The key event.
     * @param policyFlags Policy flags for the event.
     */
    default void onKeyEvent(KeyEvent event, int policyFlags) {
        EventStreamTransformation next = getNext();
        if (next != null) {
            next.onKeyEvent(event, policyFlags);
        }
    }

    /**
     * Receives an accessibility event.
     *
     * @param event The accessibility event.
     */
    default void onAccessibilityEvent(AccessibilityEvent event) {
        EventStreamTransformation next = getNext();
        if (next != null) {
            next.onAccessibilityEvent(event);
        }
    };

    /**
     * Sets the next transformation.
     *
     * @param next The next transformation.
     */
    public void setNext(EventStreamTransformation next);

    /**
     * Gets the next transformation.
     *
     * @return The next transformation.
     */
    public EventStreamTransformation getNext();

    /**
     * Clears internal state associated with events from specific input source.
     *
     * @param inputSource The input source class for which transformation state should be cleared.
     */
    default void clearEvents(int inputSource) {
        EventStreamTransformation next = getNext();
        if (next != null) {
            next.clearEvents(inputSource);
        }
    }

    /**
     * Destroys this transformation.
     */
    default void onDestroy() {}
}
