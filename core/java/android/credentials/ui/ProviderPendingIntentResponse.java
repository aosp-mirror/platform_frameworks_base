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

import android.annotation.Nullable;
import android.content.Intent;
import android.os.Parcel;
import android.os.Parcelable;

import androidx.annotation.NonNull;

/**
 * Response from a provider's pending intent
 *
 * @hide
 */
public final class ProviderPendingIntentResponse implements Parcelable {
    private final int mResultCode;
    @Nullable
    private final Intent mResultData;

    public ProviderPendingIntentResponse(int resultCode, @Nullable Intent resultData) {
        mResultCode = resultCode;
        mResultData = resultData;
    }

    protected ProviderPendingIntentResponse(Parcel in) {
        mResultCode = in.readInt();
        mResultData = in.readTypedObject(Intent.CREATOR);
    }

    public static final Creator<ProviderPendingIntentResponse> CREATOR =
            new Creator<ProviderPendingIntentResponse>() {
                @Override
                public ProviderPendingIntentResponse createFromParcel(Parcel in) {
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

    /** Returns the result code associated with this pending intent activity result. */
    public int getResultCode() {
        return mResultCode;
    }

    /** Returns the result data associated with this pending intent activity result. */
    @NonNull public Intent getResultData() {
        return mResultData;
    }
}
