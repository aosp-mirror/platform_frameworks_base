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
import android.content.pm.VersionedPackage;
import android.os.Parcel;
import android.os.Parcelable;

/**
 * Information about a rollback available for a particular package.
 *
 * @hide
 */
@SystemApi
public final class PackageRollbackInfo implements Parcelable {

    private final VersionedPackage mVersionRolledBackFrom;
    private final VersionedPackage mVersionRolledBackTo;

    /**
     * Returns the name of the package to roll back from.
     */
    public String getPackageName() {
        return mVersionRolledBackFrom.getPackageName();
    }

    /**
     * Returns the version of the package rolled back from.
     */
    public VersionedPackage getVersionRolledBackFrom() {
        return mVersionRolledBackFrom;
    }

    /**
     * Returns the version of the package rolled back to.
     */
    public VersionedPackage getVersionRolledBackTo() {
        return mVersionRolledBackTo;
    }

    /** @hide */
    public PackageRollbackInfo(VersionedPackage packageRolledBackFrom,
            VersionedPackage packageRolledBackTo) {
        this.mVersionRolledBackFrom = packageRolledBackFrom;
        this.mVersionRolledBackTo = packageRolledBackTo;
    }

    private PackageRollbackInfo(Parcel in) {
        this.mVersionRolledBackFrom = VersionedPackage.CREATOR.createFromParcel(in);
        this.mVersionRolledBackTo = VersionedPackage.CREATOR.createFromParcel(in);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel out, int flags) {
        mVersionRolledBackFrom.writeToParcel(out, flags);
        mVersionRolledBackTo.writeToParcel(out, flags);
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
