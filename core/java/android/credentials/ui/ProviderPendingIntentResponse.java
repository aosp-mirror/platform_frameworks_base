/*
 * Copyright (C) 2022 The Android Open Source Project
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
import android.annotation.SuppressLint;
import android.content.Intent;
import android.os.Parcel;
import android.os.Parcelable;

/**
 * Result of launching a provider's PendingIntent associated with an {@link Entry} after it is
 * selected by the user.
 *
 * The provider sets the credential creation / retrieval result through
 * {@link android.app.Activity#setResult(int, Intent)}, which is then directly propagated back
 * through this data structure.
 *
 * @hide
 */
public final class ProviderPendingIntentResponse implements Parcelable {
    private final int mResultCode;
    @Nullable
    private final Intent mResultData;

    /** Constructs a {@link ProviderPendingIntentResponse}. */
    public ProviderPendingIntentResponse(int resultCode, @Nullable Intent resultData) {
        mResultCode = resultCode;
        mResultData = resultData;
    }

    private ProviderPendingIntentResponse(@NonNull Parcel in) {
        mResultCode = in.readInt();
        mResultData = in.readTypedObject(Intent.CREATOR);
    }

    public static final @NonNull Creator<ProviderPendingIntentResponse> CREATOR =
            new Creator<>() {
                @Override
                public ProviderPendingIntentResponse createFromParcel(@NonNull Parcel in) {
                    return new ProviderPendingIntentResponse(in);
                }

                @Override
                public ProviderPendingIntentResponse[] newArray(int size) {
                    return new ProviderPendingIntentResponse[size];
                }
            };

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeInt(mResultCode);
        dest.writeTypedObject(mResultData, flags);
    }

    /** Returns the result code associated with this provider PendingIntent activity result. */
    public int getResultCode() {
        return mResultCode;
    }

    /** Returns the result data associated with this provider PendingIntent activity result. */
    @SuppressLint("IntentBuilderName") // Not building a new intent.
    @NonNull
    public Intent getResultData() {
        return mResultData;
    }
}
