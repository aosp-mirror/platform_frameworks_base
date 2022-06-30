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

package android.service.ambientcontext;

import android.annotation.NonNull;
import android.annotation.SystemApi;
import android.app.ambientcontext.AmbientContextManager;
import android.app.ambientcontext.AmbientContextManager.StatusCode;
import android.os.Parcelable;

import com.android.internal.util.AnnotationValidations;

import java.util.Objects;

/**
 * Represents a status for the {@code AmbientContextDetectionService}.
 *
 * @hide
 */
@SystemApi
public final class AmbientContextDetectionServiceStatus implements Parcelable {
    /**
     * The bundle key for this class of object, used in {@code RemoteCallback#sendResult}.
     *
     * @hide
     */
    public static final String STATUS_RESPONSE_BUNDLE_KEY =
            "android.app.ambientcontext.AmbientContextServiceStatusBundleKey";

    @StatusCode private final int mStatusCode;
    @NonNull private final String mPackageName;

    AmbientContextDetectionServiceStatus(
            @StatusCode int statusCode,
            @NonNull String packageName) {
        this.mStatusCode = statusCode;
        AnnotationValidations.validate(StatusCode.class, null, mStatusCode);
        this.mPackageName = packageName;
    }

    /**
     * The status of the service.
     */
    public @StatusCode int getStatusCode() {
        return mStatusCode;
    }

    /**
     * The package to deliver the response to.
     */
    public @NonNull String getPackageName() {
        return mPackageName;
    }

    @Override
    public String toString() {
        return "AmbientContextDetectionServiceStatus { " + "statusCode = " + mStatusCode + ", "
                + "packageName = " + mPackageName + " }";
    }

    @Override
    public void writeToParcel(@NonNull android.os.Parcel dest, int flags) {
        byte flg = 0;
        dest.writeByte(flg);
        dest.writeInt(mStatusCode);
        dest.writeString(mPackageName);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    /** @hide */
    @SuppressWarnings({"unchecked", "RedundantCast"})
    AmbientContextDetectionServiceStatus(@NonNull android.os.Parcel in) {
        byte flg = in.readByte();
        int statusCode = in.readInt();
        String packageName = in.readString();

        this.mStatusCode = statusCode;
        AnnotationValidations.validate(
                StatusCode.class, null, mStatusCode);
        this.mPackageName = packageName;
        AnnotationValidations.validate(
                NonNull.class, null, mPackageName);
    }

    public static final @NonNull Creator<AmbientContextDetectionServiceStatus> CREATOR =
            new Creator<AmbientContextDetectionServiceStatus>() {
        @Override
        public AmbientContextDetectionServiceStatus[] newArray(int size) {
            return new AmbientContextDetectionServiceStatus[size];
        }

        @Override
        public AmbientContextDetectionServiceStatus createFromParcel(
                @NonNull android.os.Parcel in) {
            return new AmbientContextDetectionServiceStatus(in);
        }
    };

    /**
     * A builder for {@link AmbientContextDetectionServiceStatus}
     */
    @SuppressWarnings("WeakerAccess")
    public static final class Builder {
        private @StatusCode int mStatusCode;
        private @NonNull String mPackageName;
        private long mBuilderFieldsSet = 0L;

        public Builder(@NonNull String packageName) {
            Objects.requireNonNull(packageName);
            mPackageName = packageName;
        }

        /**
         * Sets the status of the service.
         */
        public @NonNull Builder setStatusCode(@StatusCode int value) {
            checkNotUsed();
            mBuilderFieldsSet |= 0x1;
            mStatusCode = value;
            return this;
        }

        /** Builds the instance. This builder should not be touched after calling this! */
        public @NonNull AmbientContextDetectionServiceStatus build() {
            checkNotUsed();
            mBuilderFieldsSet |= 0x2; // Mark builder used

            if ((mBuilderFieldsSet & 0x1) == 0) {
                mStatusCode = AmbientContextManager.STATUS_UNKNOWN;
            }
            AmbientContextDetectionServiceStatus o = new AmbientContextDetectionServiceStatus(
                    mStatusCode,
                    mPackageName);
            return o;
        }

        private void checkNotUsed() {
            if ((mBuilderFieldsSet & 0x2) != 0) {
                throw new IllegalStateException(
                        "This Builder should not be reused. Use a new Builder instance instead");
            }
        }
    }
}
