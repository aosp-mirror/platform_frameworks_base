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
import com.android.packageinstaller.R;

public class UninstallAborted extends UninstallStage {

    public static final int ABORT_REASON_GENERIC_ERROR = 0;
    public static final int ABORT_REASON_APP_UNAVAILABLE = 1;
    public static final int ABORT_REASON_USER_NOT_ALLOWED = 2;
    private final int mStage = UninstallStage.STAGE_ABORTED;
    private final int mAbortReason;
    private final int mDialogTitleResource;
    private final int mDialogTextResource;
    private final int mActivityResultCode = Activity.RESULT_FIRST_USER;

    public UninstallAborted(int abortReason) {
        mAbortReason = abortReason;
        switch (abortReason) {
            case ABORT_REASON_APP_UNAVAILABLE -> {
                mDialogTitleResource = R.string.app_not_found_dlg_title;
                mDialogTextResource = R.string.app_not_found_dlg_text;
            }
            case ABORT_REASON_USER_NOT_ALLOWED -> {
                mDialogTitleResource = 0;
                mDialogTextResource = R.string.user_is_not_allowed_dlg_text;
            }
            default -> {
                mDialogTitleResource = 0;
                mDialogTextResource = R.string.generic_error_dlg_text;
            }
        }
    }

    public int getAbortReason() {
        return mAbortReason;
    }

    public int getActivityResultCode() {
        return mActivityResultCode;
    }

    public int getDialogTitleResource() {
        return mDialogTitleResource;
    }

    public int getDialogTextResource() {
        return mDialogTextResource;
    }

    @Override
    public int getStageCode() {
        return mStage;
    }
}
