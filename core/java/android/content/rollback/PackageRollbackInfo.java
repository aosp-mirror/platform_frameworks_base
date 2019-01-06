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

package android.content.rollback;

import android.annotation.SystemApi;
import android.os.Parcel;
import android.os.Parcelable;

import java.util.Objects;

/**
 * Information about a rollback available for a particular package.
 *
 * @hide
 */
@SystemApi
public final class PackageRollbackInfo implements Parcelable {
    /**
     * The name of a package being rolled back.
     */
    public final String packageName;

    /**
     * The version the package was rolled back from.
     */
    public final PackageVersion higherVersion;

    /**
     * The version the package was rolled back to.
     */
    public final PackageVersion lowerVersion;

    /**
     * Represents a version of a package.
     */
    public static class PackageVersion {
        public final long versionCode;

        // TODO(b/120200473): Include apk sha or some other way to distinguish
        // between two different apks with the same version code.
        public PackageVersion(long versionCode) {
            this.versionCode = versionCode;
        }

        @Override
        public boolean equals(Object other) {
            if (other instanceof PackageVersion)  {
                PackageVersion otherVersion = (PackageVersion) other;
                return versionCode == otherVersion.versionCode;
            }
            return false;
        }

        @Override
        public int hashCode() {
            return Objects.hash(versionCode);
        }
    }

    public PackageRollbackInfo(String packageName,
            PackageVersion higherVersion, PackageVersion lowerVersion) {
        this.packageName = packageName;
        this.higherVersion = higherVersion;
        this.lowerVersion = lowerVersion;
    }

    private PackageRollbackInfo(Parcel in) {
        this.packageName = in.readString();
        this.higherVersion = new PackageVersion(in.readLong());
        this.lowerVersion = new PackageVersion(in.readLong());
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel out, int flags) {
        out.writeString(packageName);
        out.writeLong(higherVersion.versionCode);
        out.writeLong(lowerVersion.versionCode);
    }

    public static final Parcelable.Creator<PackageRollbackInfo> CREATOR =
            new Parcelable.Creator<PackageRollbackInfo>() {
        public PackageRollbackInfo createFromParcel(Parcel in) {
            return new PackageRollbackInfo(in);
        }

        public PackageRollbackInfo[] newArray(int size) {
            return new PackageRollbackInfo[size];
        }
    };
}
