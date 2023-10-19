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
import static com.android.internal.util.FrameworkStatsLog.AUTOFILL_FIELD_CLASSIFICATION_EVENT_REPORTED__STATUS__STATUS_CANCELLED;
import static com.android.internal.util.FrameworkStatsLog.AUTOFILL_FIELD_CLASSIFICATION_EVENT_REPORTED__STATUS__STATUS_FAIL;
import static com.android.internal.util.FrameworkStatsLog.AUTOFILL_FIELD_CLASSIFICATION_EVENT_REPORTED__STATUS__STATUS_SUCCESS;
import static com.android.internal.util.FrameworkStatsLog.AUTOFILL_FIELD_CLASSIFICATION_EVENT_REPORTED__STATUS__STATUS_UNKNOWN;
import static com.android.server.autofill.Helper.sVerbose;

import android.annotation.IntDef;
import android.util.Slog;

import com.android.internal.util.FrameworkStatsLog;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
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

    public static final int STATUS_SUCCESS =
            AUTOFILL_FIELD_CLASSIFICATION_EVENT_REPORTED__STATUS__STATUS_SUCCESS;
    public static final int STATUS_UNKNOWN =
            AUTOFILL_FIELD_CLASSIFICATION_EVENT_REPORTED__STATUS__STATUS_UNKNOWN;
    public static final int STATUS_FAIL =
            AUTOFILL_FIELD_CLASSIFICATION_EVENT_REPORTED__STATUS__STATUS_FAIL;
    public static final int STATUS_CANCELLED =
            AUTOFILL_FIELD_CLASSIFICATION_EVENT_REPORTED__STATUS__STATUS_CANCELLED;

    /**
     * Status of the FieldClassification IPC request. These are wrappers around
     * {@link com.android.os.AtomsProto.AutofillFieldClassificationEventReported.FieldClassificationRequestStatus}.
     */
    @IntDef(prefix = {"STATUS"}, value = {
            STATUS_UNKNOWN,
            STATUS_SUCCESS,
            STATUS_FAIL,
            STATUS_CANCELLED
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface FieldClassificationStatus {
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
     * Set latency_millis as long as mEventInternal presents.
     */
    public void maybeSetLatencyMillis(long timestamp) {
        mEventInternal.ifPresent(event -> {
            event.mLatencyClassificationRequestMillis = timestamp;
        });
    }

    /**
     * Set count_classifications as long as mEventInternal presents.
     */
    public void maybeSetCountClassifications(int countClassifications) {
        mEventInternal.ifPresent(event -> {
            event.mCountClassifications = countClassifications;
        });
    }

    /**
     * Set session_id as long as mEventInternal presents.
     */
    public void maybeSetSessionId(int sessionId) {
        mEventInternal.ifPresent(event -> {
            event.mSessionId = sessionId;
        });
    }

    /**
     * Set request_id as long as mEventInternal presents.
     */
    public void maybeSetRequestId(int requestId) {
        mEventInternal.ifPresent(event -> {
            event.mRequestId = requestId;
        });
    }

    /**
     * Set next_fill_request_id as long as mEventInternal presents.
     */
    public void maybeSetNextFillRequestId(int nextFillRequestId) {
        mEventInternal.ifPresent(event -> {
            event.mNextFillRequestId = nextFillRequestId;
        });
    }

    /**
     * Set app_package_uid as long as mEventInternal presents.
     */
    public void maybeSetAppPackageUid(int uid) {
        mEventInternal.ifPresent(event -> {
            event.mAppPackageUid = uid;
        });
    }

    /**
     * Set status as long as mEventInternal presents.
     */
    public void maybeSetRequestStatus(@FieldClassificationStatus int status) {
        mEventInternal.ifPresent(event -> {
            event.mStatus = status;
        });
    }

    /**
     * Set is_session_gc as long as mEventInternal presents.
     */
    public void maybeSetSessionGc(boolean isSessionGc) {
        mEventInternal.ifPresent(event -> {
            event.mIsSessionGc = isSessionGc;
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
                    + event.mLatencyClassificationRequestMillis
                    + " mCountClassifications=" + event.mCountClassifications
                    + " mSessionId=" + event.mSessionId
                    + " mRequestId=" + event.mRequestId
                    + " mNextFillRequestId=" + event.mNextFillRequestId
                    + " mAppPackageUid=" + event.mAppPackageUid
                    + " mStatus=" + event.mStatus
                    + " mIsSessionGc=" + event.mIsSessionGc);
        }
        FrameworkStatsLog.write(
                AUTOFILL_FIELD_CLASSIFICATION_EVENT_REPORTED,
                event.mLatencyClassificationRequestMillis,
                event.mCountClassifications,
                event.mSessionId,
                event.mRequestId,
                event.mNextFillRequestId,
                event.mAppPackageUid,
                event.mStatus,
                event.mIsSessionGc);
        mEventInternal = Optional.empty();
    }

    private static final class FieldClassificationEventInternal {
        long mLatencyClassificationRequestMillis = -1;
        int mCountClassifications = -1;
        int mSessionId = -1;
        int mRequestId = -1;
        int mNextFillRequestId = -1;
        int mAppPackageUid = -1;
        int mStatus;
        boolean mIsSessionGc;

        FieldClassificationEventInternal() {
        }
    }
}
