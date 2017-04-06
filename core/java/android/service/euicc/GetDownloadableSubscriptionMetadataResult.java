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

import android.annotation.IntDef;
import android.annotation.Nullable;
import android.os.Parcel;
import android.os.Parcelable;
import android.telephony.euicc.DownloadableSubscription;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Result of a {@link EuiccService#getDownloadableSubscriptionMetadata} operation.
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

    /** @hide */
    @IntDef({
            RESULT_OK,
            RESULT_GENERIC_ERROR,
            RESULT_MUST_DEACTIVATE_REMOVABLE_SIM,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface ResultCode {}

    public static final int RESULT_OK = 0;
    public static final int RESULT_MUST_DEACTIVATE_REMOVABLE_SIM = 1;
    public static final int RESULT_GENERIC_ERROR = 2;

    /** Result of the operation - one of the RESULT_* constants. */
    public final @ResultCode int result;

    /**
     * The {@link DownloadableSubscription} with filled-in metadata.
     *
     * <p>Only non-null if {@link #result} is {@link #RESULT_OK}.
     */
    @Nullable
    public final DownloadableSubscription subscription;

    /** Implementation-defined detailed error code in case of a failure not covered here. */
    public final int detailedCode;

    private GetDownloadableSubscriptionMetadataResult(int result,
            @Nullable DownloadableSubscription subscription, int detailedCode) {
        this.result = result;
        this.subscription = subscription;
        this.detailedCode = detailedCode;
    }

    private GetDownloadableSubscriptionMetadataResult(Parcel in) {
        this.result = in.readInt();
        this.subscription = in.readTypedObject(DownloadableSubscription.CREATOR);
        this.detailedCode = in.readInt();
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(result);
        dest.writeTypedObject(this.subscription, flags);
        dest.writeInt(detailedCode);
    }

    /** Return a result indicating that the download was successful. */
    public static GetDownloadableSubscriptionMetadataResult success(
            DownloadableSubscription subscription) {
        return new GetDownloadableSubscriptionMetadataResult(RESULT_OK, subscription,
                0 /* detailedCode */);
    }

    /**
     * Return a result indicating that the removable SIM must be deactivated to perform the
     * operation.
     */
    public static GetDownloadableSubscriptionMetadataResult mustDeactivateRemovableSim() {
        return new GetDownloadableSubscriptionMetadataResult(RESULT_MUST_DEACTIVATE_REMOVABLE_SIM,
                null /* subscription */, 0 /* detailedCode */);
    }

    /**
     * Return a result indicating that an error occurred for which no other more specific error
     * code has been defined.
     *
     * @param detailedCode an implementation-defined detailed error code for debugging purposes.
     */
    public static GetDownloadableSubscriptionMetadataResult genericError(int detailedCode) {
        return new GetDownloadableSubscriptionMetadataResult(RESULT_GENERIC_ERROR,
                null /* subscription */, detailedCode);
    }

    @Override
    public int describeContents() {
        return 0;
    }
}