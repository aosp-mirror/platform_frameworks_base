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

import com.android.internal.util.FrameworkStatsLog;

import java.util.List;

/** @hide */
public final class ContentCaptureMetricsLogger {
    /**
     * Class only contains static utility functions, and should not be instantiated
     */
    private ContentCaptureMetricsLogger() {
    }

    /** @hide */
    public static void writeServiceEvent(int eventType, @NonNull String serviceName) {
        // we should not logging the application package name
        FrameworkStatsLog.write(FrameworkStatsLog.CONTENT_CAPTURE_SERVICE_EVENTS, eventType,
                serviceName, /* componentName= */ null, 0, 0);
    }

    /** @hide */
    public static void writeServiceEvent(int eventType, @NonNull ComponentName service) {
        writeServiceEvent(eventType, ComponentName.flattenToShortString(service));
    }

    /** @hide */
    public static void writeSetWhitelistEvent(@Nullable ComponentName service,
            @Nullable List<String> packages, @Nullable List<ComponentName> activities) {
        final String serviceName = ComponentName.flattenToShortString(service);
        int packageCount = packages != null ? packages.size() : 0;
        int activityCount = activities != null ? activities.size() : 0;
        // we should not logging the application package name
        // log the allow list package and activity count instead
        FrameworkStatsLog.write(FrameworkStatsLog.CONTENT_CAPTURE_SERVICE_EVENTS,
                FrameworkStatsLog.CONTENT_CAPTURE_SERVICE_EVENTS__EVENT__SET_WHITELIST,
                serviceName, /* allowListStr= */ null, packageCount, activityCount);
    }

    /** @hide */
    public static void writeSessionEvent(int sessionId, int event, int flags,
            @NonNull ComponentName service, boolean isChildSession) {
        // we should not logging the application package name
        FrameworkStatsLog.write(FrameworkStatsLog.CONTENT_CAPTURE_SESSION_EVENTS, sessionId, event,
                flags, ComponentName.flattenToShortString(service),
            /* componentName= */ null, isChildSession);
    }

    /** @hide */
    public static void writeSessionFlush(int sessionId, @NonNull ComponentName service,
            @NonNull FlushMetrics fm, @NonNull ContentCaptureOptions options,
            int flushReason) {
        // we should not logging the application package name
        FrameworkStatsLog.write(FrameworkStatsLog.CONTENT_CAPTURE_FLUSHED, sessionId,
                ComponentName.flattenToShortString(service),
                /* componentName= */ null, fm.sessionStarted, fm.sessionFinished,
                fm.viewAppearedCount, fm.viewDisappearedCount, fm.viewTextChangedCount,
                options.maxBufferSize, options.idleFlushingFrequencyMs,
                options.textChangeFlushingFrequencyMs, flushReason);
    }
}
