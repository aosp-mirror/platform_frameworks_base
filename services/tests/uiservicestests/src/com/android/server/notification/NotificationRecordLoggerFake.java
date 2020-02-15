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
        public boolean shouldLogReported;

        CallRecord(NotificationRecord r, NotificationRecord old, int position,
                int buzzBeepBlink) {
            super(r, old);
            this.position = position;
            this.buzzBeepBlink = buzzBeepBlink;
            shouldLogReported = shouldLogReported(buzzBeepBlink);
            event = shouldLogReported ? NotificationReportedEvent.fromRecordPair(this) : null;
        }

        CallRecord(NotificationRecord r, UiEventLogger.UiEventEnum event) {
            super(r, null);
            shouldLogReported = false;
            this.event = event;
        }
    }
    private List<CallRecord> mCalls = new ArrayList<>();

    List<CallRecord> getCalls() {
        return mCalls;
    }

    CallRecord get(int index) {
        return mCalls.get(index);
    }

    @Override
    public void maybeLogNotificationPosted(NotificationRecord r, NotificationRecord old,
            int position, int buzzBeepBlink) {
        mCalls.add(new CallRecord(r, old, position, buzzBeepBlink));
    }

    @Override
    public void logNotificationCancelled(NotificationRecord r, int reason, int dismissalSurface) {
        mCalls.add(new CallRecord(r,
                NotificationCancelledEvent.fromCancelReason(reason, dismissalSurface)));
    }

    @Override
    public void logNotificationVisibility(NotificationRecord r, boolean visible) {
        mCalls.add(new CallRecord(r, NotificationEvent.fromVisibility(visible)));
    }
}
