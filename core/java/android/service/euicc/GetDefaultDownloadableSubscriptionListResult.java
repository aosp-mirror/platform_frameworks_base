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
 * Result of a {@link EuiccService#onGetDefaultDownloadableSubscriptionList} operation.
 * @hide
 *
 * TODO(b/35851809): Make this a SystemApi.
 */
public final class GetDefaultDownloadableSubscriptionListResult implements Parcelable {

    public static final Creator<GetDefaultDownloadableSubscriptionListResult> CREATOR =
            new Creator<GetDefaultDownloadableSubscriptionListResult>() {
        @Override
        public GetDefaultDownloadableSubscriptionListResult createFromParcel(Parcel in) {
            return new GetDefaultDownloadableSubscriptionListResult(in);
        }

        @Override
        public GetDefaultDownloadableSubscriptionListResult[] newArray(int size) {
            return new GetDefaultDownloadableSubscriptionListResult[size];
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
     * The available {@link DownloadableSubscription}s (with filled-in metadata).
     *
     * <p>Only non-null if {@link #result} is {@link EuiccService#RESULT_OK}.
     */
    @Nullable
    public final DownloadableSubscription[] subscriptions;

    /**
     * Construct a new {@link GetDefaultDownloadableSubscriptionListResult}.
     *
     * @param result Result of the operation. May be one of the predefined {@code RESULT_} constants
     *     in EuiccService or any implementation-specific code starting with
     *     {@link EuiccService#RESULT_FIRST_USER}.
     * @param subscriptions The available subscriptions. Should only be provided if the result is
     *     {@link EuiccService#RESULT_OK}.
     */
    public GetDefaultDownloadableSubscriptionListResult(int result,
            @Nullable DownloadableSubscription[] subscriptions) {
        this.result = result;
        if (this.result == EuiccService.RESULT_OK) {
            this.subscriptions = subscriptions;
        } else {
            if (subscriptions != null) {
                throw new IllegalArgumentException(
                        "Error result with non-null subscriptions: " + result);
            }
            this.subscriptions = null;
        }
    }

    private GetDefaultDownloadableSubscriptionListResult(Parcel in) {
        this.result = in.readInt();
        this.subscriptions = in.createTypedArray(DownloadableSubscription.CREATOR);
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(result);
        dest.writeTypedArray(subscriptions, flags);
    }

    @Override
    public int describeContents() {
        return 0;
    }
}
