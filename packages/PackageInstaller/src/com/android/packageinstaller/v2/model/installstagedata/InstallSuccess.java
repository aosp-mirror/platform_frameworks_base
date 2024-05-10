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

import android.content.Intent;
import android.graphics.drawable.Drawable;
import androidx.annotation.NonNull;
import com.android.packageinstaller.v2.model.PackageUtil.AppSnippet;

public class InstallSuccess extends InstallStage {

    private final int mStage = InstallStage.STAGE_SUCCESS;

    @NonNull
    private final AppSnippet mAppSnippet;
    private final boolean mShouldReturnResult;
    /**
     * <p>If the caller is requesting a result back, this will hold the Intent with
     * EXTRA_INSTALL_RESULT set to INSTALL_SUCCEEDED which is sent back to the caller.</p>
     * <p>If the caller doesn't want the result back, this will hold the Intent that launches
     * the newly installed / updated app.</p>
     */
    @NonNull
    private final Intent mResultIntent;

    public InstallSuccess(@NonNull AppSnippet appSnippet, boolean shouldReturnResult,
        @NonNull Intent launcherIntent) {
        mAppSnippet = appSnippet;
        mShouldReturnResult = shouldReturnResult;
        mResultIntent = launcherIntent;
    }

    @Override
    public int getStageCode() {
        return mStage;
    }

    @NonNull
    public Drawable getAppIcon() {
        return mAppSnippet.getIcon();
    }

    @NonNull
    public String getAppLabel() {
        return (String) mAppSnippet.getLabel();
    }

    public boolean shouldReturnResult() {
        return mShouldReturnResult;
    }

    @NonNull
    public Intent getResultIntent() {
        return mResultIntent;
    }

    public static class Builder {

        private final AppSnippet mAppSnippet;
        private boolean mShouldReturnResult;
        private Intent mLauncherIntent;

        public Builder(@NonNull AppSnippet appSnippet) {
            mAppSnippet = appSnippet;
        }

        public Builder setShouldReturnResult(boolean returnResult) {
            mShouldReturnResult = returnResult;
            return this;
        }

        public Builder setResultIntent(@NonNull Intent intent) {
            mLauncherIntent = intent;
            return this;
        }

        public InstallSuccess build() {
            return new InstallSuccess(mAppSnippet, mShouldReturnResult, mLauncherIntent);
        }
    }
}
