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

import android.annotation.Hide;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.pm.PackageManager;

/**
 * Used as a method parameter which is then transformed into a {@link ParseResult}. This is
 * generalized as it doesn't matter what type this input is for. It's simply to hide the
 * methods of {@link ParseResult}.
 *
 * @hide
 */
public interface ParseInput {

    <ResultType> ParseResult<ResultType> success(ResultType result);

    /** @see #error(int, String, Exception) */
    <ResultType> ParseResult<ResultType> error(int parseError);

    /**
     * This will assign errorCode to {@link PackageManager#INSTALL_PARSE_FAILED_MANIFEST_MALFORMED}.
     * @see #error(int, String, Exception)
     */
    <ResultType> ParseResult<ResultType> error(@NonNull String parseError);

    /** @see #error(int, String, Exception) */
    <ResultType> ParseResult<ResultType> error(int parseError, @Nullable String errorMessage);

    /**
     * Marks this as an error result. When this method is called, the return value <b>must</b>
     * be returned to the exit of the parent method that took in this {@link ParseInput} as a
     * parameter.
     *
     * The calling site of that method is then expected to check the result for error, and
     * continue to bubble up if it is an error.
     *
     * Or, if the code explicitly handles an error,
     * {@link ParseResult#ignoreError()} should be called.
     *
     * If the result {@link ParseResult#isSuccess()}, then it can be used as-is, as
     * overlapping/consecutive successes are allowed.
     */
    <ResultType> ParseResult<ResultType> error(int parseError, @Nullable String errorMessage,
            @Nullable Exception exception);

    /**
     * Moves the error in {@param result} to this input's type. In practice this does nothing
     * but cast the type of the {@link ParseResult} for type safety, since the parameter
     * and the receiver should be the same object.
     */
    <ResultType> ParseResult<ResultType> error(ParseResult result);
}
