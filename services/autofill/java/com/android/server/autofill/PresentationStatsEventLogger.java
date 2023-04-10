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

import static android.service.autofill.FillEventHistory.Event.UI_TYPE_DIALOG;
import static android.service.autofill.FillEventHistory.Event.UI_TYPE_INLINE;
import static android.service.autofill.FillEventHistory.Event.UI_TYPE_MENU;
import static android.service.autofill.FillEventHistory.Event.UiType;
import static android.view.autofill.AutofillManager.COMMIT_REASON_ACTIVITY_FINISHED;
import static android.view.autofill.AutofillManager.COMMIT_REASON_VIEW_CHANGED;
import static android.view.autofill.AutofillManager.COMMIT_REASON_VIEW_CLICKED;
import static android.view.autofill.AutofillManager.COMMIT_REASON_VIEW_COMMITTED;

import static com.android.internal.util.FrameworkStatsLog.AUTOFILL_PRESENTATION_EVENT_REPORTED;
import static com.android.internal.util.FrameworkStatsLog.AUTOFILL_PRESENTATION_EVENT_REPORTED__AUTHENTICATION_RESULT__AUTHENTICATION_FAILURE;
import static com.android.internal.util.FrameworkStatsLog.AUTOFILL_PRESENTATION_EVENT_REPORTED__AUTHENTICATION_RESULT__AUTHENTICATION_RESULT_UNKNOWN;
import static com.android.internal.util.FrameworkStatsLog.AUTOFILL_PRESENTATION_EVENT_REPORTED__AUTHENTICATION_RESULT__AUTHENTICATION_SUCCESS;
import static com.android.internal.util.FrameworkStatsLog.AUTOFILL_PRESENTATION_EVENT_REPORTED__AUTHENTICATION_TYPE__AUTHENTICATION_TYPE_UNKNOWN;
import static com.android.internal.util.FrameworkStatsLog.AUTOFILL_PRESENTATION_EVENT_REPORTED__AUTHENTICATION_TYPE__DATASET_AUTHENTICATION;
import static com.android.internal.util.FrameworkStatsLog.AUTOFILL_PRESENTATION_EVENT_REPORTED__AUTHENTICATION_TYPE__FULL_AUTHENTICATION;
import static com.android.internal.util.FrameworkStatsLog.AUTOFILL_PRESENTATION_EVENT_REPORTED__DISPLAY_PRESENTATION_TYPE__DIALOG;
import static com.android.internal.util.FrameworkStatsLog.AUTOFILL_PRESENTATION_EVENT_REPORTED__DISPLAY_PRESENTATION_TYPE__INLINE;
import static com.android.internal.util.FrameworkStatsLog.AUTOFILL_PRESENTATION_EVENT_REPORTED__DISPLAY_PRESENTATION_TYPE__MENU;
import static com.android.internal.util.FrameworkStatsLog.AUTOFILL_PRESENTATION_EVENT_REPORTED__DISPLAY_PRESENTATION_TYPE__UNKNOWN_AUTOFILL_DISPLAY_PRESENTATION_TYPE;
import static com.android.internal.util.FrameworkStatsLog.AUTOFILL_PRESENTATION_EVENT_REPORTED__PRESENTATION_EVENT_RESULT__ANY_SHOWN;
import static com.android.internal.util.FrameworkStatsLog.AUTOFILL_PRESENTATION_EVENT_REPORTED__PRESENTATION_EVENT_RESULT__NONE_SHOWN_ACTIVITY_FINISHED;
import static com.android.internal.util.FrameworkStatsLog.AUTOFILL_PRESENTATION_EVENT_REPORTED__PRESENTATION_EVENT_RESULT__NONE_SHOWN_FILL_REQUEST_FAILED;
import static com.android.internal.util.FrameworkStatsLog.AUTOFILL_PRESENTATION_EVENT_REPORTED__PRESENTATION_EVENT_RESULT__NONE_SHOWN_NO_FOCUS;
import static com.android.internal.util.FrameworkStatsLog.AUTOFILL_PRESENTATION_EVENT_REPORTED__PRESENTATION_EVENT_RESULT__NONE_SHOWN_REQUEST_TIMEOUT;
import static com.android.internal.util.FrameworkStatsLog.AUTOFILL_PRESENTATION_EVENT_REPORTED__PRESENTATION_EVENT_RESULT__NONE_SHOWN_SESSION_COMMITTED_PREMATURELY;
import static com.android.internal.util.FrameworkStatsLog.AUTOFILL_PRESENTATION_EVENT_REPORTED__PRESENTATION_EVENT_RESULT__NONE_SHOWN_UNKNOWN_REASON;
import static com.android.internal.util.FrameworkStatsLog.AUTOFILL_PRESENTATION_EVENT_REPORTED__PRESENTATION_EVENT_RESULT__NONE_SHOWN_VIEW_CHANGED;
import static com.android.internal.util.FrameworkStatsLog.AUTOFILL_PRESENTATION_EVENT_REPORTED__PRESENTATION_EVENT_RESULT__NONE_SHOWN_VIEW_FOCUSED_BEFORE_FILL_DIALOG_RESPONSE;
import static com.android.internal.util.FrameworkStatsLog.AUTOFILL_PRESENTATION_EVENT_REPORTED__PRESENTATION_EVENT_RESULT__NONE_SHOWN_VIEW_FOCUS_CHANGED;
import static com.android.server.autofill.Helper.sVerbose;

import android.annotation.IntDef;
import android.annotation.Nullable;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.provider.Settings;
import android.service.autofill.Dataset;
import android.text.TextUtils;
import android.util.Slog;
import android.view.autofill.AutofillId;
import android.view.autofill.AutofillManager;

import com.android.internal.util.FrameworkStatsLog;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.List;
import java.util.Optional;

/** Helper class to track and log Autofill presentation stats. */
public final class PresentationStatsEventLogger {
    private static final String TAG = "PresentationStatsEventLogger";

    /**
     * Reasons why presentation was not shown. These are wrappers around
     * {@link com.android.os.AtomsProto.AutofillPresentationEventReported.PresentationEventResult}.
     */
    @IntDef(prefix = {"NOT_SHOWN_REASON"}, value = {
            NOT_SHOWN_REASON_ANY_SHOWN,
            NOT_SHOWN_REASON_VIEW_FOCUS_CHANGED,
            NOT_SHOWN_REASON_VIEW_FOCUSED_BEFORE_FILL_DIALOG_RESPONSE,
            NOT_SHOWN_REASON_VIEW_CHANGED,
            NOT_SHOWN_REASON_ACTIVITY_FINISHED,
            NOT_SHOWN_REASON_REQUEST_TIMEOUT,
            NOT_SHOWN_REASON_REQUEST_FAILED,
            NOT_SHOWN_REASON_NO_FOCUS,
            NOT_SHOWN_REASON_SESSION_COMMITTED_PREMATURELY,
            NOT_SHOWN_REASON_UNKNOWN
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface NotShownReason {}

    /**
     * Reasons why presentation was not shown. These are wrappers around
     * {@link com.android.os.AtomsProto.AutofillPresentationEventReported.AuthenticationType}.
     */
    @IntDef(prefix = {"AUTHENTICATION_TYPE"}, value = {
            AUTHENTICATION_TYPE_UNKNOWN,
            AUTHENTICATION_TYPE_DATASET_AUTHENTICATION,
            AUTHENTICATION_TYPE_FULL_AUTHENTICATION
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface AuthenticationType {
    }

    /**
     * Reasons why presentation was not shown. These are wrappers around
     * {@link com.android.os.AtomsProto.AutofillPresentationEventReported.AuthenticationResult}.
     */
    @IntDef(prefix = {"AUTHENTICATION_RESULT"}, value = {
            AUTHENTICATION_RESULT_UNKNOWN,
            AUTHENTICATION_RESULT_SUCCESS,
            AUTHENTICATION_RESULT_FAILURE
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface AuthenticationResult {
    }

    public static final int NOT_SHOWN_REASON_ANY_SHOWN =
            AUTOFILL_PRESENTATION_EVENT_REPORTED__PRESENTATION_EVENT_RESULT__ANY_SHOWN;
    public static final int NOT_SHOWN_REASON_VIEW_FOCUS_CHANGED =
            AUTOFILL_PRESENTATION_EVENT_REPORTED__PRESENTATION_EVENT_RESULT__NONE_SHOWN_VIEW_FOCUS_CHANGED;
    public static final int NOT_SHOWN_REASON_VIEW_FOCUSED_BEFORE_FILL_DIALOG_RESPONSE =
            AUTOFILL_PRESENTATION_EVENT_REPORTED__PRESENTATION_EVENT_RESULT__NONE_SHOWN_VIEW_FOCUSED_BEFORE_FILL_DIALOG_RESPONSE;
    public static final int NOT_SHOWN_REASON_VIEW_CHANGED =
            AUTOFILL_PRESENTATION_EVENT_REPORTED__PRESENTATION_EVENT_RESULT__NONE_SHOWN_VIEW_CHANGED;
    public static final int NOT_SHOWN_REASON_ACTIVITY_FINISHED =
            AUTOFILL_PRESENTATION_EVENT_REPORTED__PRESENTATION_EVENT_RESULT__NONE_SHOWN_ACTIVITY_FINISHED;
    public static final int NOT_SHOWN_REASON_REQUEST_TIMEOUT =
            AUTOFILL_PRESENTATION_EVENT_REPORTED__PRESENTATION_EVENT_RESULT__NONE_SHOWN_REQUEST_TIMEOUT;
    public static final int NOT_SHOWN_REASON_REQUEST_FAILED =
            AUTOFILL_PRESENTATION_EVENT_REPORTED__PRESENTATION_EVENT_RESULT__NONE_SHOWN_FILL_REQUEST_FAILED;
    public static final int NOT_SHOWN_REASON_NO_FOCUS =
            AUTOFILL_PRESENTATION_EVENT_REPORTED__PRESENTATION_EVENT_RESULT__NONE_SHOWN_NO_FOCUS;
    public static final int NOT_SHOWN_REASON_SESSION_COMMITTED_PREMATURELY =
            AUTOFILL_PRESENTATION_EVENT_REPORTED__PRESENTATION_EVENT_RESULT__NONE_SHOWN_SESSION_COMMITTED_PREMATURELY;
    public static final int NOT_SHOWN_REASON_UNKNOWN =
            AUTOFILL_PRESENTATION_EVENT_REPORTED__PRESENTATION_EVENT_RESULT__NONE_SHOWN_UNKNOWN_REASON;

    public static final int AUTHENTICATION_TYPE_UNKNOWN =
            AUTOFILL_PRESENTATION_EVENT_REPORTED__AUTHENTICATION_TYPE__AUTHENTICATION_TYPE_UNKNOWN;
    public static final int AUTHENTICATION_TYPE_DATASET_AUTHENTICATION =
            AUTOFILL_PRESENTATION_EVENT_REPORTED__AUTHENTICATION_TYPE__DATASET_AUTHENTICATION;
    public static final int AUTHENTICATION_TYPE_FULL_AUTHENTICATION =
            AUTOFILL_PRESENTATION_EVENT_REPORTED__AUTHENTICATION_TYPE__FULL_AUTHENTICATION;

    public static final int AUTHENTICATION_RESULT_UNKNOWN =
            AUTOFILL_PRESENTATION_EVENT_REPORTED__AUTHENTICATION_RESULT__AUTHENTICATION_RESULT_UNKNOWN;
    public static final int AUTHENTICATION_RESULT_SUCCESS =
            AUTOFILL_PRESENTATION_EVENT_REPORTED__AUTHENTICATION_RESULT__AUTHENTICATION_SUCCESS;
    public static final int AUTHENTICATION_RESULT_FAILURE =
            AUTOFILL_PRESENTATION_EVENT_REPORTED__AUTHENTICATION_RESULT__AUTHENTICATION_FAILURE;

    private final int mSessionId;
    private Optional<PresentationStatsEventInternal> mEventInternal;

    private PresentationStatsEventLogger(int sessionId) {
        mSessionId = sessionId;
        mEventInternal = Optional.empty();
    }

    public static PresentationStatsEventLogger forSessionId(int sessionId) {
        return new PresentationStatsEventLogger(sessionId);
    }

    public void startNewEvent() {
        if (mEventInternal.isPresent()) {
            Slog.e(TAG, "Failed to start new event because already have active event.");
            return;
        }
        mEventInternal = Optional.of(new PresentationStatsEventInternal());
    }

    public void maybeSetRequestId(int requestId) {
        mEventInternal.ifPresent(event -> event.mRequestId = requestId);
    }

    public void maybeSetNoPresentationEventReason(@NotShownReason int reason) {
        mEventInternal.ifPresent(event -> {
            if (event.mCountShown == 0) {
                event.mNoPresentationReason = reason;
            }
        });
    }

    public void maybeSetNoPresentationEventReasonIfNoReasonExists(@NotShownReason int reason) {
        mEventInternal.ifPresent(event -> {
            if (event.mCountShown == 0 && event.mNoPresentationReason == NOT_SHOWN_REASON_UNKNOWN) {
                event.mNoPresentationReason = reason;
            }
        });
    }

    public void maybeSetAvailableCount(@Nullable List<Dataset> datasetList,
            AutofillId currentViewId) {
        mEventInternal.ifPresent(event -> {
            int availableCount = getDatasetCountForAutofillId(datasetList, currentViewId);
            event.mAvailableCount = availableCount;
            event.mIsDatasetAvailable = availableCount > 0;
        });
    }

    public void maybeSetCountShown(@Nullable List<Dataset> datasetList,
            AutofillId currentViewId) {
        mEventInternal.ifPresent(event -> {
            int countShown = getDatasetCountForAutofillId(datasetList, currentViewId);
            event.mCountShown = countShown;
            if (countShown > 0) {
                event.mNoPresentationReason = NOT_SHOWN_REASON_ANY_SHOWN;
            }
        });
    }

    private static int getDatasetCountForAutofillId(@Nullable List<Dataset> datasetList,
            AutofillId currentViewId) {
        int availableCount = 0;
        if (datasetList != null) {
            for (int i = 0; i < datasetList.size(); i++) {
                Dataset data = datasetList.get(i);
                if (data != null && data.getFieldIds() != null
                        && data.getFieldIds().contains(currentViewId)) {
                    availableCount += 1;
                }
            }
        }
        return availableCount;
    }

    public void maybeSetCountFilteredUserTyping(int countFilteredUserTyping) {
        mEventInternal.ifPresent(event -> {
            event.mCountFilteredUserTyping = countFilteredUserTyping;
        });
    }

    public void maybeSetCountNotShownImePresentationNotDrawn(
            int countNotShownImePresentationNotDrawn) {
        mEventInternal.ifPresent(event -> {
            event.mCountNotShownImePresentationNotDrawn = countNotShownImePresentationNotDrawn;
        });
    }

    public void maybeSetCountNotShownImeUserNotSeen(int countNotShownImeUserNotSeen) {
        mEventInternal.ifPresent(event -> {
            event.mCountNotShownImeUserNotSeen = countNotShownImeUserNotSeen;
        });
    }

    public void maybeSetDisplayPresentationType(@UiType int uiType) {
        mEventInternal.ifPresent(event -> {
            event.mDisplayPresentationType = getDisplayPresentationType(uiType);
        });
    }

    public void maybeSetFillRequestSentTimestampMs(int timestamp) {
        mEventInternal.ifPresent(event -> {
            event.mFillRequestSentTimestampMs = timestamp;
        });
    }

    public void maybeSetFillResponseReceivedTimestampMs(int timestamp) {
        mEventInternal.ifPresent(event -> {
            event.mFillResponseReceivedTimestampMs = timestamp;
        });
    }

    public void maybeSetSuggestionSentTimestampMs(int timestamp) {
        mEventInternal.ifPresent(event -> {
            event.mSuggestionSentTimestampMs = timestamp;
        });
    }

    public void maybeSetSuggestionPresentedTimestampMs(int timestamp) {
        mEventInternal.ifPresent(event -> {
            event.mSuggestionPresentedTimestampMs = timestamp;
        });
    }

    public void maybeSetSelectedDatasetId(int selectedDatasetId) {
        mEventInternal.ifPresent(event -> {
            event.mSelectedDatasetId = selectedDatasetId;
        });
    }

    public void maybeSetDialogDismissed(boolean dialogDismissed) {
        mEventInternal.ifPresent(event -> {
            event.mDialogDismissed = dialogDismissed;
        });
    }

    public void maybeSetNegativeCtaButtonClicked(boolean negativeCtaButtonClicked) {
        mEventInternal.ifPresent(event -> {
            event.mNegativeCtaButtonClicked = negativeCtaButtonClicked;
        });
    }

    public void maybeSetPositiveCtaButtonClicked(boolean positiveCtaButtonClicked) {
        mEventInternal.ifPresent(event -> {
            event.mPositiveCtaButtonClicked = positiveCtaButtonClicked;
        });
    }

    public void maybeSetInlinePresentationAndSuggestionHostUid(Context context, int userId) {
        mEventInternal.ifPresent(event -> {
            event.mDisplayPresentationType =
                    AUTOFILL_PRESENTATION_EVENT_REPORTED__DISPLAY_PRESENTATION_TYPE__INLINE;
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

    public void maybeSetAutofillServiceUid(int uid) {
        mEventInternal.ifPresent(event -> {
            event.mAutofillServiceUid = uid;
        });
    }

    public void maybeSetIsNewRequest(boolean isRequestTriggered) {
        mEventInternal.ifPresent(event -> {
            event.mIsRequestTriggered = isRequestTriggered;
        });
    }

    /**
     * Set authentication_type as long as mEventInternal presents.
     */
    public void maybeSetAuthenticationType(@AuthenticationType int val) {
        mEventInternal.ifPresent(event -> {
            event.mAuthenticationType = val;
        });
    }

    /**
     * Set authentication_result as long as mEventInternal presents.
     */
    public void maybeSetAuthenticationResult(@AuthenticationResult int val) {
        mEventInternal.ifPresent(event -> {
            event.mAuthenticationResult = val;
        });
    }

    /**
     * Set latency_authentication_ui_display_millis as long as mEventInternal presents.
     */
    public void maybeSetLatencyAuthenticationUiDisplayMillis(int val) {
        mEventInternal.ifPresent(event -> {
            event.mLatencyAuthenticationUiDisplayMillis = val;
        });
    }

    /**
     * Set latency_dataset_display_millis as long as mEventInternal presents.
     */
    public void maybeSetLatencyDatasetDisplayMillis(int val) {
        mEventInternal.ifPresent(event -> {
            event.mLatencyDatasetDisplayMillis = val;
        });
    }


    public void logAndEndEvent() {
        if (!mEventInternal.isPresent()) {
            Slog.w(TAG, "Shouldn't be logging AutofillPresentationEventReported again for same "
                    + "event");
            return;
        }
        PresentationStatsEventInternal event = mEventInternal.get();
        if (sVerbose) {
            Slog.v(TAG, "Log AutofillPresentationEventReported:"
                    + " requestId=" + event.mRequestId
                    + " sessionId=" + mSessionId
                    + " mNoPresentationEventReason=" + event.mNoPresentationReason
                    + " mAvailableCount=" + event.mAvailableCount
                    + " mCountShown=" + event.mCountShown
                    + " mCountFilteredUserTyping=" + event.mCountFilteredUserTyping
                    + " mCountNotShownImePresentationNotDrawn="
                    + event.mCountNotShownImePresentationNotDrawn
                    + " mCountNotShownImeUserNotSeen=" + event.mCountNotShownImeUserNotSeen
                    + " mDisplayPresentationType=" + event.mDisplayPresentationType
                    + " mAutofillServiceUid=" + event.mAutofillServiceUid
                    + " mInlineSuggestionHostUid=" + event.mInlineSuggestionHostUid
                    + " mIsRequestTriggered=" + event.mIsRequestTriggered
                    + " mFillRequestSentTimestampMs=" + event.mFillRequestSentTimestampMs
                    + " mFillResponseReceivedTimestampMs=" + event.mFillResponseReceivedTimestampMs
                    + " mSuggestionSentTimestampMs=" + event.mSuggestionSentTimestampMs
                    + " mSuggestionPresentedTimestampMs=" + event.mSuggestionPresentedTimestampMs
                    + " mSelectedDatasetId=" + event.mSelectedDatasetId
                    + " mDialogDismissed=" + event.mDialogDismissed
                    + " mNegativeCtaButtonClicked=" + event.mNegativeCtaButtonClicked
                    + " mPositiveCtaButtonClicked=" + event.mPositiveCtaButtonClicked
                    + " mAuthenticationType=" + event.mAuthenticationType
                    + " mAuthenticationResult=" + event.mAuthenticationResult
                    + " mLatencyAuthenticationUiDisplayMillis="
                    + event.mLatencyAuthenticationUiDisplayMillis
                    + " mLatencyDatasetDisplayMillis=" + event.mLatencyDatasetDisplayMillis);
        }

        // TODO(b/234185326): Distinguish empty responses from other no presentation reasons.
        if (!event.mIsDatasetAvailable) {
            mEventInternal = Optional.empty();
            return;
        }
        FrameworkStatsLog.write(
                AUTOFILL_PRESENTATION_EVENT_REPORTED,
                event.mRequestId,
                mSessionId,
                event.mNoPresentationReason,
                event.mAvailableCount,
                event.mCountShown,
                event.mCountFilteredUserTyping,
                event.mCountNotShownImePresentationNotDrawn,
                event.mCountNotShownImeUserNotSeen,
                event.mDisplayPresentationType,
                event.mAutofillServiceUid,
                event.mInlineSuggestionHostUid,
                event.mIsRequestTriggered,
                event.mFillRequestSentTimestampMs,
                event.mFillResponseReceivedTimestampMs,
                event.mSuggestionSentTimestampMs,
                event.mSuggestionPresentedTimestampMs,
                event.mSelectedDatasetId,
                event.mDialogDismissed,
                event.mNegativeCtaButtonClicked,
                event.mPositiveCtaButtonClicked,
                event.mAuthenticationType,
                event.mAuthenticationResult,
                event.mLatencyAuthenticationUiDisplayMillis,
                event.mLatencyDatasetDisplayMillis);
        mEventInternal = Optional.empty();
    }

    private static final class PresentationStatsEventInternal {
        int mRequestId;
        @NotShownReason int mNoPresentationReason = NOT_SHOWN_REASON_UNKNOWN;
        boolean mIsDatasetAvailable;
        int mAvailableCount;
        int mCountShown;
        int mCountFilteredUserTyping;
        int mCountNotShownImePresentationNotDrawn;
        int mCountNotShownImeUserNotSeen;
        int mDisplayPresentationType = AUTOFILL_PRESENTATION_EVENT_REPORTED__DISPLAY_PRESENTATION_TYPE__UNKNOWN_AUTOFILL_DISPLAY_PRESENTATION_TYPE;
        int mAutofillServiceUid = -1;
        int mInlineSuggestionHostUid = -1;
        boolean mIsRequestTriggered;
        int mFillRequestSentTimestampMs;
        int mFillResponseReceivedTimestampMs;
        int mSuggestionSentTimestampMs;
        int mSuggestionPresentedTimestampMs;
        int mSelectedDatasetId = -1;
        boolean mDialogDismissed = false;
        boolean mNegativeCtaButtonClicked = false;
        boolean mPositiveCtaButtonClicked = false;
        int mAuthenticationType = AUTHENTICATION_TYPE_UNKNOWN;
        int mAuthenticationResult = AUTHENTICATION_RESULT_UNKNOWN;
        int mLatencyAuthenticationUiDisplayMillis = -1;
        int mLatencyDatasetDisplayMillis = -1;

        PresentationStatsEventInternal() {}
    }

    static int getNoPresentationEventReason(
            @AutofillManager.AutofillCommitReason int commitReason) {
        switch (commitReason) {
            case COMMIT_REASON_VIEW_COMMITTED:
                return NOT_SHOWN_REASON_SESSION_COMMITTED_PREMATURELY;
            case COMMIT_REASON_ACTIVITY_FINISHED:
                return NOT_SHOWN_REASON_ACTIVITY_FINISHED;
            case COMMIT_REASON_VIEW_CHANGED:
                return NOT_SHOWN_REASON_VIEW_CHANGED;
            case COMMIT_REASON_VIEW_CLICKED:
                // TODO(b/234185326): Add separate reason for view clicked.
            default:
                return NOT_SHOWN_REASON_UNKNOWN;
        }
    }

    private static int getDisplayPresentationType(@UiType int uiType) {
        switch (uiType) {
            case UI_TYPE_MENU:
                return AUTOFILL_PRESENTATION_EVENT_REPORTED__DISPLAY_PRESENTATION_TYPE__MENU;
            case UI_TYPE_INLINE:
                return AUTOFILL_PRESENTATION_EVENT_REPORTED__DISPLAY_PRESENTATION_TYPE__INLINE;
            case UI_TYPE_DIALOG:
                return AUTOFILL_PRESENTATION_EVENT_REPORTED__DISPLAY_PRESENTATION_TYPE__DIALOG;
            default:
                return AUTOFILL_PRESENTATION_EVENT_REPORTED__DISPLAY_PRESENTATION_TYPE__UNKNOWN_AUTOFILL_DISPLAY_PRESENTATION_TYPE;
        }
    }
}
