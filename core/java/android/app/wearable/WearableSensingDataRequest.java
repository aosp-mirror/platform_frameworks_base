/*
 * Copyright (C) 2024 The Android Open Source Project
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

package android.app.wearable;

import android.annotation.NonNull;
import android.annotation.SystemApi;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.PersistableBundle;

import java.time.Duration;

/**
 * Data class for a data request for wearable sensing.
 *
 * @hide
 */
@SystemApi
public final class WearableSensingDataRequest implements Parcelable {
    private static final int MAX_REQUEST_SIZE = 200;
    private static final Duration RATE_LIMIT_WINDOW_SIZE = Duration.ofMinutes(1);
    private static final int RATE_LIMIT = 30;

    private final int mDataType;
    @NonNull private final PersistableBundle mRequestDetails;

    private WearableSensingDataRequest(int dataType, @NonNull PersistableBundle requestDetails) {
        mDataType = dataType;
        mRequestDetails = requestDetails;
    }

    /** Returns the data type this request is for. */
    public int getDataType() {
        return mDataType;
    }

    /** Returns the details for this request. */
    @NonNull
    public PersistableBundle getRequestDetails() {
        return mRequestDetails;
    }

    /** Returns the data size of this object when it is parcelled. */
    public int getDataSize() {
        Parcel parcel = Parcel.obtain();
        try {
            writeToParcel(parcel, describeContents());
            return parcel.dataSize();
        } finally {
            parcel.recycle();
        }
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeInt(mDataType);
        dest.writeTypedObject(mRequestDetails, flags);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public String toString() {
        return "WearableSensingDataRequest { "
                + "dataType = "
                + mDataType
                + ", "
                + "requestDetails = "
                + mRequestDetails
                + " }";
    }

    /**
     * Returns a String representation of this data request that shows its contents.
     *
     * @hide
     */
    public String toExpandedString() {
        if (mRequestDetails != null) {
            // Trigger unparcelling so that its individual fields will be listed in toString
            boolean unused =
                    mRequestDetails.getBoolean(
                            "PlaceholderForWearableSensingDataRequest#toExpandedString()");
        }
        return toString();
    }

    /**
     * The bundle key for this class of object, used in {@code RemoteCallback#sendResult}.
     *
     * @hide
     */
    public static final String REQUEST_BUNDLE_KEY =
            "android.app.wearable.WearableSensingDataRequestBundleKey";

    /**
     * The bundle key for the status callback for a data request, used in {@code
     * RemoteCallback#sendResult}.
     *
     * @hide
     */
    public static final String REQUEST_STATUS_CALLBACK_BUNDLE_KEY =
            "android.app.wearable.WearableSensingDataRequestStatusCallbackBundleKey";

    public static final @NonNull Parcelable.Creator<WearableSensingDataRequest> CREATOR =
            new Parcelable.Creator<WearableSensingDataRequest>() {
                @Override
                public WearableSensingDataRequest[] newArray(int size) {
                    return new WearableSensingDataRequest[size];
                }

                @Override
                public WearableSensingDataRequest createFromParcel(@NonNull Parcel in) {
                    int dataType = in.readInt();
                    PersistableBundle requestDetails =
                            in.readTypedObject(PersistableBundle.CREATOR);
                    return new WearableSensingDataRequest(dataType, requestDetails);
                }
            };

    /**
     * Returns the maximum allowed size of a WearableSensingDataRequest when it is parcelled.
     * Instances that exceed this size can be constructed, but will be rejected by the system when
     * they leave the isolated WearableSensingService.
     */
    public static int getMaxRequestSize() {
        return MAX_REQUEST_SIZE;
    }

    /**
     * Returns the rolling time window used to perform rate limiting on data requests leaving the
     * WearableSensingService.
     */
    @NonNull
    public static Duration getRateLimitWindowSize() {
        return RATE_LIMIT_WINDOW_SIZE;
    }

    /**
     * Returns the number of data requests allowed to leave the WearableSensingService in each
     * {@link #getRateLimitWindowSize()}.
     */
    public static int getRateLimit() {
        return RATE_LIMIT;
    }

    /** A builder for WearableSensingDataRequest. */
    public static final class Builder {
        private int mDataType;
        private PersistableBundle mRequestDetails;

        public Builder(int dataType) {
            mDataType = dataType;
        }

        /** Sets the request details. */
        public @NonNull Builder setRequestDetails(@NonNull PersistableBundle requestDetails) {
            mRequestDetails = requestDetails;
            return this;
        }

        /** Builds the WearableSensingDataRequest. */
        public @NonNull WearableSensingDataRequest build() {
            if (mRequestDetails == null) {
                mRequestDetails = PersistableBundle.EMPTY;
            }
            return new WearableSensingDataRequest(mDataType, mRequestDetails);
        }
    }
}
