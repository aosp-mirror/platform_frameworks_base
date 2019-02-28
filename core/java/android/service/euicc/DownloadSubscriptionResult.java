/*
 * Copyright (C) 2018 The Android Open Source Project
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
package android.service.euicc;

import android.annotation.SystemApi;
import android.os.Parcel;
import android.os.Parcelable;
import android.service.euicc.EuiccService.ResolvableError;
import android.service.euicc.EuiccService.Result;

/**
 * Result of a {@link EuiccService#onDownloadSubscription} operation.
 * @hide
 */
@SystemApi
public final class DownloadSubscriptionResult implements Parcelable {

    public static final @android.annotation.NonNull Creator<DownloadSubscriptionResult> CREATOR =
            new Creator<DownloadSubscriptionResult>() {
        @Override
        public DownloadSubscriptionResult createFromParcel(Parcel in) {
            return new DownloadSubscriptionResult(in);
        }

        @Override
        public DownloadSubscriptionResult[] newArray(int size) {
            return new DownloadSubscriptionResult[size];
        }
    };

    private final @Result int mResult;
    private final @ResolvableError int mResolvableErrors;
    private final int mCardId;

    public DownloadSubscriptionResult(@Result int result, @ResolvableError int resolvableErrors,
            int cardId) {
        this.mResult = result;
        this.mResolvableErrors = resolvableErrors;
        this.mCardId = cardId;
    }

    /** Gets the result of the operation. */
    public @Result int getResult() {
        return mResult;
    }

    /**
     * Gets the bit map of resolvable errors.
     *
     * <p>The value is passed from EuiccService. The values can be
     *
     * <ul>
     * <li>{@link EuiccService#RESOLVABLE_ERROR_CONFIRMATION_CODE}
     * <li>{@link EuiccService#RESOLVABLE_ERROR_POLICY_RULES}
     * </ul>
     */
    public @ResolvableError int getResolvableErrors() {
        return mResolvableErrors;
    }

    /**
     * Gets the card Id. This is used when resolving resolvable errors. The value is passed from
     * EuiccService.
     */
    public int getCardId() {
        return mCardId;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(mResult);
        dest.writeInt(mResolvableErrors);
        dest.writeInt(mCardId);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    private DownloadSubscriptionResult(Parcel in) {
        this.mResult = in.readInt();
        this.mResolvableErrors = in.readInt();
        this.mCardId = in.readInt();
    }
}
