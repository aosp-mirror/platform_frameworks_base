/*
 * Copyright (C) 2016 The Android Open Source Project
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

package android.telephony.mbms;

import android.annotation.SystemApi;
import android.annotation.TestApi;
import android.net.Uri;
import android.os.Parcel;
import android.os.Parcelable;

import java.util.Objects;

/**
 * Describes a single file that is available over MBMS.
 */
public final class FileInfo implements Parcelable {

    private final Uri uri;

    private final String mimeType;

    public static final @android.annotation.NonNull Parcelable.Creator<FileInfo> CREATOR =
            new Parcelable.Creator<FileInfo>() {
        @Override
        public FileInfo createFromParcel(Parcel source) {
            return new FileInfo(source);
        }

        @Override
        public FileInfo[] newArray(int size) {
            return new FileInfo[size];
        }
    };

    /**
     * @hide
     */
    @SystemApi
    @TestApi
    public FileInfo(Uri uri, String mimeType) {
        this.uri = uri;
        this.mimeType = mimeType;
    }

    private FileInfo(Parcel in) {
        uri = in.readParcelable(null);
        mimeType = in.readString();
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeParcelable(uri, flags);
        dest.writeString(mimeType);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    /**
     * @return The URI in the carrier's infrastructure which points to this file. Apps should
     * negotiate the contents of this URI separately with the carrier.
     */
    public Uri getUri() {
        return uri;
    }

    /**
     * @return The MIME type of the file.
     */
    public String getMimeType() {
        return mimeType;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }

        FileInfo fileInfo = (FileInfo) o;
        return Objects.equals(uri, fileInfo.uri) &&
                Objects.equals(mimeType, fileInfo.mimeType);
    }

    @Override
    public int hashCode() {
        return Objects.hash(uri, mimeType);
    }
}
