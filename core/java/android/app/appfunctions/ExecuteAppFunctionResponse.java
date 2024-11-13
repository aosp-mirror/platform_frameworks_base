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

    /** The call was successful. */
    public static final int RESULT_OK = 0;

    /** The caller does not have the permission to execute an app function. */
    public static final int RESULT_DENIED = 1;

    /** An unknown error occurred while processing the call in the AppFunctionService. */
    public static final int RESULT_APP_UNKNOWN_ERROR = 2;

    /**
     * An internal error occurred within AppFunctionManagerService.
     *
     * <p>This error may be considered similar to {@link IllegalStateException}
     */
    public static final int RESULT_INTERNAL_ERROR = 3;

    /**
     * The caller supplied invalid arguments to the call.
     *
     * <p>This error may be considered similar to {@link IllegalArgumentException}.
     */
    public static final int RESULT_INVALID_ARGUMENT = 4;

    /** The operation was timed out. */
    public static final int RESULT_TIMED_OUT = 5;

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
     * @param extras The additional metadata data relevant to this function execution response.
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
     * @param extras The additional metadata data relevant to this function execution response.
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
     */
    @NonNull
    public GenericDocument getResultDocument() {
        return mResultDocumentWrapper.getValue();
    }

    /** Returns the extras of the app function execution. */
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
                RESULT_TIMED_OUT,
            })
    @Retention(RetentionPolicy.SOURCE)
    public @interface ResultCode {}
}
