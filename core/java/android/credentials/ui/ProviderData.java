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
import android.os.Parcel;
import android.os.Parcelable;

import com.android.internal.util.AnnotationValidations;

/**
 * Holds metadata and credential entries for a single provider.
 *
 * @hide
 */
public class ProviderData implements Parcelable {

    /**
     * The intent extra key for the list of {@code ProviderData} when launching the UX
     * activities.
     */
    public static final String EXTRA_PROVIDER_DATA_LIST =
            "android.credentials.ui.extra.PROVIDER_DATA_LIST";

    // TODO: add entry data.

    @NonNull
    private final String mPackageName;

    public ProviderData(@NonNull String packageName) {
        mPackageName = packageName;
    }

    /** Returns the provider package name. */
    @NonNull
    public String getPackageName() {
        return mPackageName;
    }

    protected ProviderData(@NonNull Parcel in) {
        String packageName = in.readString8();
        mPackageName = packageName;
        AnnotationValidations.validate(NonNull.class, null, mPackageName);
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeString8(mPackageName);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    public static final @NonNull Creator<ProviderData> CREATOR = new Creator<ProviderData>() {
        @Override
        public ProviderData createFromParcel(@NonNull Parcel in) {
            return new ProviderData(in);
        }

        @Override
        public ProviderData[] newArray(int size) {
            return new ProviderData[size];
        }
    };
}
