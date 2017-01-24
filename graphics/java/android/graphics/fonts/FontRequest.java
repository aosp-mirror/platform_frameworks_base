/*
 * Copyright (C) 2017 The Android Open Source Project
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
package android.graphics.fonts;

import android.annotation.NonNull;
import android.os.Parcel;
import android.os.Parcelable;

import com.android.internal.util.Preconditions;

/**
 * Information about a font request that may be sent to a Font Provider.
 */
public final class FontRequest implements Parcelable {
    private final String mProviderAuthority;
    private final String mQuery;

    /**
     * @param providerAuthority The authority of the Font Provider to be used for the request.
     * @param query The query to be sent over to the provider. Refer to your font provider's
     *              documentation on the format of this string.
     */
    public FontRequest(@NonNull String providerAuthority, @NonNull String query) {
        mProviderAuthority = Preconditions.checkNotNull(providerAuthority);
        mQuery = Preconditions.checkNotNull(query);
    }

    /**
     * Returns the selected font provider's authority. This tells the system what font provider
     * it should request the font from.
     */
    public String getProviderAuthority() {
        return mProviderAuthority;
    }

    /**
     * Returns the query string. Refer to your font provider's documentation on the format of this
     * string.
     */
    public String getQuery() {
        return mQuery;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(mProviderAuthority);
        dest.writeString(mQuery);
    }

    private FontRequest(Parcel in) {
        mProviderAuthority = in.readString();
        mQuery = in.readString();
    }

    public static final Parcelable.Creator<FontRequest> CREATOR =
            new Parcelable.Creator<FontRequest>() {
                @Override
                public FontRequest createFromParcel(Parcel in) {
                    return new FontRequest(in);
                }

                @Override
                public FontRequest[] newArray(int size) {
                    return new FontRequest[size];
                }
            };

    @Override
    public String toString() {
        return "FontRequest {"
                + "mProviderAuthority: " + mProviderAuthority
                + ", mQuery: " + mQuery
                + "}";
    }
}
