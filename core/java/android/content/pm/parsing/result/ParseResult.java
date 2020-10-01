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

import android.annotation.Nullable;

/**
 * The output side of {@link ParseInput}, which must result from a method call on
 * {@link ParseInput}.
 *
 * When using this class, keep in mind that all {@link ParseInput}s and {@link ParseResult}s
 * are the exact same object, scoped per thread, thrown around and casted for type safety.
 *
 * @hide
 */
public interface ParseResult<ResultType> {

    /**
     * Returns true if the result is not an error and thus contains a valid object.
     *
     * For backwards-compat reasons, it's possible to have a successful result with a null
     * result object, depending on the behavior of the parsing method.
     *
     * It is expected that every method calls this to check for an error state to bubble up
     * the error to its parent method after every parse method call.
     *
     * It is not always necessary to check this, as it is valid to return any ParseResult from
     * a method so long as the type matches <b>without casting it</b>.
     *
     * The infrastructure is set up such that as long as a result is the proper type and
     * the right side of success vs. error, it can be bubble up through all its parent methods.
     */
    boolean isSuccess();

    /**
     * Opposite of {@link #isSuccess()} for readability.
     */
    boolean isError();

    ResultType getResult();

    int getErrorCode();

    @Nullable
    String getErrorMessage();

    @Nullable
    Exception getException();
}
