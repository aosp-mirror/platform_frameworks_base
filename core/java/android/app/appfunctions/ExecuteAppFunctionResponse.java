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
import android.app.appsearch.GenericDocument;
import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Objects;

/** The response to an app function execution. */
@FlaggedApi(FLAG_ENABLE_APP_FUNCTION_MANAGER)
public final class ExecuteAppFunctionResponse implements Parcelable {
    @NonNull
    public static final Creator<ExecuteAppFunctionResponse> CREATOR =
            new Creator<ExecuteAppFunctionResponse>() {
                @Override
                public ExecuteAppFunctionResponse createFromParcel(Parcel parcel) {
                    GenericDocumentWrapper resultWrapper =
                            Objects.requireNonNull(
                                    GenericDocumentWrapper.CREATOR.createFromParcel(parcel));
                    Bundle extras =
                            Objects.requireNonNull(
                                    parcel.readBundle(Bundle.class.getClassLoader()));
                    int resultCode = parcel.readInt();
                    String errorMessage = parcel.readString8();
                    return new ExecuteAppFunctionResponse(
                            resultWrapper, extras, resultCode, errorMessage);
                }

                @Override
                public ExecuteAppFunctionResponse[] newArray(int size) {
                    return new ExecuteAppFunctionResponse[size];
                }
            };

    /**
     * The name of the property that stores the function return value within the {@code
     * resultDocument}.
     *
     * <p>See {@link GenericDocument#getProperty(String)} for more information.
     *
     * <p>If the function returns {@code void} or throws an error, the {@code resultDocument} will
     * be empty {@link GenericDocument}.
     *
     * <p>If the {@code resultDocument} is empty, {@link GenericDocument#getProperty(String)} will
     * return {@code null}.
     *
     * <p>See {@link #getResultDocument} for more information on extracting the return value.
     */
    public static final String PROPERTY_RETURN_VALUE = "returnValue";

    /**
     * The call was successful.
     *
     * <p>This result code does not belong in an error category.
     */
    public static final int RESULT_OK = 0;

    /**
     * The caller does not have the permission to execute an app function.
     *
     * <p>This error is in the {@link #ERROR_CATEGORY_REQUEST_ERROR} category.
     */
    public static final int RESULT_DENIED = 1000;

    /**
     * The caller supplied invalid arguments to the execution request.
     *
     * <p>This error may be considered similar to {@link IllegalArgumentException}.
     *
     * <p>This error is in the {@link #ERROR_CATEGORY_REQUEST_ERROR} category.
     */
    public static final int RESULT_INVALID_ARGUMENT = 1001;

    /**
     * The caller tried to execute a disabled app function.
     *
     * <p>This error is in the request error category.
     *
     * <p>This error is in the {@link #ERROR_CATEGORY_REQUEST_ERROR} category.
     */
    public static final int RESULT_DISABLED = 1002;

    /**
     * An internal unexpected error coming from the system.
     *
     * <p>This error is in the {@link #ERROR_CATEGORY_SYSTEM} category.
     */
    public static final int RESULT_INTERNAL_ERROR = 2000;

    /**
     * The operation was cancelled. Use this error code to report that a cancellation is done after
     * receiving a cancellation signal.
     *
     * <p>This error is in the {@link #ERROR_CATEGORY_SYSTEM} category.
     */
    public static final int RESULT_CANCELLED = 2001;

    /**
     * An unknown error occurred while processing the call in the AppFunctionService.
     *
     * <p>This error is thrown when the service is connected in the remote application but an
     * unexpected error is thrown from the bound application.
     *
     * <p>This error is in the {@link #ERROR_CATEGORY_APP} category.
     */
    public static final int RESULT_APP_UNKNOWN_ERROR = 3000;

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

    /** The result code of the app function execution. */
    @ResultCode private final int mResultCode;

    /**
     * The error message associated with the result, if any. This is {@code null} if the result code
     * is {@link #RESULT_OK}.
     */
    @Nullable private final String mErrorMessage;

    /**
     * Returns the return value of the executed function.
     *
     * <p>The return value is stored in a {@link GenericDocument} with the key {@link
     * #PROPERTY_RETURN_VALUE}.
     *
     * <p>See {@link #getResultDocument} for more information on extracting the return value.
     */
    @NonNull private final GenericDocumentWrapper mResultDocumentWrapper;

    /** Returns the additional metadata data relevant to this function execution response. */
    @NonNull private final Bundle mExtras;

    private ExecuteAppFunctionResponse(
            @NonNull GenericDocumentWrapper resultDocumentWrapper,
            @NonNull Bundle extras,
            @ResultCode int resultCode,
            @Nullable String errorMessage) {
        mResultDocumentWrapper = Objects.requireNonNull(resultDocumentWrapper);
        mExtras = Objects.requireNonNull(extras);
        mResultCode = resultCode;
        mErrorMessage = errorMessage;
    }

    /**
     * Returns result codes from throwable.
     *
     * @hide
     */
    @FlaggedApi(FLAG_ENABLE_APP_FUNCTION_MANAGER)
    static @ResultCode int getResultCode(@NonNull Throwable t) {
        if (t instanceof IllegalArgumentException) {
            return ExecuteAppFunctionResponse.RESULT_INVALID_ARGUMENT;
        }
        return ExecuteAppFunctionResponse.RESULT_APP_UNKNOWN_ERROR;
    }

    /**
     * Returns a successful response.
     *
     * @param resultDocument The return value of the executed function.
     * @param extras The additional metadata for this function execution response.
     */
    @NonNull
    @FlaggedApi(FLAG_ENABLE_APP_FUNCTION_MANAGER)
    public static ExecuteAppFunctionResponse newSuccess(
            @NonNull GenericDocument resultDocument, @Nullable Bundle extras) {
        Objects.requireNonNull(resultDocument);
        Bundle actualExtras = getActualExtras(extras);
        GenericDocumentWrapper resultDocumentWrapper = new GenericDocumentWrapper(resultDocument);

        return new ExecuteAppFunctionResponse(
                resultDocumentWrapper, actualExtras, RESULT_OK, /* errorMessage= */ null);
    }

    /**
     * Returns a failure response.
     *
     * @param resultCode The result code of the app function execution.
     * @param extras The additional metadata for this function execution response.
     * @param errorMessage The error message associated with the result, if any.
     */
    @NonNull
    @FlaggedApi(FLAG_ENABLE_APP_FUNCTION_MANAGER)
    public static ExecuteAppFunctionResponse newFailure(
            @ResultCode int resultCode, @Nullable String errorMessage, @Nullable Bundle extras) {
        if (resultCode == RESULT_OK) {
            throw new IllegalArgumentException("resultCode must not be RESULT_OK");
        }
        Bundle actualExtras = getActualExtras(extras);
        GenericDocumentWrapper emptyWrapper =
                new GenericDocumentWrapper(new GenericDocument.Builder<>("", "", "").build());
        return new ExecuteAppFunctionResponse(emptyWrapper, actualExtras, resultCode, errorMessage);
    }

    private static Bundle getActualExtras(@Nullable Bundle extras) {
        if (extras == null) {
            return Bundle.EMPTY;
        }
        return extras;
    }

    /**
     * Returns the error category of the {@link ExecuteAppFunctionResponse}.
     *
     * <p>This method categorizes errors based on their underlying cause, allowing developers to
     * implement targeted error handling and provide more informative error messages to users. It
     * maps ranges of result codes to specific error categories.
     *
     * <p>When constructing a {@link #newFailure} response, use the appropriate result code value to
     * ensure correct categorization of the failed response.
     *
     * <p>This method returns {@code ERROR_CATEGORY_UNKNOWN} if the result code does not belong to
     * any error category, for example, in the case of a successful result with {@link #RESULT_OK}.
     *
     * <p>See {@link ErrorCategory} for a complete list of error categories and their corresponding
     * result code ranges.
     */
    @ErrorCategory
    public int getErrorCategory() {
        if (mResultCode >= 1000 && mResultCode < 2000) {
            return ERROR_CATEGORY_REQUEST_ERROR;
        }
        if (mResultCode >= 2000 && mResultCode < 3000) {
            return ERROR_CATEGORY_SYSTEM;
        }
        if (mResultCode >= 3000 && mResultCode < 4000) {
            return ERROR_CATEGORY_APP;
        }
        return ERROR_CATEGORY_UNKNOWN;
    }

    /**
     * Returns a generic document containing the return value of the executed function.
     *
     * <p>The {@link #PROPERTY_RETURN_VALUE} key can be used to obtain the return value.
     *
     * <p>An empty document is returned if {@link #isSuccess} is {@code false} or if the executed
     * function does not produce a return value.
     *
     * <p>Sample code for extracting the return value:
     *
     * <pre>
     *     GenericDocument resultDocument = response.getResultDocument();
     *     Object returnValue = resultDocument.getProperty(PROPERTY_RETURN_VALUE);
     *     if (returnValue != null) {
     *       // Cast returnValue to expected type, or use {@link GenericDocument#getPropertyString},
     *       // {@link GenericDocument#getPropertyLong} etc.
     *       // Do something with the returnValue
     *     }
     * </pre>
     *
     * @see AppFunctionManager on how to determine the expected function return.
     */
    @NonNull
    public GenericDocument getResultDocument() {
        return mResultDocumentWrapper.getValue();
    }

    /** Returns the additional metadata for this function execution response. */
    @NonNull
    public Bundle getExtras() {
        return mExtras;
    }

    /**
     * Returns {@code true} if {@link #getResultCode} equals {@link
     * ExecuteAppFunctionResponse#RESULT_OK}.
     */
    public boolean isSuccess() {
        return getResultCode() == RESULT_OK;
    }

    /**
     * Returns one of the {@code RESULT} constants defined in {@link ExecuteAppFunctionResponse}.
     */
    @ResultCode
    public int getResultCode() {
        return mResultCode;
    }

    /**
     * Returns the error message associated with this result.
     *
     * <p>If {@link #isSuccess} is {@code true}, the error message is always {@code null}.
     */
    @Nullable
    public String getErrorMessage() {
        return mErrorMessage;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        mResultDocumentWrapper.writeToParcel(dest, flags);
        dest.writeBundle(mExtras);
        dest.writeInt(mResultCode);
        dest.writeString8(mErrorMessage);
    }

    /**
     * Result codes.
     *
     * @hide
     */
    @IntDef(
            prefix = {"RESULT_"},
            value = {
                RESULT_OK,
                RESULT_DENIED,
                RESULT_APP_UNKNOWN_ERROR,
                RESULT_INTERNAL_ERROR,
                RESULT_INVALID_ARGUMENT,
                RESULT_DISABLED,
                RESULT_CANCELLED
            })
    @Retention(RetentionPolicy.SOURCE)
    public @interface ResultCode {}

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
}
