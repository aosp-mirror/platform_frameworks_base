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

/**
 * Information about a set of packages that can be, or already have been
 * rolled back together.
 *
 * @hide
 */
@SystemApi
public final class RollbackInfo implements Parcelable {

    /**
     * A unique identifier for the rollback.
     */
    private final int mRollbackId;

    /**
     * The package that needs to be rolled back.
     */
    public final PackageRollbackInfo targetPackage;

    // TODO: Add a list of additional packages rolled back due to atomic
    // install dependencies when rollback of atomic installs is supported.
    // TODO: Add a flag to indicate if reboot is required, when rollback of
    // staged installs is supported.

    /** @hide */
    public RollbackInfo(int rollbackId, PackageRollbackInfo targetPackage) {
        this.mRollbackId = rollbackId;
        this.targetPackage = targetPackage;
    }

    private RollbackInfo(Parcel in) {
        mRollbackId = in.readInt();
        targetPackage = PackageRollbackInfo.CREATOR.createFromParcel(in);
    }

    /**
     * Returns a unique identifier for this rollback.
     */
    public int getRollbackId() {
        return mRollbackId;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel out, int flags) {
        out.writeInt(mRollbackId);
        targetPackage.writeToParcel(out, flags);
    }

    public static final Parcelable.Creator<RollbackInfo> CREATOR =
            new Parcelable.Creator<RollbackInfo>() {
        public RollbackInfo createFromParcel(Parcel in) {
            return new RollbackInfo(in);
        }

        public RollbackInfo[] newArray(int size) {
            return new RollbackInfo[size];
        }
    };
}
