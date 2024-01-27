/*
 * Copyright 2022 The Android Open Source Project
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

package android.credentials.selection;

import static android.credentials.flags.Flags.FLAG_CONFIGURABLE_SELECTOR_UI_ENABLED;

import android.annotation.FlaggedApi;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Failure or cancellation result encountered during a UI flow.
 *
 * @hide
 */
@SystemApi
@FlaggedApi(FLAG_CONFIGURABLE_SELECTOR_UI_ENABLED)
public final class FailureResult {
    @Nullable
    private final String mErrorMessage;
    @NonNull
    private final int mErrorCode;

    /** @hide **/
    @IntDef(prefix = {"ERROR_CODE_"}, value = {
            ERROR_CODE_DIALOG_CANCELED_BY_USER,
            ERROR_CODE_CANCELED_AND_LAUNCHED_SETTINGS,
            ERROR_CODE_UI_FAILURE,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface ErrorCode {
    }

    /**
     * The UI was stopped due to a failure, e.g. because it failed to parse the incoming data,
     * or it encountered an irrecoverable internal issue.
     */
    public static final int ERROR_CODE_UI_FAILURE = 0;
    /** The user intentionally canceled the dialog. */
    public static final int ERROR_CODE_DIALOG_CANCELED_BY_USER = 1;
    /**
     * The UI was stopped since the user has chosen to navigate to the Settings UI to reconfigure
     * their providers.
     */
    public static final int ERROR_CODE_CANCELED_AND_LAUNCHED_SETTINGS = 2;

    /**
     * Constructs a {@link FailureResult}.
     *
     * @throws IllegalArgumentException if {@code providerId} is empty
     */
    public FailureResult(@ErrorCode int errorCode, @Nullable String errorMessage) {
        mErrorCode = errorCode;
        mErrorMessage = errorMessage;
    }

    /** Returns the error code. */
    @ErrorCode
    public int getErrorCode() {
        return mErrorCode;
    }

    /** Returns the error message. */
    @Nullable
    public String getErrorMessage() {
        return mErrorMessage;
    }

    FailureDialogResult toFailureDialogResult() {
        return new FailureDialogResult(/*requestToken=*/null, mErrorMessage);
    }

    int errorCodeToResultCode() {
        switch (mErrorCode) {
            case ERROR_CODE_DIALOG_CANCELED_BY_USER:
                return BaseDialogResult.RESULT_CODE_DIALOG_USER_CANCELED;
            case ERROR_CODE_CANCELED_AND_LAUNCHED_SETTINGS:
                return BaseDialogResult.RESULT_CODE_CANCELED_AND_LAUNCHED_SETTINGS;
            default:
                return BaseDialogResult.RESULT_CODE_DATA_PARSING_FAILURE;
        }
    }
}
