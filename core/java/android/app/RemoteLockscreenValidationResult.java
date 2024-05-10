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

import android.annotation.DurationMillisLong;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.SystemApi;
import android.os.Parcel;
import android.os.Parcelable;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
/**
 * Result of lock screen credentials verification.
 *
 * @hide
 */
@SystemApi
public final class RemoteLockscreenValidationResult implements Parcelable {

    /**
     * The guess was correct
     */
    public static final int RESULT_GUESS_VALID = 1;

    /**
     * Remote device provided incorrect credentials.
     */
    public static final int RESULT_GUESS_INVALID = 2;

    /**
     * The operation was canceled because the API is locked out due to too many attempts. It
     * usually happens after 5 failed attempts and API may be called again after a short
     * delay specified by {@code getTimeoutMillis}.
     */
    public static final int RESULT_LOCKOUT = 3;

    /**
     * There were too many invalid guesses.
     */
    public static final int RESULT_NO_REMAINING_ATTEMPTS = 4;

    /**
     * New lockscreen validation session is required to verify guess.
     */
    public static final int RESULT_SESSION_EXPIRED = 5;

    @IntDef({RESULT_GUESS_VALID,
            RESULT_GUESS_INVALID,
            RESULT_LOCKOUT,
            RESULT_NO_REMAINING_ATTEMPTS,
            RESULT_SESSION_EXPIRED})
    @Retention(RetentionPolicy.SOURCE)
    @interface ResultCode {}

    private int mResultCode;
    private long mTimeoutMillis;

    public static final @NonNull Parcelable.Creator<RemoteLockscreenValidationResult> CREATOR =
            new Parcelable.Creator<RemoteLockscreenValidationResult>() {
        @Override
        public RemoteLockscreenValidationResult createFromParcel(Parcel source) {
            return new RemoteLockscreenValidationResult(source);
        }

        @Override
        public RemoteLockscreenValidationResult[] newArray(int size) {
            return new RemoteLockscreenValidationResult[size];
        }
    };

    /**
     * Builder for {@code RemoteLockscreenValidationResult}
     */
    public static final class Builder {
        private RemoteLockscreenValidationResult mInstance = new RemoteLockscreenValidationResult();

        /**
         * Sets the result code.
         */
        public @NonNull Builder setResultCode(@ResultCode int resultCode) {
            mInstance.mResultCode = resultCode;
            return this;
        }

        /**
         * Sets timeout for {@code RESULT_LOCKOUT}.
         * Default value is {@code 0}.
         */
        public @NonNull Builder setTimeoutMillis(@DurationMillisLong long timeoutMillis) {
            mInstance.mTimeoutMillis = timeoutMillis;
            return this;
        }

        /**
         * Creates {@code RemoteLockscreenValidationResult}.
         *
         * @throws IllegalStateException if result code was not set.
         */
        public @NonNull RemoteLockscreenValidationResult build() {
            if (mInstance.mResultCode == 0) {
                throw new IllegalStateException("Result code must be set");
            }
            return mInstance;
        }
    }

    /**
     * Gets the result code.
     */
    public @ResultCode int getResultCode() {
        return mResultCode;
    }

    /**
     * Delay before next attempt to verify credentials.
     *
     * Default value is {@code 0}.
     */
    public @DurationMillisLong long getTimeoutMillis() {
        return mTimeoutMillis;
    }


    @Override
    public void writeToParcel(@NonNull Parcel out, int flags) {
        out.writeInt(mResultCode);
        out.writeLong(mTimeoutMillis);
    }

    private RemoteLockscreenValidationResult() {
    }

    private RemoteLockscreenValidationResult(Parcel in) {
        mResultCode = in.readInt();
        mTimeoutMillis = in.readLong();
    }

    @Override
    public int describeContents() {
        return 0;
    }
}
