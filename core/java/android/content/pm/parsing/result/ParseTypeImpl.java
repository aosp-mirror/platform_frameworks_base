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

package android.content.pm.parsing.result;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.pm.PackageManager;
import android.content.pm.parsing.ParsingUtils;
import android.util.Log;

import java.util.Arrays;

/** @hide */
public class ParseTypeImpl implements ParseInput, ParseResult<Object> {

    private static final String TAG = ParsingUtils.TAG;

    private static final boolean DEBUG_FILL_STACK_TRACE = false;

    private static final boolean DEBUG_LOG_ON_ERROR = false;

    private Object result;

    private int errorCode = PackageManager.INSTALL_SUCCEEDED;

    @Nullable
    private String errorMessage;

    @Nullable
    private Exception exception;

    public ParseInput reset() {
        this.result = null;
        this.errorCode = PackageManager.INSTALL_SUCCEEDED;
        this.errorMessage = null;
        this.exception = null;
        return this;
    }

    @Override
    public void ignoreError() {
        reset();
    }

    @Override
    public <ResultType> ParseResult<ResultType> success(ResultType result) {
        if (errorCode != PackageManager.INSTALL_SUCCEEDED || errorMessage != null) {
            throw new IllegalStateException("Cannot set to success after set to error, was "
                    + errorMessage, exception);
        }
        this.result = result;
        //noinspection unchecked
        return (ParseResult<ResultType>) this;
    }

    @Override
    public <ResultType> ParseResult<ResultType> error(int parseError) {
        return error(parseError, null);
    }

    @Override
    public <ResultType> ParseResult<ResultType> error(@NonNull String parseError) {
        return error(PackageManager.INSTALL_PARSE_FAILED_MANIFEST_MALFORMED, parseError);
    }

    @Override
    public <ResultType> ParseResult<ResultType> error(int errorCode,
            @Nullable String errorMessage) {
        return error(errorCode, errorMessage, null);
    }

    @Override
    public <ResultType> ParseResult<ResultType> error(ParseResult intentResult) {
        return error(intentResult.getErrorCode(), intentResult.getErrorMessage());
    }

    @Override
    public <ResultType> ParseResult<ResultType> error(int errorCode, @Nullable String errorMessage,
            Exception exception) {
        this.errorCode = errorCode;
        this.errorMessage = errorMessage;
        this.exception = exception;

        if (DEBUG_FILL_STACK_TRACE) {
            if (exception == null) {
                this.exception = new Exception();
            }
        }

        if (DEBUG_LOG_ON_ERROR) {
            Exception exceptionToLog = this.exception != null ? this.exception : new Exception();
            Log.w(TAG, "ParseInput set to error " + errorCode + ", " + errorMessage,
                    exceptionToLog);
        }

        //noinspection unchecked
        return (ParseResult<ResultType>) this;
    }

    @Override
    public Object getResult() {
        return this.result;
    }

    @Override
    public boolean isSuccess() {
        return errorCode == PackageManager.INSTALL_SUCCEEDED;
    }

    @Override
    public boolean isError() {
        return !isSuccess();
    }

    @Override
    public int getErrorCode() {
        return errorCode;
    }

    @Nullable
    @Override
    public String getErrorMessage() {
        return errorMessage;
    }

    @Nullable
    @Override
    public Exception getException() {
        return exception;
    }
}
