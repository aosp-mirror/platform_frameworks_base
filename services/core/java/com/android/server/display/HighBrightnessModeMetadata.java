/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.server.display;

import java.util.ArrayDeque;


/**
 * Represents High Brightness Mode metadata associated
 * with a specific internal physical display.
 * Required for separately storing data like time information,
 * and related events when display was in HBM mode per
 * physical internal display.
 */
class HighBrightnessModeMetadata {
    /**
     * Queue of previous HBM-events ordered from most recent to least recent.
     * Meant to store only the events that fall into the most recent
     * {@link HighBrightnessModeData#timeWindowMillis mHbmData.timeWindowMillis}.
     */
    private final ArrayDeque<HbmEvent> mEvents = new ArrayDeque<>();

    /**
     * If HBM is currently running, this is the start time for the current HBM session.
     */
    private long mRunningStartTimeMillis = -1;

    public long getRunningStartTimeMillis() {
        return mRunningStartTimeMillis;
    }

    public void setRunningStartTimeMillis(long setTime) {
        mRunningStartTimeMillis = setTime;
    }

    public ArrayDeque<HbmEvent> getHbmEventQueue() {
        return mEvents;
    }

    public void addHbmEvent(HbmEvent hbmEvent) {
        mEvents.addFirst(hbmEvent);
    }
}

