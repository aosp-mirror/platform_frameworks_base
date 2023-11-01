/*
 * Copyright (C) 2023 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.packageinstaller.v2.model.uninstallstagedata;

import android.app.Activity;
import android.app.Notification;
import android.content.Intent;

public class UninstallFailed extends UninstallStage {

    private final int mStage = UninstallStage.STAGE_FAILED;
    private final boolean mReturnResult;
    /**
     * If the caller wants the result back, the intent will hold the uninstall failure status code
     * and legacy code.
     */
    private final Intent mResultIntent;
    /**
     * When the user does not request a result back, this notification will be shown indicating the
     * reason for uninstall failure.
     */
    private final Notification mUninstallNotification;
    /**
     * ID used to show {@link #mUninstallNotification}
     */
    private final int mUninstallId;
    private final int mActivityResultCode;

    public UninstallFailed(boolean returnResult, Intent resultIntent, int activityResultCode,
        int uninstallId, Notification uninstallNotification) {
        mReturnResult = returnResult;
        mResultIntent = resultIntent;
        mActivityResultCode = activityResultCode;
        mUninstallId = uninstallId;
        mUninstallNotification = uninstallNotification;
    }

    public boolean returnResult() {
        return mReturnResult;
    }

    public Intent getResultIntent() {
        return mResultIntent;
    }

    public int getActivityResultCode() {
        return mActivityResultCode;
    }

    public Notification getUninstallNotification() {
        return mUninstallNotification;
    }

    public int getUninstallId() {
        return mUninstallId;
    }

    @Override
    public int getStageCode() {
        return mStage;
    }

    public static class Builder {

        private final boolean mReturnResult;
        private int mActivityResultCode = Activity.RESULT_CANCELED;
        /**
         * See {@link UninstallFailed#mResultIntent}
         */
        private Intent mResultIntent = null;
        /**
         * See {@link UninstallFailed#mUninstallNotification}
         */
        private Notification mUninstallNotification;
        /**
         * See {@link UninstallFailed#mUninstallId}
         */
        private int mUninstallId;

        public Builder(boolean returnResult) {
            mReturnResult = returnResult;
        }

        public Builder setUninstallNotification(int uninstallId, Notification notification) {
            mUninstallId = uninstallId;
            mUninstallNotification = notification;
            return this;
        }

        public Builder setResultIntent(Intent intent) {
            mResultIntent = intent;
            return this;
        }

        public Builder setActivityResultCode(int resultCode) {
            mActivityResultCode = resultCode;
            return this;
        }

        public UninstallFailed build() {
            return new UninstallFailed(mReturnResult, mResultIntent, mActivityResultCode,
                mUninstallId, mUninstallNotification);
        }
    }
}
