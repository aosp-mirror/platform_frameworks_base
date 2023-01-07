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

import com.android.internal.util.AnnotationValidations;

/**
 * Result data matching {@link BaseDialogResult#RESULT_CODE_PROVIDER_ENABLED}, or {@link
 * BaseDialogResult#RESULT_CODE_DEFAULT_PROVIDER_CHANGED}.
 *
 * @hide
 */
public final class ProviderDialogResult extends BaseDialogResult implements Parcelable {
    /** Parses and returns a ProviderDialogResult from the given resultData. */
    @Nullable
    public static ProviderDialogResult fromResultData(@NonNull Bundle resultData) {
        return resultData.getParcelable(EXTRA_PROVIDER_RESULT, ProviderDialogResult.class);
    }

    /**
     * Used for the UX to construct the {@code resultData Bundle} to send via the {@code
     *  ResultReceiver}.
     */
    public static void addToBundle(
            @NonNull ProviderDialogResult result, @NonNull Bundle bundle) {
        bundle.putParcelable(EXTRA_PROVIDER_RESULT, result);
    }

    /**
     * The intent extra key for the {@code ProviderDialogResult} object when the credential
     * selector activity finishes.
     */
    private static final String EXTRA_PROVIDER_RESULT =
            "android.credentials.ui.extra.PROVIDER_RESULT";

    @NonNull
    private final String mProviderId;

    public ProviderDialogResult(@NonNull IBinder requestToken, @NonNull String providerId) {
        super(requestToken);
        mProviderId = providerId;
    }

    @NonNull
    public String getProviderId() {
        return mProviderId;
    }

    protected ProviderDialogResult(@NonNull Parcel in) {
        super(in);
        String providerId = in.readString8();
        mProviderId = providerId;
        AnnotationValidations.validate(NonNull.class, null, mProviderId);
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        super.writeToParcel(dest, flags);
        dest.writeString8(mProviderId);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    public static final @NonNull Creator<ProviderDialogResult> CREATOR =
            new Creator<ProviderDialogResult>() {
        @Override
        public ProviderDialogResult createFromParcel(@NonNull Parcel in) {
            return new ProviderDialogResult(in);
        }

        @Override
        public ProviderDialogResult[] newArray(int size) {
            return new ProviderDialogResult[size];
        }
    };
}
