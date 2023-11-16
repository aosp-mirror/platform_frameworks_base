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

package com.android.packageinstaller.v2.model.installstagedata;


import android.app.Activity;
import android.content.Intent;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

public class InstallAborted extends InstallStage {

    public static final int ABORT_REASON_INTERNAL_ERROR = 0;
    public static final int ABORT_REASON_POLICY = 1;
    private final int mStage = InstallStage.STAGE_ABORTED;
    private final int mAbortReason;

    /**
     * It will hold the restriction name, when the restriction was enforced by the system, and not
     * a device admin.
     */
    @NonNull
    private final String mMessage;
    /**
     * <p>If abort reason is ABORT_REASON_POLICY, then this will hold the Intent
     * to display a support dialog when a feature was disabled by an admin. It will be
     * {@code null} if the feature is disabled by the system. In this case, the restriction name
     * will be set in {@link #mMessage} </p>
     *
     * <p>If the abort reason is ABORT_REASON_INTERNAL_ERROR, it <b>may</b> hold an
     * intent to be sent as a result to the calling activity.</p>
     */
    @Nullable
    private final Intent mIntent;
    private final int mActivityResultCode;

    private InstallAborted(int reason, @NonNull String message, @Nullable Intent intent,
        int activityResultCode) {
        mAbortReason = reason;
        mMessage = message;
        mIntent = intent;
        mActivityResultCode = activityResultCode;
    }

    public int getAbortReason() {
        return mAbortReason;
    }

    @NonNull
    public String getMessage() {
        return mMessage;
    }

    @Nullable
    public Intent getResultIntent() {
        return mIntent;
    }

    public int getActivityResultCode() {
        return mActivityResultCode;
    }

    @Override
    public int getStageCode() {
        return mStage;
    }

    public static class Builder {

        private final int mAbortReason;
        private String mMessage = "";
        private Intent mIntent = null;
        private int mActivityResultCode = Activity.RESULT_CANCELED;

        public Builder(int reason) {
            mAbortReason = reason;
        }

        public Builder setMessage(@NonNull String message) {
            mMessage = message;
            return this;
        }

        public Builder setResultIntent(@NonNull Intent intent) {
            mIntent = intent;
            return this;
        }

        public Builder setActivityResultCode(int resultCode) {
            mActivityResultCode = resultCode;
            return this;
        }

        public InstallAborted build() {
            return new InstallAborted(mAbortReason, mMessage, mIntent, mActivityResultCode);
        }
    }
}
