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

package android.app.appsearch;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.Parcel;
import android.os.Parcelable;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Objects;

/**
 * Information about the success or failure of an AppSearch call.
 *
 * @param <ValueType> The type of result object for successful calls.
 * @hide
 */
public class AppSearchResult<ValueType> implements Parcelable {
    /** Result codes from {@link AppSearchManager} methods. */
    @IntDef(prefix = {"RESULT_"}, value = {
            RESULT_OK,
            RESULT_UNKNOWN_ERROR,
            RESULT_INTERNAL_ERROR,
            RESULT_INVALID_ARGUMENT,
            RESULT_IO_ERROR,
            RESULT_OUT_OF_SPACE,
            RESULT_NOT_FOUND,
            RESULT_INVALID_SCHEMA,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface ResultCode {}

    /** The call was successful. */
    public static final int RESULT_OK = 0;

    /** An unknown error occurred while processing the call. */
    public static final int RESULT_UNKNOWN_ERROR = 1;

    /**
     * An internal error occurred within AppSearch, which the caller cannot address.
     *
     * This error may be considered similar to {@link IllegalStateException}
     */
    public static final int RESULT_INTERNAL_ERROR = 2;

    /**
     * The caller supplied invalid arguments to the call.
     *
     * This error may be considered similar to {@link IllegalArgumentException}.
     */
    public static final int RESULT_INVALID_ARGUMENT = 3;

    /**
     * An issue occurred reading or writing to storage. The call might succeed if repeated.
     *
     * This error may be considered similar to {@link java.io.IOException}.
     */
    public static final int RESULT_IO_ERROR = 4;

    /** Storage is out of space, and no more space could be reclaimed. */
    public static final int RESULT_OUT_OF_SPACE = 5;

    /** An entity the caller requested to interact with does not exist in the system. */
    public static final int RESULT_NOT_FOUND = 6;

    /** The caller supplied a schema which is invalid or incompatible with the previous schema. */
    public static final int RESULT_INVALID_SCHEMA = 7;

    private final @ResultCode int mResultCode;
    @Nullable private final ValueType mResultValue;
    @Nullable private final String mErrorMessage;

    private AppSearchResult(
            @ResultCode int resultCode,
            @Nullable ValueType resultValue,
            @Nullable String errorMessage) {
        mResultCode = resultCode;
        mResultValue = resultValue;
        mErrorMessage = errorMessage;
    }

    private AppSearchResult(@NonNull Parcel in) {
        mResultCode = in.readInt();
        mResultValue = (ValueType) in.readValue(/*loader=*/ null);
        mErrorMessage = in.readString();
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeInt(mResultCode);
        dest.writeValue(mResultValue);
        dest.writeString(mErrorMessage);
    }

    /** Returns {@code true} if {@link #getResultCode} equals {@link AppSearchResult#RESULT_OK}. */
    public boolean isSuccess() {
        return getResultCode() == RESULT_OK;
    }

    /** Returns one of the {@code RESULT} constants defined in {@link AppSearchResult}. */
    public @ResultCode int getResultCode() {
        return mResultCode;
    }

    /**
     * Returns the returned value associated with this result.
     *
     * <p>If {@link #isSuccess} is {@code false}, the result value is always {@code null}. The value
     * may be {@code null} even if {@link #isSuccess} is {@code true}. See the documentation of the
     * particular {@link AppSearchManager} call producing this {@link AppSearchResult} for what is
     * returned by {@link #getResultValue}.
     */
    @Nullable
    public ValueType getResultValue() {
        return mResultValue;
    }

    /**
     * Returns the error message associated with this result.
     *
     * <p>If {@link #isSuccess} is {@code true}, the error message is always {@code null}. The error
     * message may be {@code null} even if {@link #isSuccess} is {@code false}. See the
     * documentation of the particular {@link AppSearchManager} call producing this
     * {@link AppSearchResult} for what is returned by {@link #getErrorMessage}.
     */
    @Nullable
    public String getErrorMessage() {
        return mErrorMessage;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof AppSearchResult)) {
            return false;
        }
        AppSearchResult<?> otherResult = (AppSearchResult) other;
        return mResultCode == otherResult.mResultCode
                && Objects.equals(mResultValue, otherResult.mResultValue)
                && Objects.equals(mErrorMessage, otherResult.mErrorMessage);
    }

    @Override
    public int hashCode() {
        return Objects.hash(mResultCode, mResultValue, mErrorMessage);
    }

    @Override
    @NonNull
    public String toString() {
        if (isSuccess()) {
            return "AppSearchResult [SUCCESS]: " + mResultValue;
        }
        return "AppSearchResult [FAILURE(" + mResultCode + ")]: " + mErrorMessage;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    public static final Creator<AppSearchResult> CREATOR =
            new Creator<AppSearchResult>() {
        @NonNull
        @Override
        public AppSearchResult createFromParcel(@NonNull Parcel in) {
            return new AppSearchResult(in);
        }

        @NonNull
        @Override
        public AppSearchResult[] newArray(int size) {
            return new AppSearchResult[size];
        }
    };

    /**
     * Creates a new successful {@link AppSearchResult}.
     * @hide
     */
    @NonNull
    public static <ValueType> AppSearchResult<ValueType> newSuccessfulResult(
            @Nullable ValueType value) {
        return new AppSearchResult<>(RESULT_OK, value, /*errorMessage=*/ null);
    }

    /**
     * Creates a new failed {@link AppSearchResult}.
     * @hide
     */
    @NonNull
    public static <ValueType> AppSearchResult<ValueType> newFailedResult(
            @ResultCode int resultCode, @Nullable String errorMessage) {
        return new AppSearchResult<>(resultCode, /*resultValue=*/ null, errorMessage);
    }
}
