/*
 * Copyright (C) 2018 The Android Open Source Project
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

package android.app;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.UriPermission;
import android.net.Uri;
import android.os.Parcel;
import android.os.Parcelable;

/**
 * Represents an {@link UriPermission} granted to a package.
 *
 * {@hide}
 */
public class GrantedUriPermission implements Parcelable {

    public final Uri uri;
    public final String packageName;

    public GrantedUriPermission(@NonNull Uri uri, @Nullable String packageName) {
        this.uri = uri;
        this.packageName = packageName;
    }

    @Override
    public String toString() {
        return packageName + ":" + uri;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel out, int flags) {
        out.writeParcelable(uri, flags);
        out.writeString(packageName);
    }

    public static final Parcelable.Creator<GrantedUriPermission> CREATOR =
            new Parcelable.Creator<GrantedUriPermission>() {
                @Override
                public GrantedUriPermission createFromParcel(Parcel in) {
                    return new GrantedUriPermission(in);
                }

                @Override
                public GrantedUriPermission[] newArray(int size) {
                    return new GrantedUriPermission[size];
                }
            };

    private GrantedUriPermission(Parcel in) {
        uri = in.readParcelable(null);
        packageName = in.readString();
    }
}
