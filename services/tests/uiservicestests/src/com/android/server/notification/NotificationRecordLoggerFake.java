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

package com.android.server.notification;

import com.android.internal.logging.InstanceId;
import com.android.internal.logging.UiEventLogger;

import java.util.ArrayList;
import java.util.List;

/**
 * Fake implementation of NotificationRecordLogger, for testing.
 */
class NotificationRecordLoggerFake implements NotificationRecordLogger {
    static class CallRecord extends NotificationRecordPair {
        public UiEventLogger.UiEventEnum event;

        // The following fields are only relevant to maybeLogNotificationPosted() calls.
        static final int INVALID = -1;
        public int position = INVALID, buzzBeepBlink = INVALID;
        public boolean wasLogged;
        public InstanceId groupInstanceId;

        CallRecord(NotificationRecord r, NotificationRecord old, int position,
                int buzzBeepBlink, InstanceId groupId) {
            super(r, old);
            this.position = position;
            this.buzzBeepBlink = buzzBeepBlink;
            wasLogged = shouldLogReported(buzzBeepBlink);
            event = wasLogged ? NotificationReportedEvent.fromRecordPair(this) : null;
            groupInstanceId = groupId;
        }

        CallRecord(NotificationRecord r, int position, int buzzBeepBlink, InstanceId groupId) {
            super(r, null);
            this.position = position;
            this.buzzBeepBlink = buzzBeepBlink;
            wasLogged = true;
            event = NotificationReportedEvent.NOTIFICATION_ADJUSTED;
            groupInstanceId = groupId;
        }

        CallRecord(NotificationRecord r, UiEventLogger.UiEventEnum event) {
            super(r, null);
            wasLogged = true;
            this.event = event;
        }
    }
    private List<CallRecord> mCalls = new ArrayList<>();

    public int numCalls() {
        return mCalls.size();
    }

    List<CallRecord> getCalls() {
        return mCalls;
    }

    CallRecord get(int index) {
        return mCalls.get(index);
    }
    UiEventLogger.UiEventEnum event(int index) {
        return mCalls.get(index).event;
    }

    @Override
    public void maybeLogNotificationPosted(NotificationRecord r, NotificationRecord old,
            int position, int buzzBeepBlink, InstanceId groupId) {
        mCalls.add(new CallRecord(r, old, position, buzzBeepBlink, groupId));
    }

    @Override
    public void logNotificationAdjusted(NotificationRecord r, int position, int buzzBeepBlink,
            InstanceId groupId) {
        mCalls.add(new CallRecord(r, position, buzzBeepBlink, groupId));
    }

    @Override
    public void log(UiEventLogger.UiEventEnum event, NotificationRecord r) {
        mCalls.add(new CallRecord(r, event));
    }

    @Override
    public void log(UiEventLogger.UiEventEnum event) {
        mCalls.add(new CallRecord(null, event));
    }
}
