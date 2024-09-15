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

import static android.view.autofill.AutofillManager.COMMIT_REASON_UNKNOWN;

import static com.android.internal.util.FrameworkStatsLog.AUTOFILL_SESSION_COMMITTED;
import static com.android.server.autofill.Helper.sVerbose;

import android.util.Slog;
import android.view.autofill.AutofillManager.AutofillCommitReason;

import com.android.internal.util.FrameworkStatsLog;

import java.util.Optional;

/**
 * Helper class to log Autofill session committed event stats.
 */
public final class SessionCommittedEventLogger {
  private static final String TAG = "SessionCommittedEventLogger";

  private final int mSessionId;
  private Optional<SessionCommittedEventInternal> mEventInternal;

  private SessionCommittedEventLogger(int sessionId) {
    mSessionId = sessionId;
    mEventInternal = Optional.of(new SessionCommittedEventInternal());
  }

  /**
   * A factory constructor to create SessionCommittedEventLogger.
   */
  public static SessionCommittedEventLogger forSessionId(int sessionId) {
    return new SessionCommittedEventLogger(sessionId);
  }

  /**
   * Set component_package_uid as long as mEventInternal presents.
   */
  public void maybeSetComponentPackageUid(int val) {
    mEventInternal.ifPresent(event -> {
      event.mComponentPackageUid = val;
    });
  }

  /**
   * Set request_count as long as mEventInternal presents.
   */
  public void maybeSetRequestCount(int val) {
    mEventInternal.ifPresent(event -> {
      event.mRequestCount = val;
    });
  }

  /**
   * Set commit_reason as long as mEventInternal presents.
   */
  public void maybeSetCommitReason(@AutofillCommitReason int val) {
    mEventInternal.ifPresent(event -> {
        event.mCommitReason = val;
    });
  }

  /**
   * Set session_duration_millis as long as mEventInternal presents.
   */
  public void maybeSetSessionDurationMillis(long timestamp) {
    mEventInternal.ifPresent(event -> {
      event.mSessionDurationMillis = timestamp;
    });
  }

  /** Set autofill_service_uid as long as mEventInternal presents. */
  public void maybeSetAutofillServiceUid(int uid) {
        mEventInternal.ifPresent(
                event -> {
                    event.mServiceUid = uid;
                });
  }

  /**
   * Set how many save infos there are in current session as long as mEventInternal presents.
   */
  public void maybeSetSaveInfoCount(int saveInfoCount) {
        mEventInternal.ifPresent(event -> {
            event.mSaveInfoCount = saveInfoCount;
        });
  }

  /**
   * Set how many save data types there are in current session as long as mEventInternal presents.
   */
  public void maybeSetSaveDataTypeCount(int saveDataTypeCount) {
        mEventInternal.ifPresent(event -> {
            event.mSaveDataTypeCount = saveDataTypeCount;
        });
  }

  /**
   * Set whether last fill response in session has save info as long as mEventInternal presents.
   */
  public void maybeSetLastFillResponseHasSaveInfo(boolean lastFillResponseHasSaveInfo) {
        mEventInternal.ifPresent(event -> {
            event.mLastFillResponseHasSaveInfo = lastFillResponseHasSaveInfo;
        });
  }

  /**
   * Log an AUTOFILL_SESSION_COMMITTED event.
   */
  public void logAndEndEvent() {
    if (!mEventInternal.isPresent()) {
      Slog.w(TAG, "Shouldn't be logging AutofillSessionCommitted again for same session.");
      return;
    }
    SessionCommittedEventInternal event = mEventInternal.get();
    if (sVerbose) {
      Slog.v(TAG, "Log AutofillSessionCommitted:"
          + " sessionId=" + mSessionId
          + " mComponentPackageUid=" + event.mComponentPackageUid
          + " mRequestCount=" + event.mRequestCount
          + " mCommitReason=" + event.mCommitReason
          + " mSessionDurationMillis=" + event.mSessionDurationMillis
          + " mServiceUid=" + event.mServiceUid
          + " mSaveInfoCount=" + event.mSaveInfoCount
          + " mSaveDataTypeCount=" + event.mSaveDataTypeCount
          + " mLastFillResponseHasSaveInfo=" + event.mLastFillResponseHasSaveInfo);
    }
    FrameworkStatsLog.write(
        AUTOFILL_SESSION_COMMITTED,
        mSessionId,
        event.mComponentPackageUid,
        event.mRequestCount,
        event.mCommitReason,
        event.mSessionDurationMillis,
        event.mServiceUid,
        event.mSaveInfoCount,
        event.mSaveDataTypeCount,
        event.mLastFillResponseHasSaveInfo);
    mEventInternal = Optional.empty();
  }

  private static final class SessionCommittedEventInternal {
    int mComponentPackageUid = -1;
    int mRequestCount = 0;
    int mCommitReason = COMMIT_REASON_UNKNOWN;
    long mSessionDurationMillis = 0;
    int mSaveInfoCount = -1;
    int mSaveDataTypeCount = -1;
    boolean mLastFillResponseHasSaveInfo = false;
    int mServiceUid = -1;

    SessionCommittedEventInternal() {
    }
  }
}
