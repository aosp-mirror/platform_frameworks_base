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

import android.graphics.drawable.Drawable;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.android.packageinstaller.v2.model.PackageUtil.AppSnippet;

public class InstallUserActionRequired extends InstallStage {

    public static final int USER_ACTION_REASON_UNKNOWN_SOURCE = 0;
    public static final int USER_ACTION_REASON_ANONYMOUS_SOURCE = 1;
    public static final int USER_ACTION_REASON_INSTALL_CONFIRMATION = 2;
    private final int mStage = InstallStage.STAGE_USER_ACTION_REQUIRED;
    private final int mActionReason;
    @NonNull
    private final AppSnippet mAppSnippet;
    private final boolean mIsAppUpdating;
    @Nullable
    private final String mDialogMessage;

    public InstallUserActionRequired(int actionReason, @NonNull AppSnippet appSnippet,
        boolean isUpdating, @Nullable String dialogMessage) {
        mActionReason = actionReason;
        mAppSnippet = appSnippet;
        mIsAppUpdating = isUpdating;
        mDialogMessage = dialogMessage;
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

    public boolean isAppUpdating() {
        return mIsAppUpdating;
    }

    @Nullable
    public String getDialogMessage() {
        return mDialogMessage;
    }

    public static class Builder {

        private final int mActionReason;
        private final AppSnippet mAppSnippet;
        private boolean mIsAppUpdating;
        private String mDialogMessage;

        public Builder(int actionReason, @NonNull AppSnippet appSnippet) {
            mActionReason = actionReason;
            mAppSnippet = appSnippet;
        }

        public Builder setAppUpdating(boolean isUpdating) {
            mIsAppUpdating = isUpdating;
            return this;
        }

        public Builder setDialogMessage(@Nullable String message) {
            mDialogMessage = message;
            return this;
        }

        public InstallUserActionRequired build() {
            return new InstallUserActionRequired(mActionReason, mAppSnippet, mIsAppUpdating,
                mDialogMessage);
        }
    }
}
