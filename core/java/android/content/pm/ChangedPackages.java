/**
 * Copyright (c) 2017, The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package android.content.pm;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.TestApi;
import android.content.Intent;
import android.os.Parcel;
import android.os.Parcelable;

import java.util.List;

/**
 * Packages that have been changed since the last time they
 * were requested.
 * @see PackageManager#getChangedPackages(int)
 */
public final class ChangedPackages implements Parcelable {
    /** The last known sequence number for these changes */
    private final int mSequenceNumber;
    /** The names of the packages that have changed */
    private final List<String> mPackageNames;

    public ChangedPackages(int sequenceNumber, @NonNull List<String> packageNames) {
        this.mSequenceNumber = sequenceNumber;
        this.mPackageNames = packageNames;
    }

    /** @hide */
    protected ChangedPackages(Parcel in) {
        mSequenceNumber = in.readInt();
        mPackageNames = in.createStringArrayList();
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(mSequenceNumber);
        dest.writeStringList(mPackageNames);
    }

    /**
     * Returns the last known sequence number for these changes.
     */
    public int getSequenceNumber() {
        return mSequenceNumber;
    }

    /**
     * Returns the names of the packages that have changed.
     */
    public @NonNull List<String> getPackageNames() {
        return mPackageNames;
    }

    public static final @android.annotation.NonNull Parcelable.Creator<ChangedPackages> CREATOR =
            new Parcelable.Creator<ChangedPackages>() {
        public ChangedPackages createFromParcel(Parcel in) {
            return new ChangedPackages(in);
        }

        public ChangedPackages[] newArray(int size) {
            return new ChangedPackages[size];
        }
    };
}
