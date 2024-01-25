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

package android.credentials.ui;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Parcel;
import android.os.Parcelable;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Base dialog result data.
 *
 * Returned for simple use cases like cancellation. Can also be subclassed when more information
 * is needed, e.g. {@link UserSelectionDialogResult}.
 *
 * @hide
 */
public class BaseDialogResult implements Parcelable {
    /** Parses and returns a BaseDialogResult from the given resultData. */
    @Nullable
    public static BaseDialogResult fromResultData(@NonNull Bundle resultData) {
        return resultData.getParcelable(EXTRA_BASE_RESULT, BaseDialogResult.class);
    }

    /**
     * Used for the UX to construct the {@code resultData Bundle} to send via the {@code
     * ResultReceiver}.
     */
    public static void addToBundle(@NonNull BaseDialogResult result, @NonNull Bundle bundle) {
        bundle.putParcelable(EXTRA_BASE_RESULT, result);
    }

    /**
     * The intent extra key for the {@code BaseDialogResult} object when the credential
     * selector activity finishes.
     */
    private static final String EXTRA_BASE_RESULT = "android.credentials.ui.extra.BASE_RESULT";

    /** @hide **/
    @IntDef(prefix = {"RESULT_CODE_"}, value = {
            RESULT_CODE_DIALOG_USER_CANCELED,
            RESULT_CODE_CANCELED_AND_LAUNCHED_SETTINGS,
            RESULT_CODE_DIALOG_COMPLETE_WITH_SELECTION,
            RESULT_CODE_DATA_PARSING_FAILURE,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface ResultCode {
    }

    /** User intentionally canceled the dialog. */
    public static final int RESULT_CODE_DIALOG_USER_CANCELED = 0;
    /**
     * The UI was stopped since the user has chosen to navigate to the Settings UI to reconfigure
     * their providers.
     */
    public static final int RESULT_CODE_CANCELED_AND_LAUNCHED_SETTINGS = 1;
    /**
     * User made a selection and the dialog finished. The user selection result is in the
     * {@code resultData}.
     */
    public static final int RESULT_CODE_DIALOG_COMPLETE_WITH_SELECTION = 2;
    /**
     * The UI was canceled because it failed to parse the incoming data.
     */
    public static final int RESULT_CODE_DATA_PARSING_FAILURE = 3;

    @Nullable
    @Deprecated
    private final IBinder mRequestToken;

    public BaseDialogResult(@Nullable IBinder requestToken) {
        mRequestToken = requestToken;
    }

    /** Returns the unique identifier for the request that launched the operation. */
    @Nullable
    @Deprecated
    public IBinder getRequestToken() {
        return mRequestToken;
    }

    protected BaseDialogResult(@NonNull Parcel in) {
        IBinder requestToken = in.readStrongBinder();
        mRequestToken = requestToken;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeStrongBinder(mRequestToken);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    public static final @NonNull Creator<BaseDialogResult> CREATOR =
            new Creator<BaseDialogResult>() {
                @Override
                public BaseDialogResult createFromParcel(@NonNull Parcel in) {
                    return new BaseDialogResult(in);
                }

                @Override
                public BaseDialogResult[] newArray(int size) {
                    return new BaseDialogResult[size];
                }
            };
}
