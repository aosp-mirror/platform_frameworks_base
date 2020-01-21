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

package android.app.appsearch;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.ArrayMap;

import java.util.Collections;
import java.util.Map;

/**
 * Provides access to multiple results from a batch operation accepting multiple inputs.
 *
 * @param <KeyType> The type of the keys for {@link #getResults} and {@link #getFailures}.
 * @param <ValueType> The type of result objects associated with the keys.
 * @hide
 */
public class AppSearchBatchResult<KeyType, ValueType> implements Parcelable {
    @NonNull private final Map<KeyType, ValueType> mResults;
    @NonNull private final Map<KeyType, Throwable> mFailures;

    private AppSearchBatchResult(
            @NonNull Map<KeyType, ValueType> results, @NonNull Map<KeyType, Throwable> failures) {
        mResults = results;
        mFailures = failures;
    }

    private AppSearchBatchResult(@NonNull Parcel in) {
        mResults = Collections.unmodifiableMap(in.readHashMap(/*loader=*/ null));
        mFailures = Collections.unmodifiableMap(in.readHashMap(/*loader=*/ null));
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeMap(mResults);
        dest.writeMap(mFailures);
    }

    /** Returns {@code true} if this {@link AppSearchBatchResult} has no failures. */
    public boolean isSuccess() {
        return mFailures.isEmpty();
    }

    /**
     * Returns a {@link Map} of all successful keys mapped to the results they produced.
     *
     * <p>The values of the {@link Map} may be {@code null}.
     */
    @NonNull
    public Map<KeyType, ValueType> getResults() {
        return mResults;
    }

    /**
     * Returns a {@link Map} of all failed keys mapped to a {@link Throwable} representing the cause
     * of failure.
     *
     * <p>The values of the {@link Map} may be {@code null}.
     */
    @NonNull
    public Map<KeyType, Throwable> getFailures() {
        return mFailures;
    }

    @Override
    public int describeContents() {
        return 0;
    }

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
     * Creates a new {@link Builder} for this {@link AppSearchBatchResult}.
     * @hide
     */
    @NonNull
    public static <KeyType, ValueType> Builder<KeyType, ValueType> newBuilder() {
        return new Builder<>();
    }

    /**
     * Builder for {@link AppSearchBatchResult} objects.
     *
     * @param <KeyType> The type of keys.
     * @param <ValueType> The type of result objects associated with the keys.
     * @hide
     */
    public static final class Builder<KeyType, ValueType> {
        @NonNull private final Map<KeyType, ValueType> mResults = new ArrayMap<>();
        @NonNull private final Map<KeyType, Throwable> mFailures = new ArrayMap<>();

        private Builder() {}

        /**
         * Registers that the {@code key} was processed successfully and associates it with
         * {@code value}. Any previous mapping for a key, whether success or failure, is deleted.
         */
        public Builder setSuccess(@NonNull KeyType key, @Nullable ValueType value) {
            mResults.put(key, value);
            mFailures.remove(key);
            return this;
        }

        /**
         * Registers that the {@code key} failed and associates it with {@code throwable}. Any
         * previous mapping for a key, whether success or failure, is deleted.
         */
        public Builder setFailure(@NonNull KeyType key, @Nullable Throwable throwable) {
            mFailures.put(key, throwable);
            mResults.remove(key);
            return this;
        }

        /** Builds an {@link AppSearchBatchResult} from the contents of this {@link Builder}. */
        @NonNull
        public AppSearchBatchResult<KeyType, ValueType> build() {
            return new AppSearchBatchResult<>(mResults, mFailures);
        }
    }
}
