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
import android.os.Parcel;
import android.os.Parcelable;
import android.util.ArrayMap;

import com.android.internal.util.Preconditions;

import java.util.Collections;
import java.util.Map;

/**
 * Provides access to multiple {@link AppSearchResult}s from a batch operation accepting multiple
 * inputs.
 *
 * @param <KeyType> The type of the keys for {@link #getSuccesses} and {@link #getFailures}.
 * @param <ValueType> The type of result objects associated with the keys.
 */
public final class AppSearchBatchResult<KeyType, ValueType> implements Parcelable {
    @NonNull private final Map<KeyType, ValueType> mSuccesses;
    @NonNull private final Map<KeyType, AppSearchResult<ValueType>> mFailures;
    @NonNull private final Map<KeyType, AppSearchResult<ValueType>> mAll;

    AppSearchBatchResult(
            @NonNull Map<KeyType, ValueType> successes,
            @NonNull Map<KeyType, AppSearchResult<ValueType>> failures,
            @NonNull Map<KeyType, AppSearchResult<ValueType>> all) {
        mSuccesses = successes;
        mFailures = failures;
        mAll = all;
    }

    private AppSearchBatchResult(@NonNull Parcel in) {
        mAll = Collections.unmodifiableMap(in.readHashMap(/*loader=*/ null));
        Map<KeyType, ValueType> successes = new ArrayMap<>();
        Map<KeyType, AppSearchResult<ValueType>> failures = new ArrayMap<>();
        for (Map.Entry<KeyType, AppSearchResult<ValueType>> entry : mAll.entrySet()) {
            if (entry.getValue().isSuccess()) {
                successes.put(entry.getKey(), entry.getValue().getResultValue());
            } else {
                failures.put(entry.getKey(), entry.getValue());
            }
        }
        mSuccesses = Collections.unmodifiableMap(successes);
        mFailures = Collections.unmodifiableMap(failures);
    }

    /** @hide */
    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeMap(mAll);
    }

    /** Returns {@code true} if this {@link AppSearchBatchResult} has no failures. */
    public boolean isSuccess() {
        return mFailures.isEmpty();
    }

    /**
     * Returns a {@link Map} of all successful keys mapped to the successful {@link
     * AppSearchResult}s they produced.
     *
     * <p>The values of the {@link Map} will not be {@code null}.
     */
    @NonNull
    public Map<KeyType, ValueType> getSuccesses() {
        return mSuccesses;
    }

    /**
     * Returns a {@link Map} of all failed keys mapped to the failed {@link AppSearchResult}s they
     * produced.
     *
     * <p>The values of the {@link Map} will not be {@code null}.
     */
    @NonNull
    public Map<KeyType, AppSearchResult<ValueType>> getFailures() {
        return mFailures;
    }

    /**
     * Returns a {@link Map} of all keys mapped to the {@link AppSearchResult}s they produced.
     *
     * <p>The values of the {@link Map} will not be {@code null}.
     * @hide
     */
    @NonNull
    public Map<KeyType, AppSearchResult<ValueType>> getAll() {
        return mAll;
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

    /** @hide */
    @Override
    public int describeContents() {
        return 0;
    }

    /** @hide */
    @NonNull
    public static final Creator<AppSearchBatchResult> CREATOR =
            new Creator<AppSearchBatchResult>() {
        @NonNull
        @Override
        public AppSearchBatchResult createFromParcel(@NonNull Parcel in) {
            return new AppSearchBatchResult(in);
        }

        @NonNull
        @Override
        public AppSearchBatchResult[] newArray(int size) {
            return new AppSearchBatchResult[size];
        }
    };

    /**
     * Builder for {@link AppSearchBatchResult} objects.
     *
     * @param <KeyType> The type of keys.
     * @param <ValueType> The type of result objects associated with the keys.
     * @hide
     */
    public static final class Builder<KeyType, ValueType> {
        private final Map<KeyType, ValueType> mSuccesses = new ArrayMap<>();
        private final Map<KeyType, AppSearchResult<ValueType>> mFailures = new ArrayMap<>();
        private final Map<KeyType, AppSearchResult<ValueType>> mAll = new ArrayMap<>();
        private boolean mBuilt = false;

        /**
         * Associates the {@code key} with the given successful return value.
         *
         * <p>Any previous mapping for a key, whether success or failure, is deleted.
         */
        @NonNull
        public Builder<KeyType, ValueType> setSuccess(
                @NonNull KeyType key, @Nullable ValueType result) {
            Preconditions.checkState(!mBuilt, "Builder has already been used");
            Preconditions.checkNotNull(key);
            return setResult(key, AppSearchResult.newSuccessfulResult(result));
        }

        /**
         * Associates the {@code key} with the given failure code and error message.
         *
         * <p>Any previous mapping for a key, whether success or failure, is deleted.
         */
        @NonNull
        public Builder<KeyType, ValueType> setFailure(
                @NonNull KeyType key,
                @AppSearchResult.ResultCode int resultCode,
                @Nullable String errorMessage) {
            Preconditions.checkState(!mBuilt, "Builder has already been used");
            Preconditions.checkNotNull(key);
            return setResult(key, AppSearchResult.newFailedResult(resultCode, errorMessage));
        }

        /**
         * Associates the {@code key} with the given {@code result}.
         *
         * <p>Any previous mapping for a key, whether success or failure, is deleted.
         */
        @NonNull
        public Builder<KeyType, ValueType> setResult(
                @NonNull KeyType key, @NonNull AppSearchResult<ValueType> result) {
            Preconditions.checkState(!mBuilt, "Builder has already been used");
            Preconditions.checkNotNull(key);
            Preconditions.checkNotNull(result);
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

        /** Builds an {@link AppSearchBatchResult} from the contents of this {@link Builder}. */
        @NonNull
        public AppSearchBatchResult<KeyType, ValueType> build() {
            Preconditions.checkState(!mBuilt, "Builder has already been used");
            mBuilt = true;
            return new AppSearchBatchResult<>(mSuccesses, mFailures, mAll);
        }
    }
}
