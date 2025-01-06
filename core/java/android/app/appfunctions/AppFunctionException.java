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
package android.app.appfunctions;

import static android.app.appfunctions.flags.Flags.FLAG_ENABLE_APP_FUNCTION_MANAGER;

import android.annotation.FlaggedApi;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Objects;

/**
 * Represents an app function related error.
 *
 * <p>This exception may include an {@link AppFunctionException#getExtras() Bundle} containing
 * additional error-specific metadata.
 *
 * <p>The AppFunction SDK can expose structured APIs by packing and unpacking this Bundle.
 */
@FlaggedApi(FLAG_ENABLE_APP_FUNCTION_MANAGER)
public final class AppFunctionException extends Exception implements Parcelable {
    /**
     * The caller does not have the permission to execute an app function.
     *
     * <p>This error is in the {@link #ERROR_CATEGORY_REQUEST_ERROR} category.
     */
    public static final int ERROR_DENIED = 1000;

    /**
     * The caller supplied invalid arguments to the execution request.
     *
     * <p>This error may be considered similar to {@link IllegalArgumentException}.
     *
     * <p>This error is in the {@link #ERROR_CATEGORY_REQUEST_ERROR} category.
     */
    public static final int ERROR_INVALID_ARGUMENT = 1001;

    /**
     * The caller tried to execute a disabled app function.
     *
     * <p>This error is in the {@link #ERROR_CATEGORY_REQUEST_ERROR} category.
     */
    public static final int ERROR_DISABLED = 1002;

    /**
     * The caller tried to execute a function that does not exist.
     *
     * <p>This error is in the {@link #ERROR_CATEGORY_REQUEST_ERROR} category.
     */
    public static final int ERROR_FUNCTION_NOT_FOUND = 1003;

    /**
     * An internal unexpected error coming from the system.
     *
     * <p>This error is in the {@link #ERROR_CATEGORY_SYSTEM} category.
     */
    public static final int ERROR_SYSTEM_ERROR = 2000;

    /**
     * The operation was cancelled. Use this error code to report that a cancellation is done after
     * receiving a cancellation signal.
     *
     * <p>This error is in the {@link #ERROR_CATEGORY_SYSTEM} category.
     */
    public static final int ERROR_CANCELLED = 2001;

    /**
     * The operation was disallowed by enterprise policy.
     *
     * <p>This error is in the {@link #ERROR_CATEGORY_SYSTEM} category.
     */
    public static final int ERROR_ENTERPRISE_POLICY_DISALLOWED = 2002;

    /**
     * An unknown error occurred while processing the call in the AppFunctionService.
     *
     * <p>This error is thrown when the service is connected in the remote application but an
     * unexpected error is thrown from the bound application.
     *
     * <p>This error is in the {@link #ERROR_CATEGORY_APP} category.
     */
    public static final int ERROR_APP_UNKNOWN_ERROR = 3000;

    /**
     * The error category is unknown.
     *
     * <p>This is the default value for {@link #getErrorCategory}.
     */
    public static final int ERROR_CATEGORY_UNKNOWN = 0;

    /**
     * The error is caused by the app requesting a function execution.
     *
     * <p>For example, the caller provided invalid parameters in the execution request e.g. an
     * invalid function ID.
     *
     * <p>Errors in the category fall in the range 1000-1999 inclusive.
     */
    public static final int ERROR_CATEGORY_REQUEST_ERROR = 1;

    /**
     * The error is caused by an issue in the system.
     *
     * <p>For example, the AppFunctionService implementation is not found by the system.
     *
     * <p>Errors in the category fall in the range 2000-2999 inclusive.
     */
    public static final int ERROR_CATEGORY_SYSTEM = 2;

    /**
     * The error is caused by the app providing the function.
     *
     * <p>For example, the app crashed when the system is executing the request.
     *
     * <p>Errors in the category fall in the range 3000-3999 inclusive.
     */
    public static final int ERROR_CATEGORY_APP = 3;

    private final int mErrorCode;
    @Nullable private final String mErrorMessage;
    @NonNull private final Bundle mExtras;

    /**
     * @param errorCode The error code.
     * @param errorMessage The error message.
     */
    public AppFunctionException(@ErrorCode int errorCode, @Nullable String errorMessage) {
        this(errorCode, errorMessage, Bundle.EMPTY);
    }

    /**
     * @param errorCode The error code.
     * @param errorMessage The error message.
     * @param extras The extras associated with this error.
     */
    public AppFunctionException(
            @ErrorCode int errorCode, @Nullable String errorMessage, @NonNull Bundle extras) {
        super(errorMessage);
        mErrorCode = errorCode;
        mErrorMessage = errorMessage;
        mExtras = Objects.requireNonNull(extras);
    }

    private AppFunctionException(@NonNull Parcel in) {
        mErrorCode = in.readInt();
        mErrorMessage = in.readString8();
        mExtras = Objects.requireNonNull(in.readBundle(getClass().getClassLoader()));
    }

    /** Returns one of the {@code ERROR} constants. */
    @ErrorCode
    public int getErrorCode() {
        return mErrorCode;
    }

    /** Returns the error message. */
    @Nullable
    public String getErrorMessage() {
        return mErrorMessage;
    }

    /**
     * Returns the error category.
     *
     * <p>This method categorizes errors based on their underlying cause, allowing developers to
     * implement targeted error handling and provide more informative error messages to users. It
     * maps ranges of error codes to specific error categories.
     *
     * <p>This method returns {@code ERROR_CATEGORY_UNKNOWN} if the error code does not belong to
     * any error category.
     *
     * <p>See {@link ErrorCategory} for a complete list of error categories and their corresponding
     * error code ranges.
     */
    @ErrorCategory
    public int getErrorCategory() {
        if (mErrorCode >= 1000 && mErrorCode < 2000) {
            return ERROR_CATEGORY_REQUEST_ERROR;
        }
        if (mErrorCode >= 2000 && mErrorCode < 3000) {
            return ERROR_CATEGORY_SYSTEM;
        }
        if (mErrorCode >= 3000 && mErrorCode < 4000) {
            return ERROR_CATEGORY_APP;
        }
        return ERROR_CATEGORY_UNKNOWN;
    }

    /** Returns any extras associated with this error. */
    @NonNull
    public Bundle getExtras() {
        return mExtras;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeInt(mErrorCode);
        dest.writeString8(mErrorMessage);
        dest.writeBundle(mExtras);
    }

    /**
     * Error codes.
     *
     * @hide
     */
    @IntDef(
            prefix = {"ERROR_"},
            value = {
                ERROR_DENIED,
                ERROR_APP_UNKNOWN_ERROR,
                ERROR_FUNCTION_NOT_FOUND,
                ERROR_SYSTEM_ERROR,
                ERROR_INVALID_ARGUMENT,
                ERROR_DISABLED,
                ERROR_CANCELLED,
                ERROR_ENTERPRISE_POLICY_DISALLOWED
            })
    @Retention(RetentionPolicy.SOURCE)
    public @interface ErrorCode {}

    /**
     * Error categories.
     *
     * @hide
     */
    @IntDef(
            prefix = {"ERROR_CATEGORY_"},
            value = {
                ERROR_CATEGORY_UNKNOWN,
                ERROR_CATEGORY_REQUEST_ERROR,
                ERROR_CATEGORY_APP,
                ERROR_CATEGORY_SYSTEM
            })
    @Retention(RetentionPolicy.SOURCE)
    public @interface ErrorCategory {}

    @NonNull
    public static final Creator<AppFunctionException> CREATOR =
            new Creator<>() {
                @Override
                public AppFunctionException createFromParcel(Parcel in) {
                    return new AppFunctionException(in);
                }

                @Override
                public AppFunctionException[] newArray(int size) {
                    return new AppFunctionException[size];
                }
            };
}
