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
import android.annotation.SuppressLint;
import android.annotation.TestApi;
import android.os.Parcel;
import android.os.Parcelable;

import com.android.internal.util.AnnotationValidations;

/**
 * Super class for data structures that hold metadata and credential entries for a single provider.
 *
 * @hide
 */
@TestApi
@SuppressLint({"ParcelCreator", "ParcelNotFinal"})
public abstract class ProviderData implements Parcelable {

    /**
     * The intent extra key for the list of {@code ProviderData} from active providers when
     * launching the UX activities.
     */
    public static final String EXTRA_ENABLED_PROVIDER_DATA_LIST =
            "android.credentials.ui.extra.ENABLED_PROVIDER_DATA_LIST";
    /**
     * The intent extra key for the list of {@code ProviderData} from disabled providers when
     * launching the UX activities.
     */
    public static final String EXTRA_DISABLED_PROVIDER_DATA_LIST =
            "android.credentials.ui.extra.DISABLED_PROVIDER_DATA_LIST";

    @NonNull
    private final String mProviderFlattenedComponentName;

    public ProviderData(
            @NonNull String providerFlattenedComponentName) {
        mProviderFlattenedComponentName = providerFlattenedComponentName;
    }

    /**
     * Returns provider component name.
     * It also serves as the unique identifier for this provider.
     */
    @NonNull
    public String getProviderFlattenedComponentName() {
        return mProviderFlattenedComponentName;
    }

    protected ProviderData(@NonNull Parcel in) {
        String providerFlattenedComponentName = in.readString8();
        mProviderFlattenedComponentName = providerFlattenedComponentName;
        AnnotationValidations.validate(NonNull.class, null, mProviderFlattenedComponentName);
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeString8(mProviderFlattenedComponentName);
    }

    @Override
    public int describeContents() {
        return 0;
    }
}
