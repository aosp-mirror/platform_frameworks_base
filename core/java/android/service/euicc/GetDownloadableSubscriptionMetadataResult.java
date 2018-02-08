/*
 * Copyright (C) 2017 The Android Open Source Project
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

import android.annotation.Nullable;
import android.os.Parcel;
import android.os.Parcelable;
import android.telephony.euicc.DownloadableSubscription;

/**
 * Result of a {@link EuiccService#onGetDownloadableSubscriptionMetadata} operation.
 * @hide
 *
 * TODO(b/35851809): Make this a SystemApi.
 */
public final class GetDownloadableSubscriptionMetadataResult implements Parcelable {

    public static final Creator<GetDownloadableSubscriptionMetadataResult> CREATOR =
            new Creator<GetDownloadableSubscriptionMetadataResult>() {
        @Override
        public GetDownloadableSubscriptionMetadataResult createFromParcel(Parcel in) {
            return new GetDownloadableSubscriptionMetadataResult(in);
        }

        @Override
        public GetDownloadableSubscriptionMetadataResult[] newArray(int size) {
            return new GetDownloadableSubscriptionMetadataResult[size];
        }
    };

    /**
     * Result of the operation.
     *
     * <p>May be one of the predefined {@code RESULT_} constants in EuiccService or any
     * implementation-specific code starting with {@link EuiccService#RESULT_FIRST_USER}.
     */
    public final int result;

    /**
     * The {@link DownloadableSubscription} with filled-in metadata.
     *
     * <p>Only non-null if {@link #result} is {@link EuiccService#RESULT_OK}.
     */
    @Nullable
    public final DownloadableSubscription subscription;

    /**
     * Construct a new {@link GetDownloadableSubscriptionMetadataResult}.
     *
     * @param result Result of the operation. May be one of the predefined {@code RESULT_} constants
     *     in EuiccService or any implementation-specific code starting with
     *     {@link EuiccService#RESULT_FIRST_USER}.
     * @param subscription The subscription with filled-in metadata. Should only be provided if the
     *     result is {@link EuiccService#RESULT_OK}.
     */
    public GetDownloadableSubscriptionMetadataResult(int result,
            @Nullable DownloadableSubscription subscription) {
        this.result = result;
        if (this.result == EuiccService.RESULT_OK) {
            this.subscription = subscription;
        } else {
            if (subscription != null) {
                throw new IllegalArgumentException(
                        "Error result with non-null subscription: " + result);
            }
            this.subscription = null;
        }
    }

    private GetDownloadableSubscriptionMetadataResult(Parcel in) {
        this.result = in.readInt();
        this.subscription = in.readTypedObject(DownloadableSubscription.CREATOR);
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(result);
        dest.writeTypedObject(this.subscription, flags);
    }

    @Override
    public int describeContents() {
        return 0;
    }
}