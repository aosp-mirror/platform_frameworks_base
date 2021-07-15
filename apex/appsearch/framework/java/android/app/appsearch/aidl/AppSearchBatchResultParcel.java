/*
 * Copyright (C) 2021 The Android Open Source Project
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

package android.app.appsearch.aidl;

import android.annotation.NonNull;
import android.app.appsearch.AppSearchBatchResult;
import android.app.appsearch.AppSearchResult;
import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;

import java.util.Map;
import java.util.Objects;

/**
 * Parcelable wrapper around {@link AppSearchBatchResult}.
 *
 * <p>{@link AppSearchBatchResult} can contain any type of key and value, including non-parcelable
 * values. For the specific case of sending {@link AppSearchBatchResult} across Binder, this class
 * wraps an {@link AppSearchBatchResult} that has String keys and Parcelable values. It provides
 * parcelability of the whole structure.
 *
 * @param <ValueType> The type of result object for successful calls. Must be a parcelable type.
 * @hide
 */
public final class AppSearchBatchResultParcel<ValueType> implements Parcelable {
    private final AppSearchBatchResult<String, ValueType> mResult;

    /** Creates a new {@link AppSearchBatchResultParcel} from the given result. */
    public AppSearchBatchResultParcel(@NonNull AppSearchBatchResult<String, ValueType> result) {
        mResult = Objects.requireNonNull(result);
    }

    private AppSearchBatchResultParcel(@NonNull Parcel in) {
        Bundle bundle = in.readBundle();
        AppSearchBatchResult.Builder<String, ValueType> builder =
                new AppSearchBatchResult.Builder<>();
        for (String key : bundle.keySet()) {
            AppSearchResultParcel<ValueType> resultParcel = bundle.getParcelable(key);
            builder.setResult(key, resultParcel.getResult());
        }
        mResult = builder.build();
    }

    @NonNull
    public AppSearchBatchResult<String, ValueType> getResult() {
        return mResult;
    }

    /** @hide */
    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        Bundle bundle = new Bundle();
        for (Map.Entry<String, AppSearchResult<ValueType>> entry
                : mResult.getAll().entrySet()) {
            bundle.putParcelable(entry.getKey(), new AppSearchResultParcel<>(entry.getValue()));
        }
        dest.writeBundle(bundle);
    }

    /** @hide */
    @Override
    public int describeContents() {
        return 0;
    }

    /** @hide */
    @NonNull
    public static final Creator<AppSearchBatchResultParcel<?>> CREATOR =
            new Creator<AppSearchBatchResultParcel<?>>() {
                @NonNull
                @Override
                public AppSearchBatchResultParcel<?> createFromParcel(@NonNull Parcel in) {
                    return new AppSearchBatchResultParcel<>(in);
                }

                @NonNull
                @Override
                public AppSearchBatchResultParcel<?>[] newArray(int size) {
                    return new AppSearchBatchResultParcel<?>[size];
                }
            };
}
