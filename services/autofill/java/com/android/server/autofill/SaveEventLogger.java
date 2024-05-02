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

import static com.android.internal.util.FrameworkStatsLog.AUTOFILL_SAVE_EVENT_REPORTED;
import static com.android.internal.util.FrameworkStatsLog.AUTOFILL_SAVE_EVENT_REPORTED__SAVE_UI_NOT_SHOWN_REASON__NO_SAVE_REASON_DATASET_MATCH;
import static com.android.internal.util.FrameworkStatsLog.AUTOFILL_SAVE_EVENT_REPORTED__SAVE_UI_NOT_SHOWN_REASON__NO_SAVE_REASON_FIELD_VALIDATION_FAILED;
import static com.android.internal.util.FrameworkStatsLog.AUTOFILL_SAVE_EVENT_REPORTED__SAVE_UI_NOT_SHOWN_REASON__NO_SAVE_REASON_HAS_EMPTY_REQUIRED;
import static com.android.internal.util.FrameworkStatsLog.AUTOFILL_SAVE_EVENT_REPORTED__SAVE_UI_NOT_SHOWN_REASON__NO_SAVE_REASON_NONE;
import static com.android.internal.util.FrameworkStatsLog.AUTOFILL_SAVE_EVENT_REPORTED__SAVE_UI_NOT_SHOWN_REASON__NO_SAVE_REASON_NO_SAVE_INFO;
import static com.android.internal.util.FrameworkStatsLog.AUTOFILL_SAVE_EVENT_REPORTED__SAVE_UI_NOT_SHOWN_REASON__NO_SAVE_REASON_NO_VALUE_CHANGED;
import static com.android.internal.util.FrameworkStatsLog.AUTOFILL_SAVE_EVENT_REPORTED__SAVE_UI_NOT_SHOWN_REASON__NO_SAVE_REASON_SESSION_DESTROYED;
import static com.android.internal.util.FrameworkStatsLog.AUTOFILL_SAVE_EVENT_REPORTED__SAVE_UI_NOT_SHOWN_REASON__NO_SAVE_REASON_UNKNOWN;
import static com.android.internal.util.FrameworkStatsLog.AUTOFILL_SAVE_EVENT_REPORTED__SAVE_UI_NOT_SHOWN_REASON__NO_SAVE_REASON_WITH_DELAY_SAVE_FLAG;
import static com.android.internal.util.FrameworkStatsLog.AUTOFILL_SAVE_EVENT_REPORTED__SAVE_UI_NOT_SHOWN_REASON__NO_SAVE_REASON_WITH_DONT_SAVE_ON_FINISH_FLAG;
import static com.android.internal.util.FrameworkStatsLog.AUTOFILL_SAVE_EVENT_REPORTED__SAVE_UI_SHOWN_REASON__SAVE_UI_SHOWN_REASON_OPTIONAL_ID_CHANGE;
import static com.android.internal.util.FrameworkStatsLog.AUTOFILL_SAVE_EVENT_REPORTED__SAVE_UI_SHOWN_REASON__SAVE_UI_SHOWN_REASON_REQUIRED_ID_CHANGE;
import static com.android.internal.util.FrameworkStatsLog.AUTOFILL_SAVE_EVENT_REPORTED__SAVE_UI_SHOWN_REASON__SAVE_UI_SHOWN_REASON_TRIGGER_ID_SET;
import static com.android.internal.util.FrameworkStatsLog.AUTOFILL_SAVE_EVENT_REPORTED__SAVE_UI_SHOWN_REASON__SAVE_UI_SHOWN_REASON_UNKNOWN;
import static com.android.server.autofill.Helper.sVerbose;

import android.annotation.IntDef;
import android.os.SystemClock;
import android.util.Slog;

import com.android.internal.util.FrameworkStatsLog;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Optional;

/**
 * Helper class to log Autofill Save event stats.
 */
public class SaveEventLogger {
  private static final String TAG = "SaveEventLogger";

  /**
   * Reasons why presentation was not shown. These are wrappers around
   * {@link com.android.os.AtomsProto.AutofillSaveEventReported.SaveUiShownReason}.
   */
  @IntDef(prefix = {"SAVE_UI_SHOWN_REASON"}, value = {
      SAVE_UI_SHOWN_REASON_UNKNOWN,
      SAVE_UI_SHOWN_REASON_REQUIRED_ID_CHANGE,
      SAVE_UI_SHOWN_REASON_OPTIONAL_ID_CHANGE,
      SAVE_UI_SHOWN_REASON_TRIGGER_ID_SET
  })
  @Retention(RetentionPolicy.SOURCE)
  public @interface SaveUiShownReason {
  }

  /**
   * Reasons why presentation was not shown. These are wrappers around
   * {@link com.android.os.AtomsProto.AutofillSaveEventReported.SaveUiNotShownReason}.
   */
  @IntDef(prefix = {"SAVE_UI_NOT_SHOWN_REASON"}, value = {
      NO_SAVE_REASON_UNKNOWN,
      NO_SAVE_REASON_NONE,
      NO_SAVE_REASON_NO_SAVE_INFO,
      NO_SAVE_REASON_WITH_DELAY_SAVE_FLAG,
      NO_SAVE_REASON_WITH_DONT_SAVE_ON_FINISH_FLAG,
      NO_SAVE_REASON_HAS_EMPTY_REQUIRED,
      NO_SAVE_REASON_NO_VALUE_CHANGED,
      NO_SAVE_REASON_FIELD_VALIDATION_FAILED,
      NO_SAVE_REASON_DATASET_MATCH,
      NO_SAVE_REASON_SESSION_DESTROYED
  })
  @Retention(RetentionPolicy.SOURCE)
  public @interface SaveUiNotShownReason {
  }

  public static final int SAVE_UI_SHOWN_REASON_UNKNOWN =
      AUTOFILL_SAVE_EVENT_REPORTED__SAVE_UI_SHOWN_REASON__SAVE_UI_SHOWN_REASON_UNKNOWN;
  public static final int SAVE_UI_SHOWN_REASON_REQUIRED_ID_CHANGE =
      AUTOFILL_SAVE_EVENT_REPORTED__SAVE_UI_SHOWN_REASON__SAVE_UI_SHOWN_REASON_REQUIRED_ID_CHANGE;
  public static final int SAVE_UI_SHOWN_REASON_OPTIONAL_ID_CHANGE =
      AUTOFILL_SAVE_EVENT_REPORTED__SAVE_UI_SHOWN_REASON__SAVE_UI_SHOWN_REASON_OPTIONAL_ID_CHANGE;
  public static final int SAVE_UI_SHOWN_REASON_TRIGGER_ID_SET =
      AUTOFILL_SAVE_EVENT_REPORTED__SAVE_UI_SHOWN_REASON__SAVE_UI_SHOWN_REASON_TRIGGER_ID_SET;

  public static final int NO_SAVE_REASON_UNKNOWN =
      AUTOFILL_SAVE_EVENT_REPORTED__SAVE_UI_NOT_SHOWN_REASON__NO_SAVE_REASON_UNKNOWN;
  public static final int NO_SAVE_REASON_NONE =
      AUTOFILL_SAVE_EVENT_REPORTED__SAVE_UI_NOT_SHOWN_REASON__NO_SAVE_REASON_NONE;
  public static final int NO_SAVE_REASON_NO_SAVE_INFO =
      AUTOFILL_SAVE_EVENT_REPORTED__SAVE_UI_NOT_SHOWN_REASON__NO_SAVE_REASON_NO_SAVE_INFO;
  public static final int NO_SAVE_REASON_WITH_DELAY_SAVE_FLAG =
      AUTOFILL_SAVE_EVENT_REPORTED__SAVE_UI_NOT_SHOWN_REASON__NO_SAVE_REASON_WITH_DELAY_SAVE_FLAG;
  public static final int NO_SAVE_REASON_HAS_EMPTY_REQUIRED =
      AUTOFILL_SAVE_EVENT_REPORTED__SAVE_UI_NOT_SHOWN_REASON__NO_SAVE_REASON_HAS_EMPTY_REQUIRED;
  public static final int NO_SAVE_REASON_NO_VALUE_CHANGED =
      AUTOFILL_SAVE_EVENT_REPORTED__SAVE_UI_NOT_SHOWN_REASON__NO_SAVE_REASON_NO_VALUE_CHANGED;
  public static final int NO_SAVE_REASON_FIELD_VALIDATION_FAILED =
      AUTOFILL_SAVE_EVENT_REPORTED__SAVE_UI_NOT_SHOWN_REASON__NO_SAVE_REASON_FIELD_VALIDATION_FAILED;
  public static final int NO_SAVE_REASON_DATASET_MATCH =
      AUTOFILL_SAVE_EVENT_REPORTED__SAVE_UI_NOT_SHOWN_REASON__NO_SAVE_REASON_DATASET_MATCH;
  public static final int NO_SAVE_REASON_SESSION_DESTROYED =
      AUTOFILL_SAVE_EVENT_REPORTED__SAVE_UI_NOT_SHOWN_REASON__NO_SAVE_REASON_SESSION_DESTROYED;
  public static final int NO_SAVE_REASON_WITH_DONT_SAVE_ON_FINISH_FLAG =
      AUTOFILL_SAVE_EVENT_REPORTED__SAVE_UI_NOT_SHOWN_REASON__NO_SAVE_REASON_WITH_DONT_SAVE_ON_FINISH_FLAG;

  public static final long UNINITIATED_TIMESTAMP = Long.MIN_VALUE;

  private final int mSessionId;
  private Optional<SaveEventInternal> mEventInternal;
  private final long mSessionStartTimestamp;

  private SaveEventLogger(int sessionId, long sessionStartTimestamp) {
      mSessionId = sessionId;
      mEventInternal = Optional.of(new SaveEventInternal());
      mSessionStartTimestamp = sessionStartTimestamp;
  }

  /** A factory constructor to create FillRequestEventLogger. */
  public static SaveEventLogger forSessionId(int sessionId, long sessionStartTimestamp) {
        return new SaveEventLogger(sessionId, sessionStartTimestamp);
  }

  /**
   * Set request_id as long as mEventInternal presents.
   */
  public void maybeSetRequestId(int requestId) {
    mEventInternal.ifPresent(event -> event.mRequestId = requestId);
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
   * Set save_ui_trigger_ids as long as mEventInternal presents.
   */
  public void maybeSetSaveUiTriggerIds(int val) {
    mEventInternal.ifPresent(event -> {
      event.mSaveUiTriggerIds = val;
    });
  }

  /**
   * Set flag as long as mEventInternal presents.
   */
  public void maybeSetFlag(int val) {
    mEventInternal.ifPresent(event -> {
      event.mFlag = val;
    });
  }

  /**
   * Set is_new_field as long as mEventInternal presents.
   */
  public void maybeSetIsNewField(boolean val) {
    mEventInternal.ifPresent(event -> {
      event.mIsNewField = val;
    });
  }

  /**
   * Set save_ui_shown_reason as long as mEventInternal presents.
   */
  public void maybeSetSaveUiShownReason(@SaveUiShownReason int reason) {
    mEventInternal.ifPresent(event -> {
      event.mSaveUiShownReason = reason;
    });
  }

  /**
   * Set save_ui_not_shown_reason as long as mEventInternal presents.
   */
  public void maybeSetSaveUiNotShownReason(@SaveUiNotShownReason int reason) {
    mEventInternal.ifPresent(event -> {
      event.mSaveUiNotShownReason = reason;
    });
  }

  /**
   * Set save_button_clicked as long as mEventInternal presents.
   */
  public void maybeSetSaveButtonClicked(boolean val) {
    mEventInternal.ifPresent(event -> {
      event.mSaveButtonClicked = val;
    });
  }

  /**
   * Set cancel_button_clicked as long as mEventInternal presents.
   */
  public void maybeSetCancelButtonClicked(boolean val) {
    mEventInternal.ifPresent(event -> {
      event.mCancelButtonClicked = val;
    });
  }

  /**
   * Set dialog_dismissed as long as mEventInternal presents.
   */
  public void maybeSetDialogDismissed(boolean val) {
    mEventInternal.ifPresent(event -> {
      event.mDialogDismissed = val;
    });
  }

  /**
   * Set is_saved as long as mEventInternal presents.
   */
  public void maybeSetIsSaved(boolean val) {
    mEventInternal.ifPresent(event -> {
      event.mIsSaved = val;
    });
  }

  /**
   * Returns timestamp (relative to mSessionStartTimestamp)
   */
  private long getElapsedTime() {
    return SystemClock.elapsedRealtime() - mSessionStartTimestamp;
  }

  /**
   * Set latency_save_ui_display_millis as long as mEventInternal presents.
   */
  public void maybeSetLatencySaveUiDisplayMillis(long timestamp) {
    mEventInternal.ifPresent(event -> {
      event.mLatencySaveUiDisplayMillis = timestamp;
    });
  }

  /** Set latency_save_ui_display_millis as long as mEventInternal presents. */
  public void maybeSetLatencySaveUiDisplayMillis() {
    maybeSetLatencySaveUiDisplayMillis(getElapsedTime());
  }

  /**
   * Set latency_save_request_millis as long as mEventInternal presents.
   */
  public void maybeSetLatencySaveRequestMillis(long timestamp) {
    mEventInternal.ifPresent(event -> {
      event.mLatencySaveRequestMillis = timestamp;
    });
  }

  /** Set latency_save_request_millis as long as mEventInternal presents. */
  public void maybeSetLatencySaveRequestMillis() {
    maybeSetLatencySaveRequestMillis(getElapsedTime());
  }

  /**
   * Set latency_save_finish_millis as long as mEventInternal presents.
   */
  public void maybeSetLatencySaveFinishMillis(long timestamp) {
    mEventInternal.ifPresent(event -> {
      event.mLatencySaveFinishMillis = timestamp;
    });
  }

  /** Set latency_save_finish_millis as long as mEventInternal presents. */
  public void maybeSetLatencySaveFinishMillis() {
    maybeSetLatencySaveFinishMillis(getElapsedTime());
  }

  /**
   * Set is_framework_created_save_info as long as mEventInternal presents.
   */
  public void maybeSetIsFrameworkCreatedSaveInfo(boolean val) {
    mEventInternal.ifPresent(event -> {
      event.mIsFrameworkCreatedSaveInfo = val;
    });
  }

  /**
   * Log an AUTOFILL_SAVE_EVENT_REPORTED event.
   */
  public void logAndEndEvent() {
    if (!mEventInternal.isPresent()) {
      Slog.w(TAG, "Shouldn't be logging AutofillSaveEventReported again for same "
          + "event");
      return;
    }
    SaveEventInternal event = mEventInternal.get();
    if (sVerbose) {
      Slog.v(TAG, "Log AutofillSaveEventReported:"
          + " requestId=" + event.mRequestId
          + " sessionId=" + mSessionId
          + " mAppPackageUid=" + event.mAppPackageUid
          + " mSaveUiTriggerIds=" + event.mSaveUiTriggerIds
          + " mFlag=" + event.mFlag
          + " mIsNewField=" + event.mIsNewField
          + " mSaveUiShownReason=" + event.mSaveUiShownReason
          + " mSaveUiNotShownReason=" + event.mSaveUiNotShownReason
          + " mSaveButtonClicked=" + event.mSaveButtonClicked
          + " mCancelButtonClicked=" + event.mCancelButtonClicked
          + " mDialogDismissed=" + event.mDialogDismissed
          + " mIsSaved=" + event.mIsSaved
          + " mLatencySaveUiDisplayMillis=" + event.mLatencySaveUiDisplayMillis
          + " mLatencySaveRequestMillis=" + event.mLatencySaveRequestMillis
          + " mLatencySaveFinishMillis=" + event.mLatencySaveFinishMillis
          + " mIsFrameworkCreatedSaveInfo=" + event.mIsFrameworkCreatedSaveInfo);
    }
    FrameworkStatsLog.write(
        AUTOFILL_SAVE_EVENT_REPORTED,
        event.mRequestId,
        mSessionId,
        event.mAppPackageUid,
        event.mSaveUiTriggerIds,
        event.mFlag,
        event.mIsNewField,
        event.mSaveUiShownReason,
        event.mSaveUiNotShownReason,
        event.mSaveButtonClicked,
        event.mCancelButtonClicked,
        event.mDialogDismissed,
        event.mIsSaved,
        event.mLatencySaveUiDisplayMillis,
        event.mLatencySaveRequestMillis,
        event.mLatencySaveFinishMillis,
        event.mIsFrameworkCreatedSaveInfo);
    mEventInternal = Optional.empty();
  }

  private static final class SaveEventInternal {
    int mRequestId;
    int mAppPackageUid = -1;
    int mSaveUiTriggerIds = -1;
    long mFlag = -1;
    boolean mIsNewField = false;
    int mSaveUiShownReason = SAVE_UI_SHOWN_REASON_UNKNOWN;
    int mSaveUiNotShownReason = NO_SAVE_REASON_UNKNOWN;
    boolean mSaveButtonClicked = false;
    boolean mCancelButtonClicked = false;
    boolean mDialogDismissed = false;
    boolean mIsSaved = false;
    long mLatencySaveUiDisplayMillis = UNINITIATED_TIMESTAMP;
    long mLatencySaveRequestMillis = UNINITIATED_TIMESTAMP;
    long mLatencySaveFinishMillis = UNINITIATED_TIMESTAMP;
    boolean mIsFrameworkCreatedSaveInfo = false;

    SaveEventInternal() {
    }
  }
}
