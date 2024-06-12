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

import static com.android.internal.util.FrameworkStatsLog.AUTOFILL_FILL_RESPONSE_REPORTED__DETECTION_PREFERENCE__DETECTION_PREFER_AUTOFILL_PROVIDER;
import static com.android.internal.util.FrameworkStatsLog.AUTOFILL_FILL_RESPONSE_REPORTED__DETECTION_PREFERENCE__DETECTION_PREFER_PCC;
import static com.android.internal.util.FrameworkStatsLog.AUTOFILL_FILL_RESPONSE_REPORTED__DETECTION_PREFERENCE__DETECTION_PREFER_UNKONWN;
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
import static com.android.internal.util.FrameworkStatsLog.AUTOFILL_PRESENTATION_EVENT_REPORTED__SELECTED_DATASET_PICKED_REASON__PICK_REASON_NO_PCC;
import static com.android.internal.util.FrameworkStatsLog.AUTOFILL_PRESENTATION_EVENT_REPORTED__SELECTED_DATASET_PICKED_REASON__PICK_REASON_PCC_DETECTION_ONLY;
import static com.android.internal.util.FrameworkStatsLog.AUTOFILL_PRESENTATION_EVENT_REPORTED__SELECTED_DATASET_PICKED_REASON__PICK_REASON_PCC_DETECTION_PREFERRED_WITH_PROVIDER;
import static com.android.internal.util.FrameworkStatsLog.AUTOFILL_PRESENTATION_EVENT_REPORTED__SELECTED_DATASET_PICKED_REASON__PICK_REASON_PROVIDER_DETECTION_ONLY;
import static com.android.internal.util.FrameworkStatsLog.AUTOFILL_PRESENTATION_EVENT_REPORTED__SELECTED_DATASET_PICKED_REASON__PICK_REASON_PROVIDER_DETECTION_PREFERRED_WITH_PCC;
import static com.android.internal.util.FrameworkStatsLog.AUTOFILL_PRESENTATION_EVENT_REPORTED__SELECTED_DATASET_PICKED_REASON__PICK_REASON_UNKNOWN;
import static com.android.server.autofill.Helper.sVerbose;

import android.annotation.IntDef;
import android.annotation.Nullable;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.SystemClock;
import android.provider.Settings;
import android.service.autofill.Dataset;
import android.text.TextUtils;
import android.util.ArraySet;
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

    /**
     * Reasons why the picked dataset was present. These are wrappers around
     * {@link com.android.os.AtomsProto.AutofillPresentationEventReported.DatasetPickedReason}.
     * This enum is similar to {@link android.service.autofill.Dataset.DatasetEligibleReason}
     */
    @IntDef(prefix = {"PICK_REASON"}, value = {
            PICK_REASON_UNKNOWN,
            PICK_REASON_NO_PCC,
            PICK_REASON_PROVIDER_DETECTION_ONLY,
            PICK_REASON_PROVIDER_DETECTION_PREFERRED_WITH_PCC,
            PICK_REASON_PCC_DETECTION_ONLY,
            PICK_REASON_PCC_DETECTION_PREFERRED_WITH_PROVIDER,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface DatasetPickedReason {}

    /**
     * The type of detection that was preferred. These are wrappers around
     * {@link com.android.os.AtomsProto.AutofillPresentationEventReported.DetectionPreference}.
     */
    @IntDef(prefix = {"DETECTION_PREFER"}, value = {
            DETECTION_PREFER_UNKNOWN,
            DETECTION_PREFER_AUTOFILL_PROVIDER,
            DETECTION_PREFER_PCC
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface DetectionPreference {
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

    public static final int PICK_REASON_UNKNOWN =
            AUTOFILL_PRESENTATION_EVENT_REPORTED__SELECTED_DATASET_PICKED_REASON__PICK_REASON_UNKNOWN;
    public static final int PICK_REASON_NO_PCC =
            AUTOFILL_PRESENTATION_EVENT_REPORTED__SELECTED_DATASET_PICKED_REASON__PICK_REASON_NO_PCC;
     public static final int PICK_REASON_PROVIDER_DETECTION_ONLY =
             AUTOFILL_PRESENTATION_EVENT_REPORTED__SELECTED_DATASET_PICKED_REASON__PICK_REASON_PROVIDER_DETECTION_ONLY;
    public static final int PICK_REASON_PROVIDER_DETECTION_PREFERRED_WITH_PCC =
            AUTOFILL_PRESENTATION_EVENT_REPORTED__SELECTED_DATASET_PICKED_REASON__PICK_REASON_PROVIDER_DETECTION_PREFERRED_WITH_PCC;
    public static final int PICK_REASON_PCC_DETECTION_ONLY =
            AUTOFILL_PRESENTATION_EVENT_REPORTED__SELECTED_DATASET_PICKED_REASON__PICK_REASON_PCC_DETECTION_ONLY;
    public static final int PICK_REASON_PCC_DETECTION_PREFERRED_WITH_PROVIDER =
            AUTOFILL_PRESENTATION_EVENT_REPORTED__SELECTED_DATASET_PICKED_REASON__PICK_REASON_PCC_DETECTION_PREFERRED_WITH_PROVIDER;


    // Values for AutofillFillResponseReported.detection_preference
    public static final int DETECTION_PREFER_UNKNOWN =
            AUTOFILL_FILL_RESPONSE_REPORTED__DETECTION_PREFERENCE__DETECTION_PREFER_UNKONWN;
    public static final int DETECTION_PREFER_AUTOFILL_PROVIDER =
            AUTOFILL_FILL_RESPONSE_REPORTED__DETECTION_PREFERENCE__DETECTION_PREFER_AUTOFILL_PROVIDER;
    public static final int DETECTION_PREFER_PCC =
            AUTOFILL_FILL_RESPONSE_REPORTED__DETECTION_PREFERENCE__DETECTION_PREFER_PCC;

    private static final int DEFAULT_VALUE_INT = -1;

    private final int mSessionId;
    /**
     * For app_package_uid.
     */
    private final int mCallingAppUid;
    private Optional<PresentationStatsEventInternal> mEventInternal;
    private final long mSessionStartTimestamp;

    private PresentationStatsEventLogger(int sessionId, int callingAppUid, long timestamp) {
        mSessionId = sessionId;
        mCallingAppUid = callingAppUid;
        mSessionStartTimestamp = timestamp;
        mEventInternal = Optional.empty();
    }

    /**
     * Create PresentationStatsEventLogger, populated with sessionId and the callingAppUid
     */
    public static PresentationStatsEventLogger createPresentationLog(
            int sessionId, int callingAppUid, long timestamp) {
        return new PresentationStatsEventLogger(sessionId, callingAppUid, timestamp);
    }

    public void startNewEvent() {
        if (mEventInternal.isPresent()) {
            Slog.e(TAG, "Failed to start new event because already have active event.");
            return;
        }
        mEventInternal = Optional.of(new PresentationStatsEventInternal());
    }

    /**
     * Set request_id
     */
    public void maybeSetRequestId(int requestId) {
        mEventInternal.ifPresent(event -> event.mRequestId = requestId);
    }

    /**
     * Set is_credential_request
     */
    public void maybeSetIsCredentialRequest(boolean isCredentialRequest) {
        mEventInternal.ifPresent(event -> event.mIsCredentialRequest = isCredentialRequest);
    }

    /**
     * Set webview_requested_credential
     */
    public void maybeSetWebviewRequestedCredential(boolean webviewRequestedCredential) {
        mEventInternal.ifPresent(event ->
                event.mWebviewRequestedCredential = webviewRequestedCredential);
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
            CountContainer container = getDatasetCountForAutofillId(datasetList, currentViewId);
            event.mAvailableCount = container.mAvailableCount;
            event.mAvailablePccCount = container.mAvailablePccCount;
            event.mAvailablePccOnlyCount = container.mAvailablePccOnlyCount;
            event.mIsDatasetAvailable = container.mAvailableCount > 0;
        });
    }

    public void maybeSetCountShown(@Nullable List<Dataset> datasetList,
            AutofillId currentViewId) {
        mEventInternal.ifPresent(event -> {
            CountContainer container = getDatasetCountForAutofillId(datasetList, currentViewId);
            event.mCountShown = container.mAvailableCount;
            if (container.mAvailableCount > 0) {
                event.mNoPresentationReason = NOT_SHOWN_REASON_ANY_SHOWN;
            }
        });
    }

    private static CountContainer getDatasetCountForAutofillId(@Nullable List<Dataset> datasetList,
            AutofillId currentViewId) {

        CountContainer container = new CountContainer();
        if (datasetList != null) {
            for (int i = 0; i < datasetList.size(); i++) {
                Dataset data = datasetList.get(i);
                if (data != null && data.getFieldIds() != null
                        && data.getFieldIds().contains(currentViewId)) {
                    container.mAvailableCount += 1;
                    if (data.getEligibleReason() == PICK_REASON_PCC_DETECTION_ONLY) {
                        container.mAvailablePccOnlyCount++;
                        container.mAvailablePccCount++;
                    } else if (data.getEligibleReason()
                            == PICK_REASON_PCC_DETECTION_PREFERRED_WITH_PROVIDER) {
                        container.mAvailablePccCount++;
                    }
                }
            }
        }
        return container;
    }

    private static class CountContainer{
        int mAvailableCount = 0;
        int mAvailablePccCount = 0;
        int mAvailablePccOnlyCount = 0;

        CountContainer() {}

        CountContainer(int availableCount, int availablePccCount,
                int availablePccOnlyCount) {
            mAvailableCount = availableCount;
            mAvailablePccCount = availablePccCount;
            mAvailablePccOnlyCount = availablePccOnlyCount;
        }
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

    public void maybeSetFillRequestSentTimestampMs() {
        maybeSetFillRequestSentTimestampMs(getElapsedTime());
    }

    public void maybeSetFillResponseReceivedTimestampMs(int timestamp) {
        mEventInternal.ifPresent(event -> {
            event.mFillResponseReceivedTimestampMs = timestamp;
        });
    }

    public void maybeSetFillResponseReceivedTimestampMs() {
        maybeSetFillResponseReceivedTimestampMs(getElapsedTime());
    }

    public void maybeSetSuggestionSentTimestampMs(int timestamp) {
        mEventInternal.ifPresent(event -> {
            event.mSuggestionSentTimestampMs = timestamp;
        });
    }

    public void maybeSetSuggestionSentTimestampMs() {
        maybeSetSuggestionSentTimestampMs(getElapsedTime());
    }

    public void maybeSetSuggestionPresentedTimestampMs(int timestamp) {
        mEventInternal.ifPresent(event -> {
            // mSuggestionPresentedTimestampMs only tracks the first suggested timestamp.
            if (event.mSuggestionPresentedTimestampMs == DEFAULT_VALUE_INT) {
                event.mSuggestionPresentedTimestampMs = timestamp;
            }

            event.mSuggestionPresentedLastTimestampMs = timestamp;
        });
    }

    public void maybeSetSuggestionPresentedTimestampMs() {
        maybeSetSuggestionPresentedTimestampMs(getElapsedTime());
    }

    public void maybeSetSelectedDatasetId(int selectedDatasetId) {
        mEventInternal.ifPresent(event -> {
            event.mSelectedDatasetId = selectedDatasetId;
        });
        setPresentationSelectedTimestamp();
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

    /** Set latency_authentication_ui_display_millis as long as mEventInternal presents. */
    public void maybeSetLatencyAuthenticationUiDisplayMillis() {
        maybeSetLatencyAuthenticationUiDisplayMillis(getElapsedTime());
    }

    /**
     * Set latency_dataset_display_millis as long as mEventInternal presents.
     */
    public void maybeSetLatencyDatasetDisplayMillis(int val) {
        mEventInternal.ifPresent(event -> {
            event.mLatencyDatasetDisplayMillis = val;
        });
    }

    /** Set latency_dataset_display_millis as long as mEventInternal presents. */
    public void maybeSetLatencyDatasetDisplayMillis() {
        maybeSetLatencyDatasetDisplayMillis(getElapsedTime());
    }

    /**
     * Set available_pcc_count.
     */
    public void maybeSetAvailablePccCount(int val) {
        mEventInternal.ifPresent(event -> {
            event.mAvailablePccCount = val;
        });
    }

    /**
     * Set available_pcc_only_count.
     */
    public void maybeSetAvailablePccOnlyCount(int val) {
        mEventInternal.ifPresent(event -> {
            event.mAvailablePccOnlyCount = val;
        });
    }

    /**
     * Set selected_dataset_picked_reason.
     */
    public void maybeSetSelectedDatasetPickReason(@Dataset.DatasetEligibleReason int val) {
        mEventInternal.ifPresent(event -> {
            event.mSelectedDatasetPickedReason = convertDatasetPickReason(val);
        });
    }

    /**
     * Set detection_pref
     */
    public void maybeSetDetectionPreference(@DetectionPreference int detectionPreference) {
        mEventInternal.ifPresent(event -> {
            event.mDetectionPreference = detectionPreference;
        });
    }

    /**
     * Set various timestamps whenever the ViewState is modified
     *
     * <p>If the ViewState contains ViewState.STATE_AUTOFILLED, sets field_autofilled_timestamp_ms
     * else, set field_first_modified_timestamp_ms (if unset) and field_last_modified_timestamp_ms
     */
    public void onFieldTextUpdated(ViewState state) {
        mEventInternal.ifPresent(
                event -> {
                    int timestamp = getElapsedTime();
                    // Focused id should be set before this is called
                    if (state.id != null && state.id.getViewId() != event.mFocusedId) {
                        // if these don't match, the currently field different than before
                        Slog.w(
                                TAG,
                                "current id: "
                                        + state.id.getViewId()
                                        + " is different than focused id: "
                                        + event.mFocusedId);
                        return;
                    }

                    if ((state.getState() & ViewState.STATE_AUTOFILLED) != 0) {
                        event.mAutofilledTimestampMs = timestamp;
                    } else {
                        if (event.mFieldModifiedFirstTimestampMs == DEFAULT_VALUE_INT) {
                            event.mFieldModifiedFirstTimestampMs = timestamp;
                        }
                        event.mFieldModifiedLastTimestampMs = timestamp;
                    }
                });
    }

    public void setPresentationSelectedTimestamp() {
        mEventInternal.ifPresent(event -> {
            event.mSelectionTimestamp = getElapsedTime();
        });
    }

    /**
     * Returns timestamp (relative to mSessionStartTimestamp)
     */
    private int getElapsedTime() {
        return (int)(SystemClock.elapsedRealtime() - mSessionStartTimestamp);
    }


    private int convertDatasetPickReason(@Dataset.DatasetEligibleReason int val) {
        switch (val) {
            case 0:
            case 1:
            case 2:
            case 3:
            case 4:
            case 5:
                return val;
        }
        return PICK_REASON_UNKNOWN;
    }

    /**
     * Set field_classification_request_id as long as mEventInternal presents.
     */
    public void maybeSetFieldClassificationRequestId(int requestId) {
        mEventInternal.ifPresent(event -> {
            event.mFieldClassificationRequestId = requestId;
        });
    }

    /**
     * Set views_fillable_total_count as long as mEventInternal presents.
     */
    public void maybeSetViewFillablesAndCount(List<AutofillId> autofillIds) {
        mEventInternal.ifPresent(event -> {
            event.mAutofillIdsAttemptedAutofill = new ArraySet<>(autofillIds);
            event.mViewFillableTotalCount = event.mAutofillIdsAttemptedAutofill.size();
        });
    }

    /**
     * Set views_filled_failure_count using failure count as long as mEventInternal
     * presents.
     */
    public void maybeSetViewFillFailureCounts(int failureCount) {
        mEventInternal.ifPresent(event -> {
            event.mViewFillFailureCount = failureCount;
        });
    }

    /** Sets focused_autofill_id using view id */
    public void maybeSetFocusedId(AutofillId id) {
        maybeSetFocusedId(id.getViewId());
    }

    /** Sets focused_autofill_id as long as mEventInternal is present */
    public void maybeSetFocusedId(int id) {
        mEventInternal.ifPresent(event -> {
            event.mFocusedId = id;
        });
    }
    /**
     * Set views_filled_failure_count using failure count as long as mEventInternal
     * presents.
     */
    public void maybeAddSuccessId(AutofillId autofillId) {
        mEventInternal.ifPresent(event -> {
            ArraySet<AutofillId> autofillIds = event.mAutofillIdsAttemptedAutofill;
            if (autofillIds == null) {
                Slog.w(TAG, "Attempted autofill ids is null, but received autofillId:" + autofillId
                        + " successfully filled");
                event.mViewFilledButUnexpectedCount++;
            } else if (autofillIds.contains(autofillId)) {
                if (sVerbose) {
                    Slog.v(TAG, "Logging autofill for id:" + autofillId);
                }
                event.mViewFillSuccessCount++;
                autofillIds.remove(autofillId);
                event.mAlreadyFilledAutofillIds.add(autofillId);
            } else if (event.mAlreadyFilledAutofillIds.contains(autofillId)) {
                if (sVerbose) {
                    Slog.v(TAG, "Successfully filled autofillId:" + autofillId
                            + " already processed ");
                }
            } else {
                Slog.w(TAG, "Successfully filled autofillId:" + autofillId
                        + " not found in list of attempted autofill ids: " + autofillIds);
                event.mViewFilledButUnexpectedCount++;
            }
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
                    + " mLatencyDatasetDisplayMillis=" + event.mLatencyDatasetDisplayMillis
                    + " mAvailablePccCount=" + event.mAvailablePccCount
                    + " mAvailablePccOnlyCount=" + event.mAvailablePccOnlyCount
                    + " mSelectedDatasetPickedReason=" + event.mSelectedDatasetPickedReason
                    + " mDetectionPreference=" + event.mDetectionPreference
                    + " mFieldClassificationRequestId=" + event.mFieldClassificationRequestId
                    + " mAppPackageUid=" + mCallingAppUid
                    + " mIsCredentialRequest=" + event.mIsCredentialRequest
                    + " mWebviewRequestedCredential=" + event.mWebviewRequestedCredential
                    + " mViewFillableTotalCount=" + event.mViewFillableTotalCount
                    + " mViewFillFailureCount=" + event.mViewFillFailureCount
                    + " mFocusedId=" + event.mFocusedId
                    + " mViewFillSuccessCount=" + event.mViewFillSuccessCount
                    + " mViewFilledButUnexpectedCount=" + event.mViewFilledButUnexpectedCount
                    + " event.mSelectionTimestamp=" + event.mSelectionTimestamp
                    + " event.mAutofilledTimestampMs=" + event.mAutofilledTimestampMs
                    + " event.mFieldModifiedFirstTimestampMs="
                    + event.mFieldModifiedFirstTimestampMs
                    + " event.mFieldModifiedLastTimestampMs=" + event.mFieldModifiedLastTimestampMs
                    + " event.mSuggestionPresentedLastTimestampMs="
                    + event.mSuggestionPresentedLastTimestampMs
                    + " event.mFocusedVirtualAutofillId=" + event.mFocusedVirtualAutofillId
                    + " event.mFieldFirstLength=" + event.mFieldFirstLength
                    + " event.mFieldLastLength=" + event.mFieldLastLength);
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
                event.mLatencyDatasetDisplayMillis,
                event.mAvailablePccCount,
                event.mAvailablePccOnlyCount,
                event.mSelectedDatasetPickedReason,
                event.mDetectionPreference,
                event.mFieldClassificationRequestId,
                mCallingAppUid,
                event.mIsCredentialRequest,
                event.mWebviewRequestedCredential,
                event.mViewFillableTotalCount,
                event.mViewFillFailureCount,
                event.mFocusedId,
                event.mViewFillSuccessCount,
                event.mViewFilledButUnexpectedCount,
                event.mSelectionTimestamp,
                event.mAutofilledTimestampMs,
                event.mFieldModifiedFirstTimestampMs,
                event.mFieldModifiedLastTimestampMs,
                event.mSuggestionPresentedLastTimestampMs,
                event.mFocusedVirtualAutofillId,
                event.mFieldFirstLength,
                event.mFieldLastLength);
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
        int mAutofillServiceUid = DEFAULT_VALUE_INT;
        int mInlineSuggestionHostUid = DEFAULT_VALUE_INT;
        boolean mIsRequestTriggered;
        int mFillRequestSentTimestampMs = DEFAULT_VALUE_INT;
        int mFillResponseReceivedTimestampMs = DEFAULT_VALUE_INT;
        int mSuggestionSentTimestampMs = DEFAULT_VALUE_INT;
        int mSuggestionPresentedTimestampMs = DEFAULT_VALUE_INT;
        int mSelectedDatasetId = DEFAULT_VALUE_INT;
        boolean mDialogDismissed = false;
        boolean mNegativeCtaButtonClicked = false;
        boolean mPositiveCtaButtonClicked = false;
        int mAuthenticationType = AUTHENTICATION_TYPE_UNKNOWN;
        int mAuthenticationResult = AUTHENTICATION_RESULT_UNKNOWN;
        int mLatencyAuthenticationUiDisplayMillis = DEFAULT_VALUE_INT;
        int mLatencyDatasetDisplayMillis = DEFAULT_VALUE_INT;
        int mAvailablePccCount = DEFAULT_VALUE_INT;
        int mAvailablePccOnlyCount = DEFAULT_VALUE_INT;
        @DatasetPickedReason int mSelectedDatasetPickedReason = PICK_REASON_UNKNOWN;
        @DetectionPreference int mDetectionPreference = DETECTION_PREFER_UNKNOWN;
        int mFieldClassificationRequestId = DEFAULT_VALUE_INT;
        boolean mIsCredentialRequest = false;
        boolean mWebviewRequestedCredential = false;
        int mViewFillableTotalCount = DEFAULT_VALUE_INT;
        int mViewFillFailureCount = DEFAULT_VALUE_INT;
        int mFocusedId = DEFAULT_VALUE_INT;
        int mSelectionTimestamp = DEFAULT_VALUE_INT;
        int mAutofilledTimestampMs = DEFAULT_VALUE_INT;
        int mFieldModifiedFirstTimestampMs = DEFAULT_VALUE_INT;
        int mFieldModifiedLastTimestampMs = DEFAULT_VALUE_INT;
        int mSuggestionPresentedLastTimestampMs = DEFAULT_VALUE_INT;
        int mFocusedVirtualAutofillId = DEFAULT_VALUE_INT;
        int mFieldFirstLength = DEFAULT_VALUE_INT;
        int mFieldLastLength = DEFAULT_VALUE_INT;

        // Default value for success count is set to 0 explicitly. Setting it to -1 for
        // uninitialized doesn't help much, as this would be non-zero only if callback is received.
        int mViewFillSuccessCount = 0;
        int mViewFilledButUnexpectedCount = 0;

        ArraySet<AutofillId> mAutofillIdsAttemptedAutofill;
        ArraySet<AutofillId> mAlreadyFilledAutofillIds = new ArraySet<>();
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
