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
import android.content.pm.PackageInstaller;
import android.content.pm.VersionedPackage;
import android.os.Parcel;
import android.os.Parcelable;

import java.util.List;

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

    private final List<PackageRollbackInfo> mPackages;

    private final List<VersionedPackage> mCausePackages;

    /** @hide */
    public RollbackInfo(int rollbackId, List<PackageRollbackInfo> packages,
            List<VersionedPackage> causePackages) {
        this.mRollbackId = rollbackId;
        this.mPackages = packages;
        this.mCausePackages = causePackages;
    }

    private RollbackInfo(Parcel in) {
        mRollbackId = in.readInt();
        mPackages = in.createTypedArrayList(PackageRollbackInfo.CREATOR);
        mCausePackages = in.createTypedArrayList(VersionedPackage.CREATOR);
    }

    /**
     * Returns a unique identifier for this rollback.
     */
    public int getRollbackId() {
        return mRollbackId;
    }

    /**
     * Returns the list of package that are rolled back.
     */
    public List<PackageRollbackInfo> getPackages() {
        return mPackages;
    }

    /**
     * Returns true if this rollback requires reboot to take effect after
     * being committed.
     */
    public boolean isStaged() {
        // TODO: Support rollback of staged installs.
        return false;
    }

    /**
     * Returns the session ID for the committed rollback for staged rollbacks.
     * Only applicable for rollbacks that have been committed.
     */
    public int getSessionId() {
        // TODO: Support rollback of staged installs.
        return PackageInstaller.SessionInfo.INVALID_ID;
    }

    /**
     * Gets the list of package versions that motivated this rollback.
     * As provided to {@link #commitRollback} when the rollback was committed.
     * This is only applicable for rollbacks that have been committed.
     */
    public List<VersionedPackage> getCausePackages() {
        return mCausePackages;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel out, int flags) {
        out.writeInt(mRollbackId);
        out.writeTypedList(mPackages);
        out.writeTypedList(mCausePackages);
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
