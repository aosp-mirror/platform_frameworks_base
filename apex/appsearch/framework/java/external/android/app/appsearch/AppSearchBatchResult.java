/*
 * Copyright 2020 The Android Open Source Project
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
package android.app.appsearch;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.util.ArrayMap;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;

/**
 * Provides results for AppSearch batch operations which encompass multiple documents.
 *
 * <p>Individual results of a batch operation are separated into two maps: one for successes and one
 * for failures. For successes, {@link #getSuccesses()} will return a map of keys to instances of
 * the value type. For failures, {@link #getFailures()} will return a map of keys to {@link
 * AppSearchResult} objects.
 *
 * <p>Alternatively, {@link #getAll()} returns a map of keys to {@link AppSearchResult} objects for
 * both successes and failures.
 *
 * @param <KeyType> The type of the keys for which the results will be reported.
 * @param <ValueType> The type of the result objects for successful results.
 * @see AppSearchSession#put
 * @see AppSearchSession#getByDocumentId
 * @see AppSearchSession#remove
 */
public final class AppSearchBatchResult<KeyType, ValueType> {
    @NonNull private final Map<KeyType, ValueType> mSuccesses;
    @NonNull private final Map<KeyType, AppSearchResult<ValueType>> mFailures;
    @NonNull private final Map<KeyType, AppSearchResult<ValueType>> mAll;

    AppSearchBatchResult(
            @NonNull Map<KeyType, ValueType> successes,
            @NonNull Map<KeyType, AppSearchResult<ValueType>> failures,
            @NonNull Map<KeyType, AppSearchResult<ValueType>> all) {
        mSuccesses = Objects.requireNonNull(successes);
        mFailures = Objects.requireNonNull(failures);
        mAll = Objects.requireNonNull(all);
    }

    /** Returns {@code true} if this {@link AppSearchBatchResult} has no failures. */
    public boolean isSuccess() {
        return mFailures.isEmpty();
    }

    /**
     * Returns a {@link Map} of keys mapped to instances of the value type for all successful
     * individual results.
     *
     * <p>Example: {@link AppSearchSession#getByDocumentId} returns an {@link AppSearchBatchResult}.
     * Each key (the document ID, of {@code String} type) will map to a {@link GenericDocument}
     * object.
     *
     * <p>The values of the {@link Map} will not be {@code null}.
     */
    @NonNull
    public Map<KeyType, ValueType> getSuccesses() {
        return Collections.unmodifiableMap(mSuccesses);
    }

    /**
     * Returns a {@link Map} of keys mapped to instances of {@link AppSearchResult} for all failed
     * individual results.
     *
     * <p>The values of the {@link Map} will not be {@code null}.
     */
    @NonNull
    public Map<KeyType, AppSearchResult<ValueType>> getFailures() {
        return Collections.unmodifiableMap(mFailures);
    }

    /**
     * Returns a {@link Map} of keys mapped to instances of {@link AppSearchResult} for all
     * individual results.
     *
     * <p>The values of the {@link Map} will not be {@code null}.
     */
    @NonNull
    public Map<KeyType, AppSearchResult<ValueType>> getAll() {
        return Collections.unmodifiableMap(mAll);
    }

    /**
     * Asserts that this {@link AppSearchBatchResult} has no failures.
     *
     * @hide
     */
    public void checkSuccess() {
        if (!isSuccess()) {
            throw new IllegalStateException("AppSearchBatchResult has failures: " + this);
        }
    }

    @Override
    @NonNull
    public String toString() {
        return "{\n  successes: " + mSuccesses + "\n  failures: " + mFailures + "\n}";
    }

    /**
     * Builder for {@link AppSearchBatchResult} objects.
     *
     * @param <KeyType> The type of the keys for which the results will be reported.
     * @param <ValueType> The type of the result objects for successful results.
     */
    public static final class Builder<KeyType, ValueType> {
        private ArrayMap<KeyType, ValueType> mSuccesses = new ArrayMap<>();
        private ArrayMap<KeyType, AppSearchResult<ValueType>> mFailures = new ArrayMap<>();
        private ArrayMap<KeyType, AppSearchResult<ValueType>> mAll = new ArrayMap<>();
        private boolean mBuilt = false;

        /**
         * Associates the {@code key} with the provided successful return value.
         *
         * <p>Any previous mapping for a key, whether success or failure, is deleted.
         *
         * <p>This is a convenience function which is equivalent to {@code setResult(key,
         * AppSearchResult.newSuccessfulResult(value))}.
         *
         * @param key The key to associate the result with; usually corresponds to some identifier
         *     from the input like an ID or name.
         * @param value An optional value to associate with the successful result of the operation
         *     being performed.
         */
        @SuppressWarnings("MissingGetterMatchingBuilder") // See getSuccesses
        @NonNull
        public Builder<KeyType, ValueType> setSuccess(
                @NonNull KeyType key, @Nullable ValueType value) {
            Objects.requireNonNull(key);
            resetIfBuilt();
            return setResult(key, AppSearchResult.newSuccessfulResult(value));
        }

        /**
         * Associates the {@code key} with the provided failure code and error message.
         *
         * <p>Any previous mapping for a key, whether success or failure, is deleted.
         *
         * <p>This is a convenience function which is equivalent to {@code setResult(key,
         * AppSearchResult.newFailedResult(resultCode, errorMessage))}.
         *
         * @param key The key to associate the result with; usually corresponds to some identifier
         *     from the input like an ID or name.
         * @param resultCode One of the constants documented in {@link
         *     AppSearchResult#getResultCode}.
         * @param errorMessage An optional string describing the reason or nature of the failure.
         */
        @SuppressWarnings("MissingGetterMatchingBuilder") // See getFailures
        @NonNull
        public Builder<KeyType, ValueType> setFailure(
                @NonNull KeyType key,
                @AppSearchResult.ResultCode int resultCode,
                @Nullable String errorMessage) {
            Objects.requireNonNull(key);
            resetIfBuilt();
            return setResult(key, AppSearchResult.newFailedResult(resultCode, errorMessage));
        }

        /**
         * Associates the {@code key} with the provided {@code result}.
         *
         * <p>Any previous mapping for a key, whether success or failure, is deleted.
         *
         * @param key The key to associate the result with; usually corresponds to some identifier
         *     from the input like an ID or name.
         * @param result The result to associate with the key.
         */
        @SuppressWarnings("MissingGetterMatchingBuilder") // See getAll
        @NonNull
        public Builder<KeyType, ValueType> setResult(
                @NonNull KeyType key, @NonNull AppSearchResult<ValueType> result) {
            Objects.requireNonNull(key);
            Objects.requireNonNull(result);
            resetIfBuilt();
            if (result.isSuccess()) {
                mSuccesses.put(key, result.getResultValue());
                mFailures.remove(key);
            } else {
                mFailures.put(key, result);
                mSuccesses.remove(key);
            }
            mAll.put(key, result);
            return this;
        }

        /**
         * Builds an {@link AppSearchBatchResult} object from the contents of this {@link Builder}.
         */
        @NonNull
        public AppSearchBatchResult<KeyType, ValueType> build() {
            mBuilt = true;
            return new AppSearchBatchResult<>(mSuccesses, mFailures, mAll);
        }

        private void resetIfBuilt() {
            if (mBuilt) {
                mSuccesses = new ArrayMap<>(mSuccesses);
                mFailures = new ArrayMap<>(mFailures);
                mAll = new ArrayMap<>(mAll);
                mBuilt = false;
            }
        }
    }
}
