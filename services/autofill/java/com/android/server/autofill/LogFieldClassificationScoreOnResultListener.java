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

import android.annotation.Nullable;
import android.os.Bundle;
import android.os.RemoteCallback;
import android.service.autofill.FieldClassification;
import android.service.autofill.FillEventHistory.Event.NoSaveReason;
import android.util.Slog;
import android.view.autofill.AutofillId;
import android.view.autofill.AutofillManager.AutofillCommitReason;

import java.util.ArrayList;

class LogFieldClassificationScoreOnResultListener implements
        RemoteCallback.OnResultListener {

    private static final String TAG = "LogFieldClassificationScoreOnResultListener";

    private Session mSession;
    private final @NoSaveReason int mSaveDialogNotShowReason;
    private final @AutofillCommitReason int mCommitReason;
    private final int mViewsSize;
    private final AutofillId[] mAutofillIds;
    private final String[] mUserValues;
    private final String[] mCategoryIds;
    private final ArrayList<AutofillId> mDetectedFieldIds;
    private final ArrayList<FieldClassification> mDetectedFieldClassifications;
    LogFieldClassificationScoreOnResultListener(Session session,
            int saveDialogNotShowReason,
            int commitReason, int viewsSize, AutofillId[] autofillIds, String[] userValues,
            String[] categoryIds, ArrayList<AutofillId> detectedFieldIds,
            ArrayList<FieldClassification> detectedFieldClassifications) {
        this.mSession = session;
        this.mSaveDialogNotShowReason = saveDialogNotShowReason;
        this.mCommitReason = commitReason;
        this.mViewsSize = viewsSize;
        this.mAutofillIds = autofillIds;
        this.mUserValues = userValues;
        this.mCategoryIds = categoryIds;
        this.mDetectedFieldIds = detectedFieldIds;
        this.mDetectedFieldClassifications = detectedFieldClassifications;
    }

    public void onResult(@Nullable Bundle result) {
        // Create a local copy to safe guard race condition
        Session session = mSession;
        if (session == null) {
            Slog.wtf(TAG, "session is null when calling onResult()");
            return;
        }
        session.handleLogFieldClassificationScore(
                result,
                mSaveDialogNotShowReason,
                mCommitReason,
                mViewsSize,
                mAutofillIds,
                mUserValues,
                mCategoryIds,
                mDetectedFieldIds,
                mDetectedFieldClassifications);
        mSession = null;
    }
}
