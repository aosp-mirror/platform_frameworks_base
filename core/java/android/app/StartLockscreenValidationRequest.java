/*
 * Copyright (C) 2023 The Android Open Source Project
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

package android.app;

import android.annotation.NonNull;
import android.annotation.SystemApi;
import android.app.KeyguardManager.LockTypes;
import android.os.Parcel;
import android.os.Parcelable;

import java.util.Objects;

/**
 * Provides information necessary to perform remote lock screen credentials check.
 *
 * @hide
 */
@SystemApi
public final class StartLockscreenValidationRequest implements Parcelable {

    @LockTypes
    private int mLockscreenUiType;

    private byte[] mSourcePublicKey;

    private int mRemainingAttempts;

    public static final @NonNull Parcelable.Creator<StartLockscreenValidationRequest> CREATOR = new
            Parcelable.Creator<StartLockscreenValidationRequest>() {
        @Override
        public StartLockscreenValidationRequest createFromParcel(Parcel source) {
            return new StartLockscreenValidationRequest(source);
        }

        @Override
        public StartLockscreenValidationRequest[] newArray(int size) {
            return new StartLockscreenValidationRequest[size];
        }
    };


    /**
     * Builder for {@code StartLockscreenValidationRequest}
     */
    public static final class Builder {
        private StartLockscreenValidationRequest mInstance = new StartLockscreenValidationRequest();

        /**
         * Sets UI type.
         * Default value is {@code LockTypes.PASSWORD}
         *
         * @param lockscreenUiType The UI format
         * @return This builder.
         */
        public @NonNull Builder setLockscreenUiType(@LockTypes int lockscreenUiType) {
            mInstance.mLockscreenUiType = lockscreenUiType;
            return this;
        }

        /**
         * Sets public key using secure box encoding
         * @return This builder.
         */
        public @NonNull Builder setSourcePublicKey(@NonNull byte[] publicKey) {
            mInstance.mSourcePublicKey = publicKey;
            return this;
        }

        /**
         * Sets the number of remaining credentials check
         * Default value is {@code 0}
         *
         * @return This builder.
         */
        public @NonNull Builder setRemainingAttempts(int remainingAttempts) {
            mInstance.mRemainingAttempts = remainingAttempts;
            return this;
        }

        /**
         * Creates {@code StartLockscreenValidationRequest}
         *
         * @throws NullPointerException if required fields are not set.
         */
        public @NonNull StartLockscreenValidationRequest build() {
            Objects.requireNonNull(mInstance.mSourcePublicKey);
            return mInstance;
        }
    }

    /**
     * Specifies lock screen credential type.
     */
    public @LockTypes int getLockscreenUiType() {
        return mLockscreenUiType;
    }

    /**
     * Public key used to send encrypted credentials.
     */
    public @NonNull byte[] getSourcePublicKey() {
        return mSourcePublicKey;
    }

    /**
     * Number of remaining attempts to verify credentials.
     *
     * <p>After correct guess counter is reset to {@code 5}.
     */
    public int getRemainingAttempts() {
        return mRemainingAttempts;
    }

    @Override
    public void writeToParcel(@NonNull Parcel out, int flags) {
        out.writeInt(mLockscreenUiType);
        out.writeByteArray(mSourcePublicKey);
        out.writeInt(mRemainingAttempts);
    }

    private StartLockscreenValidationRequest() {
    }

    private StartLockscreenValidationRequest(Parcel in) {
        mLockscreenUiType = in.readInt();
        mSourcePublicKey = in.createByteArray();
        mRemainingAttempts = in.readInt();
    }

    @Override
    public int describeContents() {
        return 0;
    }
}
