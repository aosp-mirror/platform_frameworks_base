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

import android.content.Intent;

public class UninstallSuccess extends UninstallStage {

    private final int mStage = UninstallStage.STAGE_SUCCESS;
    private final String mMessage;
    private final Intent mResultIntent;
    private final int mActivityResultCode;

    public UninstallSuccess(Intent resultIntent, int activityResultCode, String message) {
        mResultIntent = resultIntent;
        mActivityResultCode = activityResultCode;
        mMessage = message;
    }

    public String getMessage() {
        return mMessage;
    }

    public Intent getResultIntent() {
        return mResultIntent;
    }

    public int getActivityResultCode() {
        return mActivityResultCode;
    }

    @Override
    public int getStageCode() {
        return mStage;
    }

    public static class Builder {

        private Intent mResultIntent;
        private int mActivityResultCode;
        private String mMessage;

        public Builder() {
        }

        public Builder setResultIntent(Intent intent) {
            mResultIntent = intent;
            return this;
        }

        public Builder setActivityResultCode(int resultCode) {
            mActivityResultCode = resultCode;
            return this;
        }

        public Builder setMessage(String message) {
            mMessage = message;
            return this;
        }

        public UninstallSuccess build() {
            return new UninstallSuccess(mResultIntent, mActivityResultCode, mMessage);
        }
    }
}
