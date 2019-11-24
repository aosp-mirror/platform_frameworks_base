/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.internal.logging.testing;

import com.android.internal.logging.UiEventLogger;

import java.util.LinkedList;
import java.util.Queue;

/**
 * Fake logger that queues up logged events for inspection.
 *
 * @hide.
 */
public class UiEventLoggerFake implements UiEventLogger {
    /**
     * Immutable data class used to record fake log events.
     */
    public class FakeUiEvent {
        public final int eventId;
        public final int uid;
        public final String packageName;

        public FakeUiEvent(int eventId, int uid, String packageName) {
            this.eventId = eventId;
            this.uid = uid;
            this.packageName = packageName;
        }
    }

    private Queue<FakeUiEvent> mLogs = new LinkedList<FakeUiEvent>();

    @Override
    public void log(UiEventEnum event) {
        final int eventId = event.getId();
        if (eventId > 0) {
            mLogs.offer(new FakeUiEvent(eventId, 0, null));
        }
    }

    public Queue<FakeUiEvent> getLogs() {
        return mLogs;
    }
}
