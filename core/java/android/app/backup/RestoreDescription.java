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

package android.app.backup;

import android.annotation.SystemApi;
import android.os.Parcel;
import android.os.Parcelable;

/**
 * Description of the available restore data for a given package.  Returned by a
 * BackupTransport in response to a request about the next available restorable
 * package.
 *
 * @see BackupTransport#nextRestorePackage()
 *
 * @hide
 */
@SystemApi
public class RestoreDescription implements Parcelable {
    private final String mPackageName;
    private final int mDataType;

    private static final String NO_MORE_PACKAGES_SENTINEL = "";

    /**
     * Return this constant RestoreDescription from BackupTransport.nextRestorePackage()
     * to indicate that no more package data is available in the current restore operation.
     */
    public static final RestoreDescription NO_MORE_PACKAGES =
            new RestoreDescription(NO_MORE_PACKAGES_SENTINEL, 0);

    // ---------------------------------------
    // Data type identifiers

    /** This package's restore data is an original-style key/value dataset */
    public static final int TYPE_KEY_VALUE = 1;

    /** This package's restore data is a tarball-type full data stream */
    public static final int TYPE_FULL_STREAM = 2;

    @Override
    public String toString() {
        return "RestoreDescription{" + mPackageName + " : "
                + ((mDataType == TYPE_KEY_VALUE) ? "KEY_VALUE" : "STREAM")
                + '}';
    }

    // ---------------------------------------
    // API

    public RestoreDescription(String packageName, int dataType) {
        mPackageName = packageName;
        mDataType = dataType;
    }

    public String getPackageName() {
        return mPackageName;
    }

    public int getDataType() {
        return mDataType;
    }

    // ---------------------------------------
    // Parcelable implementation - not used by transport

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel out, int flags) {
        out.writeString(mPackageName);
        out.writeInt(mDataType);
    }

    public static final Parcelable.Creator<RestoreDescription> CREATOR
            = new Parcelable.Creator<RestoreDescription>() {
        public RestoreDescription createFromParcel(Parcel in) {
            final RestoreDescription unparceled = new RestoreDescription(in);
            return (NO_MORE_PACKAGES_SENTINEL.equals(unparceled.mPackageName))
                    ? NO_MORE_PACKAGES
                    : unparceled;
        }

        public RestoreDescription[] newArray(int size) {
            return new RestoreDescription[size];
        }
    };

    private RestoreDescription(Parcel in) {
        mPackageName = in.readString();
        mDataType = in.readInt();
    }
}
