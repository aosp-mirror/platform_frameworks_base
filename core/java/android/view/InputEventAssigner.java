/*
 * Copyright (C) 2021 The Android Open Source Project
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

import static android.os.IInputConstants.INVALID_INPUT_EVENT_ID;
import static android.view.InputDevice.SOURCE_TOUCHSCREEN;

/**
 * Process input events and assign input event id to a specific frame.
 *
 * The assigned input event id is determined by where the current gesture is relative to the vsync.
 * In the middle of the gesture (we already processed some input events, and already received at
 * least 1 vsync), the latest InputEvent is assigned to the next frame.
 * If a gesture just started, then the ACTION_DOWN event will be assigned to the next frame.
 *
 * Consider the following sequence:
 * DOWN -> VSYNC 1 -> MOVE 1 -> MOVE 2 -> VSYNC 2.
 *
 * For VSYNC 1, we will assign the "DOWN" input event.
 * For VSYNC 2, we will assign the "MOVE 2" input event.
 *
 * Consider another sequence:
 * DOWN -> MOVE 1 -> MOVE 2 -> VSYNC 1 -> MOVE 3 -> VSYNC 2.
 *
 * For VSYNC 1, we will still assign the "DOWN" input event. That means that "MOVE 1" and "MOVE 2"
 * events are not attributed to any frame.
 * For VSYNC 2, the "MOVE 3" input event will be assigned.
 *
 * @hide
 */
public class InputEventAssigner {
    private static final String TAG = "InputEventAssigner";
    private boolean mHasUnprocessedDown = false;
    private int mEventId = INVALID_INPUT_EVENT_ID;

    /**
     * Notify InputEventAssigner that the Choreographer callback has been processed. This will reset
     * the 'down' state to assign the latest input event to the current frame.
     */
    public void onChoreographerCallback() {
        // Mark completion of this frame. Use newest input event from now on.
        mHasUnprocessedDown = false;
    }

    /**
     * Process the provided input event to determine which event id to assign to the current frame.
     * @param event the input event currently being processed
     * @return the id of the input event to use for the current frame
     */
    public int processEvent(InputEvent event) {
        if (event instanceof KeyEvent) {
            // We will not do any special handling for key events
            return event.getId();
        }

        if (event instanceof MotionEvent) {
            MotionEvent motionEvent = (MotionEvent) event;
            final int action = motionEvent.getActionMasked();

            if (action == MotionEvent.ACTION_CANCEL || action == MotionEvent.ACTION_UP) {
                mHasUnprocessedDown = false;
            }
            if (motionEvent.isFromSource(SOURCE_TOUCHSCREEN) && action == MotionEvent.ACTION_DOWN) {
                mHasUnprocessedDown = true;
                mEventId = event.getId();
                // This will remain 'true' even if we receive a MOVE event, as long as choreographer
                // hasn't invoked the 'CALLBACK_INPUT' callback.
            }
            // Don't update the event id if we haven't processed DOWN yet.
            if (!mHasUnprocessedDown) {
                mEventId = event.getId();
            }
            return mEventId;
        }

        throw new IllegalArgumentException("Received unexpected " + event);
    }
}
