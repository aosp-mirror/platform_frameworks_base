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
import android.os.Parcel;
import android.os.Parcelable;

import com.android.internal.telephony.PhoneConstants;

import java.util.Objects;

/**
 * Holds the result from a pin attempt.
 *
 * @hide
 */
public final class PinResult implements Parcelable {
    /** @hide */
    @IntDef({
            PIN_RESULT_TYPE_SUCCESS,
            PIN_RESULT_TYPE_INCORRECT,
            PIN_RESULT_TYPE_FAILURE,
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

    private static final PinResult sFailedResult =
            new PinResult(PinResult.PIN_RESULT_TYPE_FAILURE, -1);

    private final @PinResultType int mType;

    private final int mAttemptsRemaining;

    /**
     * Returns either success, incorrect or failure.
     *
     * @see #PIN_RESULT_TYPE_SUCCESS
     * @see #PIN_RESULT_TYPE_INCORRECT
     * @see #PIN_RESULT_TYPE_FAILURE
     * @return The result type of the pin attempt.
     */
    public @PinResultType int getType() {
        return mType;
    }

    /**
     * The number of pin attempts remaining.
     *
     * @return Number of attempts remaining.
     */
    public int getAttemptsRemaining() {
        return mAttemptsRemaining;
    }

    @NonNull
    public static PinResult getDefaultFailedResult() {
        return sFailedResult;
    }

    /**
     * PinResult constructor
     *
     * @param type The type of pin result.
     * @see #PIN_RESULT_TYPE_SUCCESS
     * @see #PIN_RESULT_TYPE_INCORRECT
     * @see #PIN_RESULT_TYPE_FAILURE
     * @param attemptsRemaining Number of pin attempts remaining.
     */
    public PinResult(@PinResultType int type, int attemptsRemaining) {
        mType = type;
        mAttemptsRemaining = attemptsRemaining;
    }

    /**
     * Construct a PinResult object from the given parcel.
     *
     * @hide
     */
    private PinResult(Parcel in) {
        mType = in.readInt();
        mAttemptsRemaining = in.readInt();
    }

    /**
     * String representation of the Pin Result.
     */
    @NonNull
    @Override
    public String toString() {
        return "type: " + getType() + ", attempts remaining: " + getAttemptsRemaining();
    }

    /**
     * Required to be Parcelable
     */
    @Override
    public int describeContents() {
        return 0;
    }

    /**
     * Required to be Parcelable
     */
    @Override
    public void writeToParcel(@NonNull Parcel out, int flags) {
        out.writeInt(mType);
        out.writeInt(mAttemptsRemaining);
    }

    /** Required to be Parcelable */
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
        return Objects.hash(mAttemptsRemaining, mType);
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
        return (mType == other.mType
                && mAttemptsRemaining == other.mAttemptsRemaining);
    }
}
