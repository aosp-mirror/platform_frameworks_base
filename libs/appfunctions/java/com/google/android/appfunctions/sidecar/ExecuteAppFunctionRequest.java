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

package com.google.android.appfunctions.sidecar;

import android.annotation.NonNull;
import android.app.appsearch.GenericDocument;
import android.os.Bundle;

import java.util.Objects;

/**
 * A request to execute an app function.
 *
 * <p>This class copies {@link android.app.appfunctions.ExecuteAppFunctionRequest} without parcel
 * functionality and exposes it here as a sidecar library (avoiding direct dependency on the
 * platform API).
 */
public final class ExecuteAppFunctionRequest {
    /** Returns the package name of the app that hosts the function. */
    @NonNull private final String mTargetPackageName;

    /**
     * Returns the unique string identifier of the app function to be executed. TODO(b/357551503):
     * Document how callers can get the available function identifiers.
     */
    @NonNull private final String mFunctionIdentifier;

    /** Returns additional metadata relevant to this function execution request. */
    @NonNull private final Bundle mExtras;

    /**
     * Returns the parameters required to invoke this function. Within this [GenericDocument], the
     * property names are the names of the function parameters and the property values are the
     * values of those parameters.
     *
     * <p>The document may have missing parameters. Developers are advised to implement defensive
     * handling measures.
     *
     * <p>TODO(b/357551503): Document how function parameters can be obtained for function execution
     */
    @NonNull private final GenericDocument mParameters;

    private ExecuteAppFunctionRequest(
            @NonNull String targetPackageName,
            @NonNull String functionIdentifier,
            @NonNull Bundle extras,
            @NonNull GenericDocument parameters) {
        mTargetPackageName = Objects.requireNonNull(targetPackageName);
        mFunctionIdentifier = Objects.requireNonNull(functionIdentifier);
        mExtras = Objects.requireNonNull(extras);
        mParameters = Objects.requireNonNull(parameters);
    }

    /** Returns the package name of the app that hosts the function. */
    @NonNull
    public String getTargetPackageName() {
        return mTargetPackageName;
    }

    /** Returns the unique string identifier of the app function to be executed. */
    @NonNull
    public String getFunctionIdentifier() {
        return mFunctionIdentifier;
    }

    /**
     * Returns the function parameters. The key is the parameter name, and the value is the
     * parameter value.
     *
     * <p>The bundle may have missing parameters. Developers are advised to implement defensive
     * handling measures.
     */
    @NonNull
    public GenericDocument getParameters() {
        return mParameters;
    }

    /** Returns the additional data relevant to this function execution. */
    @NonNull
    public Bundle getExtras() {
        return mExtras;
    }

    /** Builder for {@link ExecuteAppFunctionRequest}. */
    public static final class Builder {
        @NonNull private final String mTargetPackageName;
        @NonNull private final String mFunctionIdentifier;
        @NonNull private Bundle mExtras = Bundle.EMPTY;

        @NonNull
        private GenericDocument mParameters = new GenericDocument.Builder<>("", "", "").build();

        public Builder(@NonNull String targetPackageName, @NonNull String functionIdentifier) {
            mTargetPackageName = Objects.requireNonNull(targetPackageName);
            mFunctionIdentifier = Objects.requireNonNull(functionIdentifier);
        }

        /** Sets the additional data relevant to this function execution. */
        @NonNull
        public Builder setExtras(@NonNull Bundle extras) {
            mExtras = Objects.requireNonNull(extras);
            return this;
        }

        /** Sets the function parameters. */
        @NonNull
        public Builder setParameters(@NonNull GenericDocument parameters) {
            Objects.requireNonNull(parameters);
            mParameters = parameters;
            return this;
        }

        /** Builds the {@link ExecuteAppFunctionRequest}. */
        @NonNull
        public ExecuteAppFunctionRequest build() {
            return new ExecuteAppFunctionRequest(
                    mTargetPackageName,
                    mFunctionIdentifier,
                    mExtras,
                    mParameters);
        }
    }
}
