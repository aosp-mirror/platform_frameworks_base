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

package android.content.pm.verify.pkg;

import android.annotation.FlaggedApi;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.SystemApi;
import android.content.pm.Flags;
import android.os.Parcel;
import android.os.Parcelable;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * This class is used by the verifier to describe the status of the verification request, whether
 * it's successful or it has failed along with any relevant details.
 * @hide
 */
@SystemApi
@FlaggedApi(Flags.FLAG_VERIFICATION_SERVICE)
public final class  VerificationStatus implements Parcelable {
    /**
     * The ASL status has not been determined.  This happens in situations where the verification
     * service is not monitoring ASLs, and means the ASL data in the app is not necessarily bad but
     * can't be trusted.
     */
    public static final int VERIFIER_STATUS_ASL_UNDEFINED = 0;

    /**
     * The app's ASL data is considered to be in a good state.
     */
    public static final int VERIFIER_STATUS_ASL_GOOD = 1;

    /**
     * There is something bad in the app's ASL data; the user should be warned about this when shown
     * the ASL data and/or appropriate decisions made about the use of this data by the platform.
     */
    public static final int VERIFIER_STATUS_ASL_BAD = 2;

    /** @hide */
    @IntDef(prefix = {"VERIFIER_STATUS_ASL_"}, value = {
            VERIFIER_STATUS_ASL_UNDEFINED,
            VERIFIER_STATUS_ASL_GOOD,
            VERIFIER_STATUS_ASL_BAD,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface VerifierStatusAsl {}

    private boolean mIsVerified;
    private @VerifierStatusAsl int mAslStatus;
    @NonNull
    private String mFailuresMessage = "";

    private VerificationStatus() {}

    /**
     * @return whether the status is set to verified or not.
     */
    public boolean isVerified() {
        return mIsVerified;
    }

    /**
     * @return the failure message associated with the failure status.
     */
    @NonNull
    public String getFailureMessage() {
        return mFailuresMessage;
    }

    /**
     * @return the asl status.
     */
    public @VerifierStatusAsl int getAslStatus() {
        return mAslStatus;
    }

    /**
     * Builder to construct a {@link VerificationStatus} object.
     */
    public static final class Builder {
        final VerificationStatus mStatus = new VerificationStatus();

        /**
         * Set in the status whether the verification has succeeded or failed.
         */
        @NonNull
        public Builder setVerified(boolean verified) {
            mStatus.mIsVerified = verified;
            return this;
        }

        /**
         * Set a developer-facing failure message to include in the verification failure status.
         */
        @NonNull
        public Builder setFailureMessage(@NonNull String failureMessage) {
            mStatus.mFailuresMessage = failureMessage;
            return this;
        }

        /**
         * Set the ASL status, as defined in {@link VerifierStatusAsl}.
         */
        @NonNull
        public Builder setAslStatus(@VerifierStatusAsl int aslStatus) {
            mStatus.mAslStatus = aslStatus;
            return this;
        }

        /**
         * Build the status object.
         */
        @NonNull
        public VerificationStatus build() {
            return mStatus;
        }
    }

    private VerificationStatus(Parcel in) {
        mIsVerified = in.readBoolean();
        mAslStatus = in.readInt();
        mFailuresMessage = in.readString8();
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeBoolean(mIsVerified);
        dest.writeInt(mAslStatus);
        dest.writeString8(mFailuresMessage);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @NonNull
    public static final Creator<VerificationStatus> CREATOR = new Creator<>() {
        @Override
        public VerificationStatus createFromParcel(@NonNull Parcel in) {
            return new VerificationStatus(in);
        }

        @Override
        public VerificationStatus[] newArray(int size) {
            return new VerificationStatus[size];
        }
    };
}
