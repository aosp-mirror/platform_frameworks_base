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

package com.android.server.appfunctions;

import android.app.appsearch.AppSearchResult;

/** Exception to wrap failure result codes returned by AppSearch. */
public class AppSearchException extends RuntimeException {
    private final int resultCode;

    public AppSearchException(int resultCode, String message) {
        super(message);
        this.resultCode = resultCode;
    }

    /**
     * Returns the result code used to create this exception, typically one of the {@link
     * AppSearchResult} result codes.
     */
    public int getResultCode() {
        return resultCode;
    }
}
