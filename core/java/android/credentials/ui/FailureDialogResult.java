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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Parcel;
import android.os.Parcelable;

/**
 * Result data when the selector UI has encountered a failure.
 *
 * @hide
 */
public final class FailureDialogResult extends BaseDialogResult implements Parcelable {
    /** Parses and returns a UserSelectionDialogResult from the given resultData. */
    @Nullable
    public static FailureDialogResult fromResultData(@NonNull Bundle resultData) {
        return resultData.getParcelable(
                EXTRA_FAILURE_RESULT, FailureDialogResult.class);
    }

    /**
     * Used for the UX to construct the {@code resultData Bundle} to send via the {@code
     * ResultReceiver}.
     */
    public static void addToBundle(
            @NonNull FailureDialogResult result, @NonNull Bundle bundle) {
        bundle.putParcelable(EXTRA_FAILURE_RESULT, result);
    }

    /**
     * The intent extra key for the {@code UserSelectionDialogResult} object when the credential
     * selector activity finishes.
     */
    private static final String EXTRA_FAILURE_RESULT =
            "android.credentials.ui.extra.FAILURE_RESULT";

    @Nullable
    private final String mErrorMessage;

    public FailureDialogResult(@Nullable IBinder requestToken, @Nullable String errorMessage) {
        super(requestToken);
        mErrorMessage = errorMessage;
    }

    /** Returns provider package name whose entry was selected by the user. */
    @Nullable
    public String getErrorMessage() {
        return mErrorMessage;
    }

    protected FailureDialogResult(@NonNull Parcel in) {
        super(in);
        mErrorMessage = in.readString8();
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        super.writeToParcel(dest, flags);
        dest.writeString8(mErrorMessage);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    public static final @NonNull Creator<FailureDialogResult> CREATOR =
            new Creator<>() {
                @Override
                public FailureDialogResult createFromParcel(@NonNull Parcel in) {
                    return new FailureDialogResult(in);
                }

                @Override
                public FailureDialogResult[] newArray(int size) {
                    return new FailureDialogResult[size];
                }
            };
}
