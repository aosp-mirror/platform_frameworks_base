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

import static android.service.autofill.Dataset.PICK_REASON_PCC_DETECTION_ONLY;
import static android.service.autofill.Dataset.PICK_REASON_PCC_DETECTION_PREFERRED_WITH_PROVIDER;

import static com.android.internal.util.FrameworkStatsLog.AUTOFILL_FILL_RESPONSE_REPORTED;
import static com.android.internal.util.FrameworkStatsLog.AUTOFILL_FILL_RESPONSE_REPORTED__AUTHENTICATION_RESULT__AUTHENTICATION_FAILURE;
import static com.android.internal.util.FrameworkStatsLog.AUTOFILL_FILL_RESPONSE_REPORTED__AUTHENTICATION_RESULT__AUTHENTICATION_RESULT_UNKNOWN;
import static com.android.internal.util.FrameworkStatsLog.AUTOFILL_FILL_RESPONSE_REPORTED__AUTHENTICATION_RESULT__AUTHENTICATION_SUCCESS;
import static com.android.internal.util.FrameworkStatsLog.AUTOFILL_FILL_RESPONSE_REPORTED__AUTHENTICATION_TYPE__AUTHENTICATION_TYPE_UNKNOWN;
import static com.android.internal.util.FrameworkStatsLog.AUTOFILL_FILL_RESPONSE_REPORTED__AUTHENTICATION_TYPE__DATASET_AUTHENTICATION;
import static com.android.internal.util.FrameworkStatsLog.AUTOFILL_FILL_RESPONSE_REPORTED__AUTHENTICATION_TYPE__FULL_AUTHENTICATION;
import static com.android.internal.util.FrameworkStatsLog.AUTOFILL_FILL_RESPONSE_REPORTED__DETECTION_PREFERENCE__DETECTION_PREFER_AUTOFILL_PROVIDER;
import static com.android.internal.util.FrameworkStatsLog.AUTOFILL_FILL_RESPONSE_REPORTED__DETECTION_PREFERENCE__DETECTION_PREFER_PCC;
import static com.android.internal.util.FrameworkStatsLog.AUTOFILL_FILL_RESPONSE_REPORTED__DETECTION_PREFERENCE__DETECTION_PREFER_UNKONWN;
import static com.android.internal.util.FrameworkStatsLog.AUTOFILL_FILL_RESPONSE_REPORTED__DISPLAY_PRESENTATION_TYPE__DIALOG;
import static com.android.internal.util.FrameworkStatsLog.AUTOFILL_FILL_RESPONSE_REPORTED__DISPLAY_PRESENTATION_TYPE__INLINE;
import static com.android.internal.util.FrameworkStatsLog.AUTOFILL_FILL_RESPONSE_REPORTED__DISPLAY_PRESENTATION_TYPE__MENU;
import static com.android.internal.util.FrameworkStatsLog.AUTOFILL_FILL_RESPONSE_REPORTED__DISPLAY_PRESENTATION_TYPE__UNKNOWN_AUTOFILL_DISPLAY_PRESENTATION_TYPE;
import static com.android.internal.util.FrameworkStatsLog.AUTOFILL_FILL_RESPONSE_REPORTED__RESPONSE_STATUS__RESPONSE_STATUS_CANCELLED;
import static com.android.internal.util.FrameworkStatsLog.AUTOFILL_FILL_RESPONSE_REPORTED__RESPONSE_STATUS__RESPONSE_STATUS_FAILURE;
import static com.android.internal.util.FrameworkStatsLog.AUTOFILL_FILL_RESPONSE_REPORTED__RESPONSE_STATUS__RESPONSE_STATUS_SESSION_DESTROYED;
import static com.android.internal.util.FrameworkStatsLog.AUTOFILL_FILL_RESPONSE_REPORTED__RESPONSE_STATUS__RESPONSE_STATUS_TRANSACTION_TOO_LARGE;
import static com.android.internal.util.FrameworkStatsLog.AUTOFILL_FILL_RESPONSE_REPORTED__RESPONSE_STATUS__RESPONSE_STATUS_SUCCESS;
import static com.android.internal.util.FrameworkStatsLog.AUTOFILL_FILL_RESPONSE_REPORTED__RESPONSE_STATUS__RESPONSE_STATUS_TIMEOUT;
import static com.android.internal.util.FrameworkStatsLog.AUTOFILL_FILL_RESPONSE_REPORTED__RESPONSE_STATUS__RESPONSE_STATUS_UNKNOWN;
import static com.android.server.autofill.Helper.sVerbose;

import android.annotation.IntDef;
import android.annotation.Nullable;
import android.os.SystemClock;
import android.service.autofill.Dataset;
import android.util.Slog;
import android.view.autofill.AutofillId;

import com.android.internal.util.FrameworkStatsLog;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.List;
import java.util.Optional;

/**
 * Helper class to log Autofill FillResponse stats.
 */
public final class FillResponseEventLogger {
  private static final String TAG = "FillResponseEventLogger";

  private static final long UNINITIALIZED_TIMESTAMP = -1;
  private long startResponseProcessingTimestamp = UNINITIALIZED_TIMESTAMP;

  /**
   * Reasons why presentation was not shown. These are wrappers around
   * {@link com.android.os.AtomsProto.AutofillFillRequestReported.RequestTriggerReason}.
   */
  @IntDef(prefix = {"DISPLAY_PRESENTATION_TYPE"}, value = {
      DISPLAY_PRESENTATION_TYPE_UNKNOWN,
      DISPLAY_PRESENTATION_TYPE_MENU,
      DISPLAY_PRESENTATION_TYPE_INLINE,
      DISPLAY_PRESENTATION_TYPE_DIALOG
  })
  @Retention(RetentionPolicy.SOURCE)
  public @interface DisplayPresentationType {
  }

  /**
   * Reasons why presentation was not shown. These are wrappers around
   * {@link com.android.os.AtomsProto.AutofillFillResponseReported.AuthenticationType}.
   */
  @IntDef(prefix = {"AUTHENTICATION_TYPE"}, value = {
      AUTHENTICATION_TYPE_UNKNOWN,
      AUTHENTICATION_TYPE_DATASET_AHTHENTICATION,
      AUTHENTICATION_TYPE_FULL_AHTHENTICATION
  })
  @Retention(RetentionPolicy.SOURCE)
  public @interface AuthenticationType {
  }

  /**
   * Reasons why presentation was not shown. These are wrappers around
   * {@link com.android.os.AtomsProto.AutofillFillResponseReported.FillResponseStatus}.
   */
  @IntDef(prefix = {"RESPONSE_STATUS"}, value = {
      RESPONSE_STATUS_UNKNOWN,
      RESPONSE_STATUS_FAILURE,
      RESPONSE_STATUS_SUCCESS,
      RESPONSE_STATUS_CANCELLED,
      RESPONSE_STATUS_TIMEOUT,
      RESPONSE_STATUS_SESSION_DESTROYED
  })
  @Retention(RetentionPolicy.SOURCE)
  public @interface ResponseStatus {
  }


  /**
   * Reasons why presentation was not shown. These are wrappers around
   * {@link com.android.os.AtomsProto.AutofillFillResponseReported.AuthenticationResult}.
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
   * Reasons why presentation was not shown. These are wrappers around
   * {@link com.android.os.AtomsProto.AutofillFillResponseReported.DetectionPreference}.
   */
  @IntDef(prefix = {"DETECTION_PREFER"}, value = {
      DETECTION_PREFER_UNKNOWN,
      DETECTION_PREFER_AUTOFILL_PROVIDER,
      DETECTION_PREFER_PCC
  })
  @Retention(RetentionPolicy.SOURCE)
  public @interface DetectionPreference {
  }

  public static final int DISPLAY_PRESENTATION_TYPE_UNKNOWN =
      AUTOFILL_FILL_RESPONSE_REPORTED__DISPLAY_PRESENTATION_TYPE__UNKNOWN_AUTOFILL_DISPLAY_PRESENTATION_TYPE;
  public static final int DISPLAY_PRESENTATION_TYPE_MENU =
      AUTOFILL_FILL_RESPONSE_REPORTED__DISPLAY_PRESENTATION_TYPE__MENU;
  public static final int DISPLAY_PRESENTATION_TYPE_INLINE =
      AUTOFILL_FILL_RESPONSE_REPORTED__DISPLAY_PRESENTATION_TYPE__INLINE;
  public static final int DISPLAY_PRESENTATION_TYPE_DIALOG =
      AUTOFILL_FILL_RESPONSE_REPORTED__DISPLAY_PRESENTATION_TYPE__DIALOG;
  public static final int AUTHENTICATION_TYPE_UNKNOWN =
      AUTOFILL_FILL_RESPONSE_REPORTED__AUTHENTICATION_TYPE__AUTHENTICATION_TYPE_UNKNOWN;
  public static final int AUTHENTICATION_TYPE_DATASET_AHTHENTICATION =
      AUTOFILL_FILL_RESPONSE_REPORTED__AUTHENTICATION_TYPE__DATASET_AUTHENTICATION;
  public static final int AUTHENTICATION_TYPE_FULL_AHTHENTICATION =
      AUTOFILL_FILL_RESPONSE_REPORTED__AUTHENTICATION_TYPE__FULL_AUTHENTICATION;

  public static final int AUTHENTICATION_RESULT_UNKNOWN =
      AUTOFILL_FILL_RESPONSE_REPORTED__AUTHENTICATION_RESULT__AUTHENTICATION_RESULT_UNKNOWN;
  public static final int AUTHENTICATION_RESULT_SUCCESS =
      AUTOFILL_FILL_RESPONSE_REPORTED__AUTHENTICATION_RESULT__AUTHENTICATION_SUCCESS;
  public static final int AUTHENTICATION_RESULT_FAILURE =
      AUTOFILL_FILL_RESPONSE_REPORTED__AUTHENTICATION_RESULT__AUTHENTICATION_FAILURE;
  public static final int RESPONSE_STATUS_TIMEOUT =
      AUTOFILL_FILL_RESPONSE_REPORTED__RESPONSE_STATUS__RESPONSE_STATUS_TIMEOUT;
  public static final int RESPONSE_STATUS_CANCELLED =
      AUTOFILL_FILL_RESPONSE_REPORTED__RESPONSE_STATUS__RESPONSE_STATUS_CANCELLED;
  public static final int RESPONSE_STATUS_FAILURE =
      AUTOFILL_FILL_RESPONSE_REPORTED__RESPONSE_STATUS__RESPONSE_STATUS_FAILURE;
  public static final int RESPONSE_STATUS_TRANSACTION_TOO_LARGE =
      AUTOFILL_FILL_RESPONSE_REPORTED__RESPONSE_STATUS__RESPONSE_STATUS_TRANSACTION_TOO_LARGE;
  public static final int RESPONSE_STATUS_SESSION_DESTROYED =
      AUTOFILL_FILL_RESPONSE_REPORTED__RESPONSE_STATUS__RESPONSE_STATUS_SESSION_DESTROYED;
  public static final int RESPONSE_STATUS_SUCCESS =
      AUTOFILL_FILL_RESPONSE_REPORTED__RESPONSE_STATUS__RESPONSE_STATUS_SUCCESS;
  public static final int RESPONSE_STATUS_UNKNOWN =
      AUTOFILL_FILL_RESPONSE_REPORTED__RESPONSE_STATUS__RESPONSE_STATUS_UNKNOWN;

  // Values for AutofillFillResponseReported.detection_preference
  public static final int DETECTION_PREFER_UNKNOWN =
          AUTOFILL_FILL_RESPONSE_REPORTED__DETECTION_PREFERENCE__DETECTION_PREFER_UNKONWN;
  public static final int DETECTION_PREFER_AUTOFILL_PROVIDER =
          AUTOFILL_FILL_RESPONSE_REPORTED__DETECTION_PREFERENCE__DETECTION_PREFER_AUTOFILL_PROVIDER;
  public static final int DETECTION_PREFER_PCC =
          AUTOFILL_FILL_RESPONSE_REPORTED__DETECTION_PREFERENCE__DETECTION_PREFER_PCC;


  // Log a magic number when FillRequest failed or timeout to differentiate with FillRequest
  // succeeded.
  public static final int AVAILABLE_COUNT_WHEN_FILL_REQUEST_FAILED_OR_TIMEOUT = -1;

  // Log a magic number to indicate that the FillResponse contains a saveTriggerId.
  public static final int HAVE_SAVE_TRIGGER_ID = 1;

  private final int mSessionId;
  private Optional<FillResponseEventInternal> mEventInternal;

  private FillResponseEventLogger(int sessionId) {
    mSessionId = sessionId;
    mEventInternal = Optional.empty();
  }

  /**
   * A factory constructor to create FillResponseEventLogger.
   */
  public static FillResponseEventLogger forSessionId(int sessionId) {
    return new FillResponseEventLogger(sessionId);
  }

  /**
   * Reset mEventInternal before logging for a new response. It shall be called
   * for each FillResponse.
   */
  public void startLogForNewResponse() {
    if (!mEventInternal.isEmpty()) {
      Slog.w(TAG, "FillResponseEventLogger is not empty before starting " +
          "for a new request");
    }
    mEventInternal = Optional.of(new FillResponseEventInternal());
  }

  /**
   * Set request_id as long as mEventInternal presents.
   */
  public void maybeSetRequestId(int val) {
    mEventInternal.ifPresent(event -> event.mRequestId = val);
  }

  /**
   * Set app_package_uid as long as mEventInternal presents.
   */
  public void maybeSetAppPackageUid(int val) {
    mEventInternal.ifPresent(event -> {
      event.mAppPackageUid = val;
    });
  }

  /**
   * Set display_presentation_type as long as mEventInternal presents.
   */
  public void maybeSetDisplayPresentationType(@DisplayPresentationType int val) {
    mEventInternal.ifPresent(event -> {
      event.mDisplayPresentationType = val;
    });
  }

  /**
   * Set available_count as long as mEventInternal presents.
   * For cases of FillRequest failed and timeout, set to -1.
   */
  public void maybeSetAvailableCount(@Nullable List<Dataset> datasetList,
      AutofillId currentViewId) {
    mEventInternal.ifPresent(event -> {
      int availableCount = getDatasetCountForAutofillId(datasetList, currentViewId);
      event.mAvailableCount = availableCount;
    });
  }

  public void maybeSetAvailableCount(int val) {
    mEventInternal.ifPresent(event -> {
      event.mAvailableCount = val;
    });
  }

  public void maybeSetTotalDatasetsProvided(int val) {
    mEventInternal.ifPresent(event -> {
      // Don't reset if it's already populated.
      // This is just a technical limitation of not having complicated logic.
      // Autofill Provider may return some datasets which are applicable to data types.
      // In such a case, we set available count to the number of datasets provided.
      // However, it's possible that those data types aren't detected by PCC, so in effect, there
      // are 0 datasets. In the codebase, we treat it as null response, which may call this again
      // to set 0. But we don't want to overwrite already set value.
      if (event.mTotalDatasetsProvided == -1) {
        event.mTotalDatasetsProvided = val;
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

  /**
   * Set save_ui_trigger_ids as long as mEventInternal presents.
   */
  public void maybeSetSaveUiTriggerIds(int val) {
    mEventInternal.ifPresent(event -> {
      event.mSaveUiTriggerIds = val;
    });
  }

  /**
   * Set latency_fill_response_received_millis as long as mEventInternal presents.
   */
  public void maybeSetLatencyFillResponseReceivedMillis(int val) {
    mEventInternal.ifPresent(event -> {
      event.mLatencyFillResponseReceivedMillis = val;
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
   * Set authentication_failure_reason as long as mEventInternal presents.
   */
  public void maybeSetAuthenticationFailureReason(int val) {
    mEventInternal.ifPresent(event -> {
      event.mAuthenticationFailureReason = val;
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

  /**
   * Set response_status as long as mEventInternal presents.
   */
  public void maybeSetResponseStatus(@ResponseStatus int val) {
    mEventInternal.ifPresent(event -> {
      event.mResponseStatus = val;
    });
  }

  public void startResponseProcessingTime() {
    startResponseProcessingTimestamp = SystemClock.elapsedRealtime();
  }

  /**
   * Set latency_response_processing_millis as long as mEventInternal presents.
   */
  public void maybeSetLatencyResponseProcessingMillis() {
    mEventInternal.ifPresent(event -> {
      if (startResponseProcessingTimestamp == UNINITIALIZED_TIMESTAMP && sVerbose) {
        Slog.v(TAG, "uninitialized startResponseProcessingTimestamp");
      }
      event.mLatencyResponseProcessingMillis
              = SystemClock.elapsedRealtime() - startResponseProcessingTimestamp;
    });
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
   * Set available_pcc_count.
   */
  public void maybeSetDatasetsCountAfterPotentialPccFiltering(@Nullable List<Dataset> datasetList) {
    mEventInternal.ifPresent(event -> {
      int pccOnlyCount = 0;
      int pccCount = 0;
      int totalCount = 0;
      if (datasetList != null) {
        totalCount = datasetList.size();
        for (int i = 0; i < datasetList.size(); i++) {
          Dataset dataset = datasetList.get(i);
          if (dataset != null) {
            if (dataset.getEligibleReason() == PICK_REASON_PCC_DETECTION_ONLY) {
              pccOnlyCount++;
              pccCount++;
            } else if (dataset.getEligibleReason()
                    == PICK_REASON_PCC_DETECTION_PREFERRED_WITH_PROVIDER) {
              pccCount++;
            }
          }
        }
      }
      event.mAvailablePccOnlyCount = pccOnlyCount;
      event.mAvailablePccCount = pccCount;
      event.mAvailableCount = totalCount;
    });
  }

  /**
   * Set detection_pref
   */
  public void maybeSetDetectionPreference(@DetectionPreference int detectionPreference) {
    mEventInternal.ifPresent(event -> {
      event.mDetectionPref = detectionPreference;
    });
  }

  /**
   * Log an AUTOFILL_FILL_RESPONSE_REPORTED event.
   */
  public void logAndEndEvent() {
    if (!mEventInternal.isPresent()) {
      Slog.w(TAG, "Shouldn't be logging AutofillFillRequestReported again for same "
          + "event");
      return;
    }
    FillResponseEventInternal event = mEventInternal.get();
    if (sVerbose) {
      Slog.v(TAG, "Log AutofillFillResponseReported:"
          + " requestId=" + event.mRequestId
          + " sessionId=" + mSessionId
          + " mAppPackageUid=" + event.mAppPackageUid
          + " mDisplayPresentationType=" + event.mDisplayPresentationType
          + " mAvailableCount=" + event.mAvailableCount
          + " mSaveUiTriggerIds=" + event.mSaveUiTriggerIds
          + " mLatencyFillResponseReceivedMillis=" + event.mLatencyFillResponseReceivedMillis
          + " mAuthenticationType=" + event.mAuthenticationType
          + " mAuthenticationResult=" + event.mAuthenticationResult
          + " mAuthenticationFailureReason=" + event.mAuthenticationFailureReason
          + " mLatencyAuthenticationUiDisplayMillis=" + event.mLatencyAuthenticationUiDisplayMillis
          + " mLatencyDatasetDisplayMillis=" + event.mLatencyDatasetDisplayMillis
          + " mResponseStatus=" + event.mResponseStatus
          + " mLatencyResponseProcessingMillis=" + event.mLatencyResponseProcessingMillis
          + " mAvailablePccCount=" + event.mAvailablePccCount
          + " mAvailablePccOnlyCount=" + event.mAvailablePccOnlyCount
          + " mTotalDatasetsProvided=" + event.mTotalDatasetsProvided
          + " mDetectionPref=" + event.mDetectionPref);
    }
    FrameworkStatsLog.write(
        AUTOFILL_FILL_RESPONSE_REPORTED,
        event.mRequestId,
        mSessionId,
        event.mAppPackageUid,
        event.mDisplayPresentationType,
        event.mAvailableCount,
        event.mSaveUiTriggerIds,
        event.mLatencyFillResponseReceivedMillis,
        event.mAuthenticationType,
        event.mAuthenticationResult,
        event.mAuthenticationFailureReason,
        event.mLatencyAuthenticationUiDisplayMillis,
        event.mLatencyDatasetDisplayMillis,
        event.mResponseStatus,
        event.mLatencyResponseProcessingMillis,
        event.mAvailablePccCount,
        event.mAvailablePccOnlyCount,
        event.mTotalDatasetsProvided,
        event.mDetectionPref);
    mEventInternal = Optional.empty();
  }

  private static final class FillResponseEventInternal {
    int mRequestId = -1;
    int mAppPackageUid = -1;
    int mDisplayPresentationType = DISPLAY_PRESENTATION_TYPE_UNKNOWN;
    int mAvailableCount = 0;
    int mSaveUiTriggerIds = -1;
    int mLatencyFillResponseReceivedMillis = (int) UNINITIALIZED_TIMESTAMP;
    int mAuthenticationType = AUTHENTICATION_TYPE_UNKNOWN;
    int mAuthenticationResult = AUTHENTICATION_RESULT_UNKNOWN;
    int mAuthenticationFailureReason = -1;
    int mLatencyAuthenticationUiDisplayMillis = (int) UNINITIALIZED_TIMESTAMP;
    int mLatencyDatasetDisplayMillis = (int) UNINITIALIZED_TIMESTAMP;
    int mResponseStatus = RESPONSE_STATUS_UNKNOWN;
    long mLatencyResponseProcessingMillis = UNINITIALIZED_TIMESTAMP;
    int mAvailablePccCount = -1;
    int mAvailablePccOnlyCount = -1;
    int mTotalDatasetsProvided = -1;
    @DetectionPreference
    int mDetectionPref = DETECTION_PREFER_UNKNOWN;

    FillResponseEventInternal() {
    }
  }
}
