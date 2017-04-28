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
 * limitations under the License
 */

package android.telephony.mbms;

import android.content.ContentResolver;
import android.net.Uri;
import android.os.Parcel;
import android.os.Parcelable;

/** @hide */
public class UriPathPair implements Parcelable {
    private final Uri mFilePathUri;
    private final Uri mContentUri;

    /** @hide */
    public UriPathPair(Uri fileUri, Uri contentUri) {
        if (fileUri == null || !ContentResolver.SCHEME_FILE.equals(fileUri.getScheme())) {
            throw new IllegalArgumentException("File URI must have file scheme");
        }
        if (contentUri == null || !ContentResolver.SCHEME_CONTENT.equals(contentUri.getScheme())) {
            throw new IllegalArgumentException("Content URI must have content scheme");
        }

        mFilePathUri = fileUri;
        mContentUri = contentUri;
    }

    /** @hide */
    protected UriPathPair(Parcel in) {
        mFilePathUri = in.readParcelable(Uri.class.getClassLoader());
        mContentUri = in.readParcelable(Uri.class.getClassLoader());
    }

    public static final Creator<UriPathPair> CREATOR = new Creator<UriPathPair>() {
        @Override
        public UriPathPair createFromParcel(Parcel in) {
            return new UriPathPair(in);
        }

        @Override
        public UriPathPair[] newArray(int size) {
            return new UriPathPair[size];
        }
    };

    /** future systemapi */
    public Uri getFilePathUri() {
        return mFilePathUri;
    }

    /** future systemapi */
    public Uri getContentUri() {
        return mContentUri;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeParcelable(mFilePathUri, flags);
        dest.writeParcelable(mContentUri, flags);
    }
}
