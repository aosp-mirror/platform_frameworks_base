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
package com.android.server.contentcapture;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.ComponentName;
import android.content.ContentCaptureOptions;
import android.service.contentcapture.FlushMetrics;
import android.util.StatsLog;

import java.util.List;

/** @hide */
public final class ContentCaptureMetricsLogger {
    /**
     * Class only contains static utility functions, and should not be instantiated
     */
    private ContentCaptureMetricsLogger() {
    }

    /** @hide */
    public static void writeServiceEvent(int eventType, @NonNull String serviceName,
            @Nullable String targetPackage) {
        StatsLog.write(StatsLog.CONTENT_CAPTURE_SERVICE_EVENTS, eventType, serviceName,
                targetPackage);
    }

    /** @hide */
    public static void writeServiceEvent(int eventType, @NonNull ComponentName service,
            @Nullable ComponentName target) {
        writeServiceEvent(eventType, ComponentName.flattenToShortString(service),
                ComponentName.flattenToShortString(target));
    }

    /** @hide */
    public static void writeServiceEvent(int eventType, @NonNull ComponentName service,
            @Nullable String targetPackage) {
        writeServiceEvent(eventType, ComponentName.flattenToShortString(service), targetPackage);
    }

    /** @hide */
    public static void writeServiceEvent(int eventType, @NonNull ComponentName service) {
        writeServiceEvent(eventType, ComponentName.flattenToShortString(service), null);
    }

    /** @hide */
    public static void writeSetWhitelistEvent(@Nullable ComponentName service,
            @Nullable List<String> packages, @Nullable List<ComponentName> activities) {
        final String serviceName = ComponentName.flattenToShortString(service);
        StringBuilder stringBuilder = new StringBuilder();
        if (packages != null && packages.size() > 0) {
            final int size = packages.size();
            stringBuilder.append(packages.get(0));
            for (int i = 1; i < size; i++) {
                stringBuilder.append(" ");
                stringBuilder.append(packages.get(i));
            }
        }
        if (activities != null && activities.size() > 0) {
            stringBuilder.append(" ");
            stringBuilder.append(activities.get(0).flattenToShortString());
            final int size = activities.size();
            for (int i = 1; i < size; i++) {
                stringBuilder.append(" ");
                stringBuilder.append(activities.get(i).flattenToShortString());
            }
        }
        StatsLog.write(StatsLog.CONTENT_CAPTURE_SERVICE_EVENTS,
                StatsLog.CONTENT_CAPTURE_SERVICE_EVENTS__EVENT__SET_WHITELIST,
                serviceName, stringBuilder.toString());
    }

    /** @hide */
    public static void writeSessionEvent(int sessionId, int event, int flags,
            @NonNull ComponentName service, @Nullable ComponentName app, boolean isChildSession) {
        StatsLog.write(StatsLog.CONTENT_CAPTURE_SESSION_EVENTS, sessionId, event, flags,
                ComponentName.flattenToShortString(service),
                ComponentName.flattenToShortString(app), isChildSession);
    }

    /** @hide */
    public static void writeSessionFlush(int sessionId, @NonNull ComponentName service,
            @Nullable ComponentName app, @NonNull FlushMetrics fm,
            @NonNull ContentCaptureOptions options, int flushReason) {
        StatsLog.write(StatsLog.CONTENT_CAPTURE_FLUSHED, sessionId,
                ComponentName.flattenToShortString(service),
                ComponentName.flattenToShortString(app), fm.sessionStarted, fm.sessionFinished,
                fm.viewAppearedCount, fm.viewDisappearedCount, fm.viewTextChangedCount,
                options.maxBufferSize, options.idleFlushingFrequencyMs,
                options.textChangeFlushingFrequencyMs, flushReason);
    }
}
