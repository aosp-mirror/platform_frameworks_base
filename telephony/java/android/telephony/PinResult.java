/*
 * Copyright (C) 2020 The Android Open Source Project
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

package android.telephony;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.os.Parcel;
import android.os.Parcelable;

import com.android.internal.telephony.PhoneConstants;

import java.util.Objects;

/**
 * Holds the result from a PIN attempt.
 *
 * @see TelephonyManager#supplyIccLockPin
 * @see TelephonyManager#supplyIccLockPuk
 * @see TelephonyManager#setIccLockEnabled
 * @see TelephonyManager#changeIccLockPin
 *
 * @hide
 */
@SystemApi
public final class PinResult implements Parcelable {
    /** @hide */
    @IntDef({
            PIN_RESULT_TYPE_SUCCESS,
            PIN_RESULT_TYPE_INCORRECT,
            PIN_RESULT_TYPE_FAILURE,
            PIN_RESULT_TYPE_ABORTED,
    })
    public @interface PinResultType {}

    /**
     * Indicates that the pin attempt was a success.
     */
    public static final int PIN_RESULT_TYPE_SUCCESS = PhoneConstants.PIN_RESULT_SUCCESS;

    /**
     * Indicates that the pin attempt was incorrect.
     */
    public static final int PIN_RESULT_TYPE_INCORRECT = PhoneConstants.PIN_PASSWORD_INCORRECT;

    /**
     * Indicates that the pin attempt was a failure.
     */
    public static final int PIN_RESULT_TYPE_FAILURE = PhoneConstants.PIN_GENERAL_FAILURE;

    /**
     * Indicates that the pin attempt was aborted.
     */
    public static final int PIN_RESULT_TYPE_ABORTED = PhoneConstants.PIN_OPERATION_ABORTED;

    private static final PinResult sFailedResult =
            new PinResult(PinResult.PIN_RESULT_TYPE_FAILURE, -1);

    private final @PinResultType int mResult;

    private final int mAttemptsRemaining;

    /**
     * Returns the result of the PIN attempt.
     *
     * @return The result of the PIN attempt.
     */
    public @PinResultType int getResult() {
        return mResult;
    }

    /**
     * Returns the number of PIN attempts remaining.
     * This will be set when {@link #getResult} is {@link #PIN_RESULT_TYPE_INCORRECT}.
     * Indicates the number of attempts at entering the PIN before the SIM will be locked and
     * require a PUK unlock to be performed.
     *
     * @return Number of attempts remaining.
     */
    public int getAttemptsRemaining() {
        return mAttemptsRemaining;
    }

    /**
     * Used to indicate a failed PIN attempt result.
     *
     * @return default PinResult for failure.
     *
     * @hide
     */
    @NonNull
    public static PinResult getDefaultFailedResult() {
        return sFailedResult;
    }

    /**
     * PinResult constructor.
     *
     * @param result The pin result value.
     * @see #PIN_RESULT_TYPE_SUCCESS
     * @see #PIN_RESULT_TYPE_INCORRECT
     * @see #PIN_RESULT_TYPE_FAILURE
     * @see #PIN_RESULT_TYPE_ABORTED
     * @param attemptsRemaining Number of pin attempts remaining.
     *
     * @hide
     */
    public PinResult(@PinResultType int result, int attemptsRemaining) {
        mResult = result;
        mAttemptsRemaining = attemptsRemaining;
    }

    /**
     * Construct a PinResult object from the given parcel.
     *
     * @hide
     */
    private PinResult(Parcel in) {
        mResult = in.readInt();
        mAttemptsRemaining = in.readInt();
    }

    /**
     * String representation of the Pin Result.
     */
    @NonNull
    @Override
    public String toString() {
        return "result: " + getResult() + ", attempts remaining: " + getAttemptsRemaining();
    }

    /**
     * Describe the contents of this object.
     */
    @Override
    public int describeContents() {
        return 0;
    }

    /**
     * Write this object to a Parcel.
     */
    @Override
    public void writeToParcel(@NonNull Parcel out, int flags) {
        out.writeInt(mResult);
        out.writeInt(mAttemptsRemaining);
    }

    /**
     * Parcel creator class.
     */
    public static final @NonNull Parcelable.Creator<PinResult> CREATOR = new Creator<PinResult>() {
        public PinResult createFromParcel(Parcel in) {
            return new PinResult(in);
        }
        public PinResult[] newArray(int size) {
            return new PinResult[size];
        }
    };

    @Override
    public int hashCode() {
        return Objects.hash(mAttemptsRemaining, mResult);
    }

    @Override
    public boolean equals(@Nullable Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj == null) {
            return false;
        }
        if (getClass() != obj.getClass()) {
            return false;
        }
        PinResult other = (PinResult) obj;
        return (mResult == other.mResult
                && mAttemptsRemaining == other.mAttemptsRemaining);
    }
}
