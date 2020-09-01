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

import com.android.internal.logging.InstanceId;
import com.android.internal.logging.UiEventLogger;

import java.util.LinkedList;
import java.util.List;

/**
 * Fake logger that queues up logged events for inspection.
 *
 * @hide.
 */
public class UiEventLoggerFake implements UiEventLogger {
    /**
     * Immutable data class used to record fake log events.
     */
    public static class FakeUiEvent {
        public final int eventId;
        public final int uid;
        public final String packageName;
        public final InstanceId instanceId;  // Used only for WithInstanceId variants
        public final int position;  // Used only for Position variants

        FakeUiEvent(int eventId, int uid, String packageName) {
            this.eventId = eventId;
            this.uid = uid;
            this.packageName = packageName;
            this.instanceId = null;
            this.position = 0;
        }

        FakeUiEvent(int eventId, int uid, String packageName, InstanceId instanceId) {
            this.eventId = eventId;
            this.uid = uid;
            this.packageName = packageName;
            this.instanceId = instanceId;
            this.position = 0;
        }

        FakeUiEvent(int eventId, int uid, String packageName, InstanceId instanceId, int position) {
            this.eventId = eventId;
            this.uid = uid;
            this.packageName = packageName;
            this.instanceId = instanceId;
            this.position = position;
        }
    }

    private List<FakeUiEvent> mLogs = new LinkedList<>();

    /** Returns list of all logging events recorded. */
    public List<FakeUiEvent> getLogs() {
        return mLogs;
    }
    /** Returns number of logging events recorded. */
    public int numLogs() {
        return mLogs.size();
    }
    /** Returns a particular logging event. */
    public FakeUiEvent get(int index) {
        return mLogs.get(index);
    }
    /** Returns event id (as integer) of a particular logging event. */
    public int eventId(int index) {
        return mLogs.get(index).eventId;
    }

    @Override
    public void log(UiEventEnum event) {
        log(event, 0, null);
    }

    @Override
    public void log(UiEventEnum event, int uid, String packageName) {
        final int eventId = event.getId();
        if (eventId > 0) {
            mLogs.add(new FakeUiEvent(eventId, uid, packageName));
        }
    }

    @Override
    public void logWithInstanceId(UiEventEnum event, int uid, String packageName,
            InstanceId instance) {
        final int eventId = event.getId();
        if (eventId > 0) {
            mLogs.add(new FakeUiEvent(eventId, uid, packageName, instance));
        }
    }

    @Override
    public void logWithPosition(UiEventEnum event, int uid, String packageName, int position) {
        final int eventId = event.getId();
        if (eventId > 0) {
            mLogs.add(new FakeUiEvent(eventId, uid, packageName, null, position));
        }
    }

    @Override
    public void logWithInstanceIdAndPosition(UiEventEnum event, int uid, String packageName,
            InstanceId instance, int position) {
        final int eventId = event.getId();
        if (eventId > 0) {
            mLogs.add(new FakeUiEvent(eventId, uid, packageName, instance, position));
        }
    }
}
