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

package com.android.extensions.appfunctions;

import android.annotation.NonNull;
import android.app.appfunctions.AppFunctionManager;
import android.app.appsearch.GenericDocument;
import android.os.Bundle;

import java.util.Objects;

/** The response to an app function execution. */
public final class ExecuteAppFunctionResponse {
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
    public static final String PROPERTY_RETURN_VALUE = "androidAppfunctionsReturnValue";

    /**
     * Returns the return value of the executed function.
     *
     * <p>The return value is stored in a {@link GenericDocument} with the key {@link
     * #PROPERTY_RETURN_VALUE}.
     *
     * <p>See {@link #getResultDocument} for more information on extracting the return value.
     */
    @NonNull private final GenericDocument mResultDocument;

    /** Returns the additional metadata data relevant to this function execution response. */
    @NonNull private final Bundle mExtras;

    /**
     * @param resultDocument The return value of the executed function.
     */
    public ExecuteAppFunctionResponse(@NonNull GenericDocument resultDocument) {
        this(resultDocument, Bundle.EMPTY);
    }

    /**
     * @param resultDocument The return value of the executed function.
     * @param extras The additional metadata for this function execution response.
     */
    public ExecuteAppFunctionResponse(
            @NonNull GenericDocument resultDocument, @NonNull Bundle extras) {
        mResultDocument = Objects.requireNonNull(resultDocument);
        mExtras = Objects.requireNonNull(extras);
    }

    /**
     * Returns a generic document containing the return value of the executed function.
     *
     * <p>The {@link #PROPERTY_RETURN_VALUE} key can be used to obtain the return value.
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
        return mResultDocument;
    }

    /** Returns the additional metadata for this function execution response. */
    @NonNull
    public Bundle getExtras() {
        return mExtras;
    }
}
