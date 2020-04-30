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
import android.util.ArrayMap;
import android.util.Log;
import android.util.Slog;

import com.android.internal.util.CollectionUtils;

/** @hide */
public class ParseTypeImpl implements ParseInput, ParseResult<Object> {

    private static final String TAG = ParsingUtils.TAG;

    public static final boolean DEBUG_FILL_STACK_TRACE = false;

    public static final boolean DEBUG_LOG_ON_ERROR = false;

    public static final boolean DEBUG_THROW_ALL_ERRORS = false;

    @NonNull
    private Callback mCallback;

    private Object mResult;

    private int mErrorCode = PackageManager.INSTALL_SUCCEEDED;

    @Nullable
    private String mErrorMessage;

    @Nullable
    private Exception mException;

    /**
     * Errors encountered before targetSdkVersion is known.
     * The size upper bound is the number of longs in {@link DeferredError}
     */
    @Nullable
    private ArrayMap<Long, String> mDeferredErrors = null;

    private String mPackageName;
    private Integer mTargetSdkVersion;

    /**
     * @param callback if nullable, fallback to manual targetSdk > Q check
     */
    public ParseTypeImpl(@NonNull Callback callback) {
        mCallback = callback;
    }

    public ParseInput reset() {
        mResult = null;
        mErrorCode = PackageManager.INSTALL_SUCCEEDED;
        mErrorMessage = null;
        mException = null;
        if (mDeferredErrors != null) {
            // If the memory was already allocated, don't bother freeing and re-allocating,
            // as this could occur hundreds of times depending on what the caller is doing and
            // how many APKs they're going through.
            mDeferredErrors.erase();
        }
        return this;
    }

    @Override
    public <ResultType> ParseResult<ResultType> success(ResultType result) {
        if (mErrorCode != PackageManager.INSTALL_SUCCEEDED) {
            Slog.wtf(ParsingUtils.TAG, "Cannot set to success after set to error, was "
                    + mErrorMessage, mException);
        }
        mResult = result;
        //noinspection unchecked
        return (ParseResult<ResultType>) this;
    }

    @Override
    public ParseResult<?> deferError(@NonNull String parseError, long deferredError) {
        if (DEBUG_THROW_ALL_ERRORS) {
            return error(parseError);
        }
        if (mTargetSdkVersion != null) {
            if (mDeferredErrors != null && mDeferredErrors.containsKey(deferredError)) {
                // If the map already contains the key, that means it's already been checked and
                // found to be disabled. Otherwise it would've failed when mTargetSdkVersion was
                // set to non-null.
                return success(null);
            }

            if (mCallback.isChangeEnabled(deferredError, mPackageName, mTargetSdkVersion)) {
                return error(parseError);
            } else {
                if (mDeferredErrors == null) {
                    mDeferredErrors = new ArrayMap<>();
                }
                mDeferredErrors.put(deferredError, null);
                return success(null);
            }
        }

        if (mDeferredErrors == null) {
            mDeferredErrors = new ArrayMap<>();
        }

        // Only save the first occurrence of any particular error
        mDeferredErrors.putIfAbsent(deferredError, parseError);
        return success(null);
    }

    @Override
    public ParseResult<?> enableDeferredError(String packageName, int targetSdkVersion) {
        mPackageName = packageName;
        mTargetSdkVersion = targetSdkVersion;

        int size = CollectionUtils.size(mDeferredErrors);
        for (int index = size - 1; index >= 0; index--) {
            long changeId = mDeferredErrors.keyAt(index);
            String errorMessage = mDeferredErrors.valueAt(index);
            if (mCallback.isChangeEnabled(changeId, mPackageName, mTargetSdkVersion)) {
                return error(errorMessage);
            } else {
                // No point holding onto the string, but need to maintain the key to signal
                // that the error was checked with isChangeEnabled and found to be disabled.
                mDeferredErrors.setValueAt(index, null);
            }
        }

        return success(null);
    }

    @Override
    public <ResultType> ParseResult<ResultType> skip(@NonNull String parseError) {
        return error(PackageManager.INSTALL_PARSE_FAILED_SKIPPED, parseError);
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
    public <ResultType> ParseResult<ResultType> error(ParseResult<?> intentResult) {
        return error(intentResult.getErrorCode(), intentResult.getErrorMessage(),
                intentResult.getException());
    }

    @Override
    public <ResultType> ParseResult<ResultType> error(int errorCode, @Nullable String errorMessage,
            Exception exception) {
        mErrorCode = errorCode;
        mErrorMessage = errorMessage;
        mException = exception;

        if (DEBUG_FILL_STACK_TRACE) {
            if (exception == null) {
                mException = new Exception();
            }
        }

        if (DEBUG_LOG_ON_ERROR) {
            Exception exceptionToLog = mException != null ? mException : new Exception();
            Log.w(TAG, "ParseInput set to error " + errorCode + ", " + errorMessage,
                    exceptionToLog);
        }

        //noinspection unchecked
        return (ParseResult<ResultType>) this;
    }

    @Override
    public Object getResult() {
        return mResult;
    }

    @Override
    public boolean isSuccess() {
        return mErrorCode == PackageManager.INSTALL_SUCCEEDED;
    }

    @Override
    public boolean isError() {
        return !isSuccess();
    }

    @Override
    public int getErrorCode() {
        return mErrorCode;
    }

    @Nullable
    @Override
    public String getErrorMessage() {
        return mErrorMessage;
    }

    @Nullable
    @Override
    public Exception getException() {
        return mException;
    }
}
