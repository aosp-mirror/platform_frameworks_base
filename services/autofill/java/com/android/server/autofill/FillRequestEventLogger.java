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

package com.android.server.autofill;

import static com.android.internal.util.FrameworkStatsLog.AUTOFILL_FILL_REQUEST_REPORTED;
import static com.android.internal.util.FrameworkStatsLog.AUTOFILL_FILL_REQUEST_REPORTED__REQUEST_TRIGGER_REASON__TRIGGER_REASON_EXPLICITLY_REQUESTED;
import static com.android.internal.util.FrameworkStatsLog.AUTOFILL_FILL_REQUEST_REPORTED__REQUEST_TRIGGER_REASON__TRIGGER_REASON_NORMAL_TRIGGER;
import static com.android.internal.util.FrameworkStatsLog.AUTOFILL_FILL_REQUEST_REPORTED__REQUEST_TRIGGER_REASON__TRIGGER_REASON_PRE_TRIGGER;
import static com.android.internal.util.FrameworkStatsLog.AUTOFILL_FILL_REQUEST_REPORTED__REQUEST_TRIGGER_REASON__TRIGGER_REASON_RETRIGGER;
import static com.android.internal.util.FrameworkStatsLog.AUTOFILL_FILL_REQUEST_REPORTED__REQUEST_TRIGGER_REASON__TRIGGER_REASON_SERVED_FROM_CACHED_RESPONSE;
import static com.android.internal.util.FrameworkStatsLog.AUTOFILL_FILL_REQUEST_REPORTED__REQUEST_TRIGGER_REASON__TRIGGER_REASON_UNKNOWN;
import static com.android.server.autofill.Helper.sVerbose;

import android.annotation.IntDef;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Slog;

import com.android.internal.util.FrameworkStatsLog;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Optional;

/**
 * Helper class to log Autofill FillRequest filing stats.
 */
public final class FillRequestEventLogger {
    private static final String TAG = "FillRequestEventLogger";

    /**
     * Reasons why presentation was not shown. These are wrappers around
     * {@link com.android.os.AtomsProto.AutofillFillRequestReported.RequestTriggerReason}.
     */
    @IntDef(prefix = {"TRIGGER_REASON"}, value = {
            TRIGGER_REASON_UNKNOWN,
            TRIGGER_REASON_EXPLICITLY_REQUESTED,
            TRIGGER_REASON_RETRIGGER,
            TRIGGER_REASON_PRE_TRIGGER,
            TRIGGER_REASON_NORMAL_TRIGGER,
            TRIGGER_REASON_SERVED_FROM_CACHED_RESPONSE
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface TriggerReason {
    }

    public static final int TRIGGER_REASON_UNKNOWN =
            AUTOFILL_FILL_REQUEST_REPORTED__REQUEST_TRIGGER_REASON__TRIGGER_REASON_UNKNOWN;
    public static final int TRIGGER_REASON_EXPLICITLY_REQUESTED =
            AUTOFILL_FILL_REQUEST_REPORTED__REQUEST_TRIGGER_REASON__TRIGGER_REASON_EXPLICITLY_REQUESTED;
    public static final int TRIGGER_REASON_RETRIGGER =
            AUTOFILL_FILL_REQUEST_REPORTED__REQUEST_TRIGGER_REASON__TRIGGER_REASON_RETRIGGER;
    public static final int TRIGGER_REASON_PRE_TRIGGER =
            AUTOFILL_FILL_REQUEST_REPORTED__REQUEST_TRIGGER_REASON__TRIGGER_REASON_PRE_TRIGGER;
    public static final int TRIGGER_REASON_NORMAL_TRIGGER =
            AUTOFILL_FILL_REQUEST_REPORTED__REQUEST_TRIGGER_REASON__TRIGGER_REASON_NORMAL_TRIGGER;
    public static final int TRIGGER_REASON_SERVED_FROM_CACHED_RESPONSE =
            AUTOFILL_FILL_REQUEST_REPORTED__REQUEST_TRIGGER_REASON__TRIGGER_REASON_SERVED_FROM_CACHED_RESPONSE;

    private final int mSessionId;
    private Optional<FillRequestEventInternal> mEventInternal;

    private FillRequestEventLogger(int sessionId) {
        mSessionId = sessionId;
        mEventInternal = Optional.empty();
    }

    /**
     * A factory constructor to create FillRequestEventLogger.
     */
    public static FillRequestEventLogger forSessionId(int sessionId) {
        return new FillRequestEventLogger(sessionId);
    }
    /**
     * Reset mEventInternal before logging for a new request. It shall be called for each
     * FillRequest.
     */
    public void startLogForNewRequest() {
        if (!mEventInternal.isEmpty()) {
            Slog.w(TAG, "FillRequestEventLogger is not empty before starting for a new " +
                    "request");
        }
        mEventInternal = Optional.of(new FillRequestEventInternal());
    }

    /**
     * Set request_id as long as mEventInternal presents.
     * For the case of Augmented Autofill, set to -2.
     */
    public void maybeSetRequestId(int requestId) {
        mEventInternal.ifPresent(event -> event.mRequestId = requestId);
    }

    /**
     * Set service_uid as long as mEventInternal presents.
     */
    public void maybeSetAutofillServiceUid(int uid) {
        mEventInternal.ifPresent(event -> {
            event.mAutofillServiceUid = uid;
        });
    }

    /**
     * Set inline_suggestion_host_uid as long as mEventInternal presents.
     */
    public void maybeSetInlineSuggestionHostUid(Context context, int userId) {
        mEventInternal.ifPresent(event -> {
            String imeString = Settings.Secure.getStringForUser(context.getContentResolver(),
                    Settings.Secure.DEFAULT_INPUT_METHOD, userId);
            if (TextUtils.isEmpty(imeString)) {
                Slog.w(TAG, "No default IME found");
                return;
            }
            ComponentName imeComponent = ComponentName.unflattenFromString(imeString);
            if (imeComponent == null) {
                Slog.w(TAG, "No default IME found");
                return;
            }
            int imeUid;
            String packageName = imeComponent.getPackageName();
            try {
                imeUid = context.getPackageManager().getApplicationInfoAsUser(packageName,
                        PackageManager.ApplicationInfoFlags.of(0), userId).uid;
            } catch (PackageManager.NameNotFoundException e) {
                Slog.w(TAG, "Couldn't find packageName: " + packageName);
                return;
            }
            event.mInlineSuggestionHostUid = imeUid;
        });
    }


    /**
     * Set flags as long as mEventInternal presents.
     */
    public void maybeSetFlags(int flags) {
        mEventInternal.ifPresent(event -> {
            event.mFlags = flags;
        });
    }

    /**
     * Set request_trigger_reason as long as mEventInternal presents.
     */
    public void maybeSetRequestTriggerReason(@TriggerReason int reason) {
        mEventInternal.ifPresent(event -> {
            event.mRequestTriggerReason = reason;
        });
    }

    /**
     * Set is_augmented as long as mEventInternal presents.
     */
    public void maybeSetIsAugmented(boolean val) {
        mEventInternal.ifPresent(event -> {
            event.mIsAugmented = val;
        });
    }

    /**
     * Set is_client_suggestion as long as mEventInternal presents.
     */
    public void maybeSetIsClientSuggestionFallback(boolean val) {
        mEventInternal.ifPresent(event -> {
            event.mIsClientSuggestionFallback = val;
        });
    }

    /**
     * Set is_fill_dialog_eligible as long as mEventInternal presents.
     */
    public void maybeSetIsFillDialogEligible(boolean val) {
        mEventInternal.ifPresent(event -> {
            event.mIsFillDialogEligible = val;
        });
    }

    /**
     * Set latency_fill_request_sent_millis as long as mEventInternal presents.
     */
    public void maybeSetLatencyFillRequestSentMillis(int timestamp) {
        mEventInternal.ifPresent(event -> {
            event.mLatencyFillRequestSentMillis = timestamp;
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
     * Log an AUTOFILL_FILL_REQUEST_REPORTED event.
     */
    public void logAndEndEvent() {
        if (!mEventInternal.isPresent()) {
            Slog.w(TAG, "Shouldn't be logging AutofillFillRequestReported again for same "
                    + "event");
            return;
        }
        FillRequestEventInternal event = mEventInternal.get();
        if (sVerbose) {
            Slog.v(TAG, "Log AutofillFillRequestReported:"
                    + " requestId=" + event.mRequestId
                    + " sessionId=" + mSessionId
                    + " mAutofillServiceUid=" + event.mAutofillServiceUid
                    + " mInlineSuggestionHostUid=" + event.mInlineSuggestionHostUid
                    + " mIsAugmented=" + event.mIsAugmented
                    + " mIsClientSuggestionFallback=" + event.mIsClientSuggestionFallback
                    + " mIsFillDialogEligible=" + event.mIsFillDialogEligible
                    + " mRequestTriggerReason=" + event.mRequestTriggerReason
                    + " mFlags=" + event.mFlags
                    + " mLatencyFillRequestSentMillis=" + event.mLatencyFillRequestSentMillis
                    + " mAppPackageUid=" + event.mAppPackageUid);
        }
        FrameworkStatsLog.write(
                AUTOFILL_FILL_REQUEST_REPORTED,
                event.mRequestId,
                mSessionId,
                event.mAutofillServiceUid,
                event.mInlineSuggestionHostUid,
                event.mIsAugmented,
                event.mIsClientSuggestionFallback,
                event.mIsFillDialogEligible,
                event.mRequestTriggerReason,
                event.mFlags,
                event.mLatencyFillRequestSentMillis,
                event.mAppPackageUid);
        mEventInternal = Optional.empty();
    }

    private static final class FillRequestEventInternal {
        int mRequestId;
        int mAppPackageUid = -1;
        int mAutofillServiceUid = -1;
        int mInlineSuggestionHostUid = -1;
        boolean mIsAugmented = false;
        boolean mIsClientSuggestionFallback = false;
        boolean mIsFillDialogEligible = false;
        int mRequestTriggerReason =
                AUTOFILL_FILL_REQUEST_REPORTED__REQUEST_TRIGGER_REASON__TRIGGER_REASON_UNKNOWN;
        int mFlags = -1;
        int mLatencyFillRequestSentMillis = -1;

        FillRequestEventInternal() {
        }
    }
}
