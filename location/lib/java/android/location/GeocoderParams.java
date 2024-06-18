/*
 * Copyright (C) 2010 The Android Open Source Project
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

package android.location;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.content.Context;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.Process;

import java.util.Locale;
import java.util.Objects;

/**
 * This class was originally shipped out-of-band from the normal API processes as a separate drop
 * before SystemApi existed. Now that SystemApi does exist, this class has been retroactively
 * published through SystemApi.
 *
 * @deprecated Do not use.
 * @hide
 */
@Deprecated
@SystemApi
public class GeocoderParams implements Parcelable {

    private final int mUid;
    private final String mPackageName;
    private final @Nullable String mAttributionTag;
    private final Locale mLocale;

    public GeocoderParams(Context context) {
        this(context, Locale.getDefault());
    }

    public GeocoderParams(Context context, Locale locale) {
        this(Process.myUid(), context.getPackageName(), context.getAttributionTag(), locale);
    }

    public GeocoderParams(
            int uid, String packageName, @Nullable String attributionTag, Locale locale) {
        mUid = uid;
        mPackageName = Objects.requireNonNull(packageName);
        mAttributionTag = attributionTag;
        mLocale = Objects.requireNonNull(locale);
    }

    /**
     * Returns the client UID.
     */
    public int getClientUid() {
        return mUid;
    }

    /**
     * Returns the client package name.
     */
    public @NonNull String getClientPackage() {
        return mPackageName;
    }

    /**
     * Returns the client attribution tag.
     */
    public @Nullable String getClientAttributionTag() {
        return mAttributionTag;
    }

    /**
     * Returns the locale.
     */
    public @NonNull Locale getLocale() {
        return mLocale;
    }

    public static final @NonNull Parcelable.Creator<GeocoderParams> CREATOR =
            new Parcelable.Creator<>() {
                public GeocoderParams createFromParcel(Parcel in) {
                    int uid = in.readInt();
                    String packageName = in.readString8();
                    String attributionTag = in.readString8();
                    String language = in.readString8();
                    String country = in.readString8();
                    String variant = in.readString8();

                    return new GeocoderParams(
                            uid,
                            packageName,
                            attributionTag,
                            new Locale(language, country, variant));
                }

                public GeocoderParams[] newArray(int size) {
                    return new GeocoderParams[size];
                }
            };

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel parcel, int flags) {
        parcel.writeInt(mUid);
        parcel.writeString8(mPackageName);
        parcel.writeString8(mAttributionTag);
        parcel.writeString8(mLocale.getLanguage());
        parcel.writeString8(mLocale.getCountry());
        parcel.writeString8(mLocale.getVariant());
    }
}
