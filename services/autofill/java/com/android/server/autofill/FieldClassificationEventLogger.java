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

package com.android.server.autofill;

import static com.android.internal.util.FrameworkStatsLog.AUTOFILL_FIELD_CLASSIFICATION_EVENT_REPORTED;
import static com.android.server.autofill.Helper.sVerbose;

import android.util.Slog;

import com.android.internal.util.FrameworkStatsLog;

import java.util.Optional;

/**
 * Helper class to log Field Classification stats.
 */
public final class FieldClassificationEventLogger {
    private static final String TAG = "FieldClassificationEventLogger";
    private Optional<FieldClassificationEventInternal> mEventInternal;

    private FieldClassificationEventLogger() {
        mEventInternal = Optional.empty();
    }

    /**
     * A factory constructor to create FieldClassificationEventLogger.
     */
    public static FieldClassificationEventLogger createLogger() {
        return new FieldClassificationEventLogger();
    }

    /**
     * Reset mEventInternal before logging for a new request. It shall be called for each
     * FieldClassification request.
     */
    public void startNewLogForRequest() {
        if (!mEventInternal.isEmpty()) {
            Slog.w(TAG, "FieldClassificationEventLogger is not empty before starting for a new "
                    + "request");
        }
        mEventInternal = Optional.of(new FieldClassificationEventInternal());
    }

    /**
     * Set latency as long as mEventInternal presents.
     */
    public void maybeSetLatencyMillis(long timestamp) {
        mEventInternal.ifPresent(event -> {
            event.mLatencyClassificationRequestMillis = timestamp;
        });
    }

    /**
     * Log an AUTOFILL_FIELD_CLASSIFICATION_EVENT_REPORTED event.
     */
    public void logAndEndEvent() {
        if (!mEventInternal.isPresent()) {
            Slog.w(TAG, "Shouldn't be logging AutofillFieldClassificationEventInternal again for "
                    + "same event");
            return;
        }
        FieldClassificationEventInternal event = mEventInternal.get();
        if (sVerbose) {
            Slog.v(TAG, "Log AutofillFieldClassificationEventReported:"
                    + " mLatencyClassificationRequestMillis="
                    + event.mLatencyClassificationRequestMillis);
        }
        FrameworkStatsLog.write(
                AUTOFILL_FIELD_CLASSIFICATION_EVENT_REPORTED,
                event.mLatencyClassificationRequestMillis);
        mEventInternal = Optional.empty();
    }

    private static final class FieldClassificationEventInternal {
        long mLatencyClassificationRequestMillis = -1;

        FieldClassificationEventInternal() {
        }
    }
}
