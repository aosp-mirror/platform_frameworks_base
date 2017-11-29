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

import android.annotation.SystemApi;
import android.content.ContentResolver;
import android.net.Uri;
import android.os.Parcel;
import android.os.Parcelable;
import android.telephony.mbms.vendor.VendorUtils;

/**
 * Wrapper for a pair of {@link Uri}s that describe a temp file used by the middleware to
 * download files via cell-broadcast.
 * @hide
 */
//@SystemApi
public final class UriPathPair implements Parcelable {
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
    private UriPathPair(Parcel in) {
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

    /**
     * Returns the file-path {@link Uri}. This has scheme {@code file} and points to the actual
     * location on disk where the temp file resides. Use this when sending {@link Uri}s back to the
     * app in the intents in {@link VendorUtils}.
     * @return A {@code file} {@link Uri}.
     */
    public Uri getFilePathUri() {
        return mFilePathUri;
    }

    /**
     * Returns the content {@link Uri} that may be used with
     * {@link ContentResolver#openFileDescriptor(Uri, String)} to obtain a
     * {@link android.os.ParcelFileDescriptor} to a temp file to write to. This {@link Uri} will
     * expire if the middleware process dies.
     * @return A {@code content} {@link Uri}
     */
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
