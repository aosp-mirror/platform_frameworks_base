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

import android.annotation.Nullable;
import android.app.NotificationChannel;
import android.app.NotificationChannelGroup;

import com.google.common.base.MoreObjects;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public class NotificationChannelLoggerFake implements NotificationChannelLogger {
    static class CallRecord {
        public final NotificationChannelEvent event;
        @Nullable public final String channelId;

        CallRecord(NotificationChannelEvent event, @Nullable String channelId) {
            this.event = event;
            this.channelId = channelId;
        }

        @Override
        public String toString() {
            return MoreObjects.toStringHelper(this)
                    .add("event", event)
                    .add(channelId, channelId)
                    .toString();
        }

        @Override
        public boolean equals(Object obj) {
            return (obj instanceof CallRecord other)
                    && Objects.equals(this.event, other.event)
                    && Objects.equals(this.channelId, other.channelId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(event, channelId);
        }
    }

    private List<CallRecord> mCalls = new ArrayList<>();

    List<CallRecord> getCalls() {
        return mCalls;
    }

    CallRecord get(int index) {
        return mCalls.get(index);
    }

    void clear() {
        mCalls.clear();
    }

    @Override
    public void logNotificationChannel(NotificationChannelEvent event, NotificationChannel channel,
            int uid, String pkg, int oldImportance, int newImportance) {
        mCalls.add(new CallRecord(event, channel.getId()));
    }

    @Override
    public void logNotificationChannelGroup(NotificationChannelEvent event,
            NotificationChannelGroup channelGroup, int uid, String pkg, boolean wasBlocked) {
        mCalls.add(new CallRecord(event, channelGroup.getId()));
    }

    @Override
    public void logAppEvent(NotificationChannelEvent event, int uid, String pkg) {
        mCalls.add(new CallRecord(event, null));
    }
}
