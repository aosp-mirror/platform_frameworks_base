/*
 * Copyright (C) 2014 The Android Open Source Project
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

package android.media.tv;

import android.annotation.SystemApi;
import android.content.ContentResolver;
import android.content.pm.ApplicationInfo;
import android.net.Uri;
import android.os.Parcel;
import android.os.Parcelable;

/**
 * TvContentRatingSystemInfo class provides information about a specific TV content rating system
 * defined either by a system app or by a third-party app.
 *
 * @hide
 */
@SystemApi
public final class TvContentRatingSystemInfo implements Parcelable {
    private final Uri mXmlUri;

    private final ApplicationInfo mApplicationInfo;

    /**
     * Creates a TvContentRatingSystemInfo object with given resource ID and receiver info.
     *
     * @param xmlResourceId The ID of an XML resource whose root element is
     *            <code> &lt;rating-system-definitions&gt;</code>
     * @param applicationInfo Information about the application that provides the TV content rating
     *            system definition.
     */
    public static final TvContentRatingSystemInfo createTvContentRatingSystemInfo(int xmlResourceId,
            ApplicationInfo applicationInfo) {
        Uri uri = new Uri.Builder()
                .scheme(ContentResolver.SCHEME_ANDROID_RESOURCE)
                .authority(applicationInfo.packageName)
                .appendPath(String.valueOf(xmlResourceId))
                .build();
        return new TvContentRatingSystemInfo(uri, applicationInfo);
    }

    private TvContentRatingSystemInfo(Uri xmlUri, ApplicationInfo applicationInfo) {
        mXmlUri = xmlUri;
        mApplicationInfo = applicationInfo;
    }

    /**
     * Returns {@code true} if the TV content rating system is defined by a system app,
     * {@code false} otherwise.
     */
    public final boolean isSystemDefined() {
        return (mApplicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
    }

    /**
     * Returns the URI to the XML resource that defines the TV content rating system.
     *
     * TODO: Remove. Instead, parse the XML resource and provide an interface to directly access
     * parsed information.
     */
    public final Uri getXmlUri() {
        return mXmlUri;
    }

    /**
     * Used to make this class parcelable.
     * @hide
     */
    public static final Parcelable.Creator<TvContentRatingSystemInfo> CREATOR =
            new Parcelable.Creator<TvContentRatingSystemInfo>() {
        @Override
        public TvContentRatingSystemInfo createFromParcel(Parcel in) {
            return new TvContentRatingSystemInfo(in);
        }

        @Override
        public TvContentRatingSystemInfo[] newArray(int size) {
            return new TvContentRatingSystemInfo[size];
        }
    };

    private TvContentRatingSystemInfo(Parcel in) {
        mXmlUri = in.readParcelable(null);
        mApplicationInfo = in.readParcelable(null);
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeParcelable(mXmlUri, flags);
        dest.writeParcelable(mApplicationInfo, flags);
    }

    @Override
    public int describeContents() {
        return 0;
    }
}
