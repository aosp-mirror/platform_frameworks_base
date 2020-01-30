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

import java.util.ArrayList;
import java.util.List;

/**
 * Fake implementation of NotificationRecordLogger, for testing.
 */
class NotificationRecordLoggerFake implements NotificationRecordLogger {
    static class CallRecord extends NotificationRecordPair {
        public int position, buzzBeepBlink;
        CallRecord(NotificationRecord r, NotificationRecord old, int position,
                int buzzBeepBlink) {
            super(r, old);
            this.position = position;
            this.buzzBeepBlink = buzzBeepBlink;
        }
        boolean shouldLog() {
            return shouldLog(buzzBeepBlink);
        }
    }
    private List<CallRecord> mCalls = new ArrayList<CallRecord>();

    List<CallRecord> getCalls() {
        return mCalls;
    }

    CallRecord get(int index) {
        return mCalls.get(index);
    }

    @Override
    public void logNotificationReported(NotificationRecord r, NotificationRecord old,
            int position, int buzzBeepBlink) {
        mCalls.add(new CallRecord(r, old, position, buzzBeepBlink));
    }
}
