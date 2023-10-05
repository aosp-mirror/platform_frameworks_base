/*
 * Copyright 2022 The Android Open Source Project
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

package android.credentials;

import android.annotation.NonNull;
import android.os.Parcel;
import android.os.Parcelable;

import com.android.internal.util.Preconditions;

import java.util.List;
import java.util.Objects;

/**
 * Sets the enabled list of credential manager providers.
 *
 * @hide
 */
public final class SetEnabledProvidersRequest implements Parcelable {

    /** List of providers. */
    @NonNull private final List<String> mProviders;

    /**
     * Creates a {@link SetEnabledProvidersRequest} with a list of providers. The list is made up of
     * strings that are flattened component names of the service that is the credman provider.
     *
     * @throws NullPointerException If args are null.
     */
    public SetEnabledProvidersRequest(@NonNull List<String> providers) {
        Objects.requireNonNull(providers, "providers must not be null");
        Preconditions.checkCollectionElementsNotNull(providers, /* valueName= */ "providers");
        mProviders = providers;
    }

    private SetEnabledProvidersRequest(@NonNull Parcel in) {
        mProviders = in.createStringArrayList();
    }

    public static final @NonNull Creator<SetEnabledProvidersRequest> CREATOR =
            new Creator<SetEnabledProvidersRequest>() {
                @Override
                public SetEnabledProvidersRequest createFromParcel(Parcel in) {
                    return new SetEnabledProvidersRequest(in);
                }

                @Override
                public SetEnabledProvidersRequest[] newArray(int size) {
                    return new SetEnabledProvidersRequest[size];
                }
            };

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeStringList(mProviders);
    }

    /** Returns the list of flattened Credential Manager provider component names as strings. */
    public @NonNull List<String> getProviderComponentNames() {
        return mProviders;
    }
}
