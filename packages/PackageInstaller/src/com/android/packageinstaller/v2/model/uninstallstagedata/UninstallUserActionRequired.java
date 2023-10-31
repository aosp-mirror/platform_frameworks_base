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

public class UninstallUserActionRequired extends UninstallStage {

    private final int mStage = UninstallStage.STAGE_USER_ACTION_REQUIRED;
    private final String mTitle;
    private final String mMessage;

    public UninstallUserActionRequired(String title, String message) {
        mTitle = title;
        mMessage = message;
    }

    public String getTitle() {
        return mTitle;
    }

    public String getMessage() {
        return mMessage;
    }

    @Override
    public int getStageCode() {
        return mStage;
    }

    public static class Builder {

        private String mTitle;
        private String mMessage;

        public Builder setTitle(String title) {
            mTitle = title;
            return this;
        }

        public Builder setMessage(String message) {
            mMessage = message;
            return this;
        }

        public UninstallUserActionRequired build() {
            return new UninstallUserActionRequired(mTitle, mMessage);
        }
    }
}
