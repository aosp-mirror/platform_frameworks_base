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

package com.android.server.appsearch;

import android.annotation.Nullable;
import android.app.appsearch.AppSearchResult;

/**
 * An exception thrown by {@link com.android.server.appsearch.AppSearchManagerService} or a
 * subcomponent.
 *
 * <p>These exceptions can be converted into a failed {@link android.app.appsearch.AppSearchResult}
 * for propagating to the client.
 */
public class AppSearchException extends Exception {
    private final @AppSearchResult.ResultCode int mResultCode;

    /** Initializes an {@link com.android.server.appsearch.AppSearchException} with no message. */
    public AppSearchException(@AppSearchResult.ResultCode int resultCode) {
        this(resultCode, /*message=*/ null);
    }

    public AppSearchException(
            @AppSearchResult.ResultCode int resultCode, @Nullable String message) {
        this(resultCode, message, /*cause=*/ null);
    }

    public AppSearchException(
            @AppSearchResult.ResultCode int resultCode,
            @Nullable String message,
            @Nullable Throwable cause) {
        super(message, cause);
        mResultCode = resultCode;
    }

    /**
     * Converts this {@link java.lang.Exception} into a failed
     * {@link android.app.appsearch.AppSearchResult}
     */
    public <T> AppSearchResult<T> toAppSearchResult() {
        return AppSearchResult.newFailedResult(mResultCode, getMessage());
    }
}
