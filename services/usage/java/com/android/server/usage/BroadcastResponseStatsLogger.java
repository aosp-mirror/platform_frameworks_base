/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.server.usage;

import static android.app.ActivityManager.procStateToString;

import static com.android.server.usage.BroadcastResponseStatsTracker.NOTIFICATION_EVENT_TYPE_CANCELLED;
import static com.android.server.usage.BroadcastResponseStatsTracker.NOTIFICATION_EVENT_TYPE_POSTED;
import static com.android.server.usage.BroadcastResponseStatsTracker.NOTIFICATION_EVENT_TYPE_UPDATED;
import static com.android.server.usage.BroadcastResponseStatsTracker.TAG;
import static com.android.server.usage.UsageStatsService.DEBUG_RESPONSE_STATS;

import android.annotation.ElapsedRealtimeLong;
import android.annotation.NonNull;
import android.annotation.UserIdInt;
import android.app.ActivityManager;
import android.app.ActivityManager.ProcessState;
import android.os.UserHandle;
import android.text.TextUtils;
import android.util.Slog;
import android.util.TimeUtils;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.util.IndentingPrintWriter;
import com.android.internal.util.RingBuffer;
import com.android.server.usage.BroadcastResponseStatsTracker.NotificationEventType;

public class BroadcastResponseStatsLogger {

    private static final int MAX_LOG_SIZE =
            ActivityManager.isLowRamDeviceStatic() ? 20 : 50;

    private final Object mLock = new Object();

    @GuardedBy("mLock")
    private final LogBuffer mBroadcastEventsBuffer = new LogBuffer(
            BroadcastEvent.class, MAX_LOG_SIZE);
    @GuardedBy("mLock")
    private final LogBuffer mNotificationEventsBuffer = new LogBuffer(
            NotificationEvent.class, MAX_LOG_SIZE);

    void logBroadcastDispatchEvent(int sourceUid, @NonNull String targetPackage,
            UserHandle targetUser, long idForResponseEvent,
            @ElapsedRealtimeLong long timeStampMs, @ProcessState int targetUidProcessState) {
        synchronized (mLock) {
            if (DEBUG_RESPONSE_STATS) {
                Slog.d(TAG, getBroadcastDispatchEventLog(sourceUid, targetPackage,
                        targetUser.getIdentifier(), idForResponseEvent, timeStampMs,
                        targetUidProcessState));
            }
            mBroadcastEventsBuffer.logBroadcastDispatchEvent(sourceUid, targetPackage,
                    targetUser, idForResponseEvent, timeStampMs, targetUidProcessState);
        }
    }

    void logNotificationEvent(@NotificationEventType int event,
            @NonNull String packageName, UserHandle user, @ElapsedRealtimeLong long timestampMs) {
        synchronized (mLock) {
            if (DEBUG_RESPONSE_STATS) {
                Slog.d(TAG, getNotificationEventLog(event, packageName, user.getIdentifier(),
                        timestampMs));
            }
            mNotificationEventsBuffer.logNotificationEvent(event, packageName, user, timestampMs);
        }
    }

    void dumpLogs(IndentingPrintWriter ipw) {
        synchronized (mLock) {
            ipw.println("Broadcast events (most recent first):");
            ipw.increaseIndent();
            mBroadcastEventsBuffer.reverseDump(ipw);
            ipw.decreaseIndent();

            ipw.println();
            ipw.println("Notification events (most recent first):");
            ipw.increaseIndent();
            mNotificationEventsBuffer.reverseDump(ipw);
            ipw.decreaseIndent();
        }
    }

    private static final class LogBuffer<T extends Data> extends RingBuffer<T> {

        LogBuffer(Class<T> classType, int capacity) {
            super(classType, capacity);
        }

        void logBroadcastDispatchEvent(int sourceUid, @NonNull String targetPackage,
                UserHandle targetUser, long idForResponseEvent,
                @ElapsedRealtimeLong long timeStampMs, @ProcessState int targetUidProcessState) {
            final Data data = getNextSlot();
            if (data == null) return;

            data.reset();
            final BroadcastEvent event = (BroadcastEvent) data;
            event.sourceUid = sourceUid;
            event.targetUserId = targetUser.getIdentifier();
            event.targetUidProcessState = targetUidProcessState;
            event.targetPackage = targetPackage;
            event.idForResponseEvent = idForResponseEvent;
            event.timestampMs = timeStampMs;
        }

        void logNotificationEvent(@NotificationEventType int type,
                @NonNull String packageName, UserHandle user,
                @ElapsedRealtimeLong long timestampMs) {
            final Data data = getNextSlot();
            if (data == null) return;

            data.reset();
            final NotificationEvent event = (NotificationEvent) data;
            event.type = type;
            event.packageName = packageName;
            event.userId = user.getIdentifier();
            event.timestampMs = timestampMs;
        }

        public void reverseDump(IndentingPrintWriter pw) {
            final Data[] allData = toArray();
            for (int i = allData.length - 1; i >= 0; --i) {
                if (allData[i] == null) {
                    continue;
                }
                pw.println(getContent(allData[i]));
            }
        }

        @NonNull
        public String getContent(Data data) {
            return data.toString();
        }
    }

    @NonNull
    private static String getBroadcastDispatchEventLog(int sourceUid, @NonNull String targetPackage,
            @UserIdInt int targetUserId, long idForResponseEvent,
            @ElapsedRealtimeLong long timestampMs, @ProcessState int targetUidProcState) {
        return TextUtils.formatSimple(
                "broadcast:%s; srcUid=%d, tgtPkg=%s, tgtUsr=%d, id=%d, state=%s",
                TimeUtils.formatDuration(timestampMs), sourceUid, targetPackage, targetUserId,
                idForResponseEvent, procStateToString(targetUidProcState));
    }

    @NonNull
    private static String getNotificationEventLog(@NotificationEventType int event,
            @NonNull String packageName, @UserIdInt int userId,
            @ElapsedRealtimeLong long timestampMs) {
        return TextUtils.formatSimple("notification:%s; event=<%s>, pkg=%s, usr=%d",
                TimeUtils.formatDuration(timestampMs), notificationEventToString(event),
                packageName, userId);
    }

    @NonNull
    private static String notificationEventToString(@NotificationEventType int event) {
        switch (event) {
            case NOTIFICATION_EVENT_TYPE_POSTED:
                return "posted";
            case NOTIFICATION_EVENT_TYPE_UPDATED:
                return "updated";
            case NOTIFICATION_EVENT_TYPE_CANCELLED:
                return "cancelled";
            default:
                return String.valueOf(event);
        }
    }

    public static final class BroadcastEvent implements Data {
        public int sourceUid;
        public int targetUserId;
        public int targetUidProcessState;
        public String targetPackage;
        public long idForResponseEvent;
        public long timestampMs;

        @Override
        public void reset() {
            targetPackage = null;
        }

        @Override
        public String toString() {
            return getBroadcastDispatchEventLog(sourceUid, targetPackage, targetUserId,
                    idForResponseEvent, timestampMs, targetUidProcessState);
        }
    }

    public static final class NotificationEvent implements Data {
        public int type;
        public String packageName;
        public int userId;
        public long timestampMs;

        @Override
        public void reset() {
            packageName = null;
        }

        @Override
        public String toString() {
            return getNotificationEventLog(type, packageName, userId, timestampMs);
        }
    }

    public interface Data {
        void reset();
    }
}
