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

package android.credentials.ui;

import android.annotation.NonNull;
import android.annotation.TestApi;
import android.os.Parcel;
import android.os.Parcelable;

/**
 * Metadata of a disabled provider.
 *
 * @hide
 */
@TestApi
public final class DisabledProviderData extends ProviderData implements Parcelable {

    public DisabledProviderData(
            @NonNull String providerFlattenedComponentName) {
        super(providerFlattenedComponentName);
    }

    /**
     * Converts the instance to a {@link DisabledProviderInfo}.
     *
     * @hide
     */
    @NonNull
    public DisabledProviderInfo toDisabledProviderInfo() {
        return new DisabledProviderInfo(getProviderFlattenedComponentName());
    }

    private DisabledProviderData(@NonNull Parcel in) {
        super(in);
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        super.writeToParcel(dest, flags);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    public static final @NonNull Creator<DisabledProviderData> CREATOR = new Creator<>() {
                @Override
                public DisabledProviderData createFromParcel(@NonNull Parcel in) {
                    return new DisabledProviderData(in);
                }

                @Override
                public DisabledProviderData[] newArray(int size) {
                    return new DisabledProviderData[size];
                }
    };
}
