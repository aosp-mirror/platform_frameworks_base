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

package com.android.systemui.bubbles;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.logging.UiEvent;
import com.android.internal.logging.UiEventLogger;

/**
 * Interface for handling bubble-specific logging.
 */
public interface BubbleLogger extends UiEventLogger {

    /**
     * Bubble UI event.
     */
    @VisibleForTesting
    enum Event implements UiEventLogger.UiEventEnum {

        @UiEvent(doc = "User dismissed the bubble via gesture, add bubble to overflow.")
        BUBBLE_OVERFLOW_ADD_USER_GESTURE(483),

        @UiEvent(doc = "No more space in top row, add bubble to overflow.")
        BUBBLE_OVERFLOW_ADD_AGED(484),

        @UiEvent(doc = "No more space in overflow, remove bubble from overflow")
        BUBBLE_OVERFLOW_REMOVE_MAX_REACHED(485),

        @UiEvent(doc = "Notification canceled, remove bubble from overflow.")
        BUBBLE_OVERFLOW_REMOVE_CANCEL(486),

        @UiEvent(doc = "Notification group canceled, remove bubble for child notif from overflow.")
        BUBBLE_OVERFLOW_REMOVE_GROUP_CANCEL(487),

        @UiEvent(doc = "Notification no longer bubble, remove bubble from overflow.")
        BUBBLE_OVERFLOW_REMOVE_NO_LONGER_BUBBLE(488),

        @UiEvent(doc = "User tapped overflow bubble. Promote bubble back to top row.")
        BUBBLE_OVERFLOW_REMOVE_BACK_TO_STACK(489),

        @UiEvent(doc = "User blocked notification from bubbling, remove bubble from overflow.")
        BUBBLE_OVERFLOW_REMOVE_BLOCKED(490);

        private final int mId;

        Event(int id) {
            mId = id;
        }

        @Override
        public int getId() {
            return mId;
        }
    }

    /**
     * @param b Bubble involved in this UI event
     * @param e UI event
     */
    void log(Bubble b, UiEventEnum e);

    /**
     *
     * @param b Bubble removed from overflow
     * @param r Reason that bubble was removed from overflow
     */
    void logOverflowRemove(Bubble b, @BubbleController.DismissReason int r);

    /**
     *
     * @param b Bubble added to overflow
     * @param r Reason that bubble was added to overflow
     */
    void logOverflowAdd(Bubble b, @BubbleController.DismissReason int r);
}
