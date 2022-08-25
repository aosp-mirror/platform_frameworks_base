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

package com.android.systemui.statusbar;

import com.android.internal.logging.UiEvent;
import com.android.internal.logging.UiEventLogger;

/**
 * Events for changes in the {@link StatusBarState}.
 */
public enum StatusBarStateEvent implements UiEventLogger.UiEventEnum {

    @UiEvent(doc = "StatusBarState changed to unknown state")
    STATUS_BAR_STATE_UNKNOWN(428),

    @UiEvent(doc = "StatusBarState changed to SHADE state")
    STATUS_BAR_STATE_SHADE(429),

    @UiEvent(doc = "StatusBarState changed to KEYGUARD state")
    STATUS_BAR_STATE_KEYGUARD(430),

    @UiEvent(doc = "StatusBarState changed to SHADE_LOCKED state")
    STATUS_BAR_STATE_SHADE_LOCKED(431);

    private int mId;
    StatusBarStateEvent(int id) {
        mId = id;
    }

    @Override
    public int getId() {
        return mId;
    }

    /**
     * Return the event associated with the state.
     */
    public static StatusBarStateEvent fromState(int state) {
        switch(state) {
            case StatusBarState.SHADE:
                return STATUS_BAR_STATE_SHADE;
            case StatusBarState.KEYGUARD:
                return STATUS_BAR_STATE_KEYGUARD;
            case StatusBarState.SHADE_LOCKED:
                return STATUS_BAR_STATE_SHADE_LOCKED;
            default:
                return STATUS_BAR_STATE_UNKNOWN;
        }
    }
}
