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

import android.annotation.NonNull;
import android.annotation.SystemApi;
import android.content.pm.VersionedPackage;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.IntArray;
import android.util.SparseLongArray;

import java.util.ArrayList;

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
     * Encapsulates information required to restore a snapshot of an app's userdata.
     *
     * @hide
     */
    public static class RestoreInfo {
        public final int userId;
        public final int appId;
        public final String seInfo;

        public RestoreInfo(int userId, int appId, String seInfo) {
            this.userId = userId;
            this.appId = appId;
            this.seInfo = seInfo;
        }
    }

    /*
     * The list of users for which we need to backup userdata for this package. Backups of
     * credential encrypted data are listed as pending if the user hasn't unlocked their device
     * with credentials yet.
     */
    // NOTE: Not a part of the Parcelable representation of this object.
    private final IntArray mPendingBackups;

    /**
     * The list of users for which we need to restore userdata for this package. This field is
     * non-null only after a rollback for this package has been committed.
     */
    // NOTE: Not a part of the Parcelable representation of this object.
    private final ArrayList<RestoreInfo> mPendingRestores;

    /**
     * Whether this instance represents the PackageRollbackInfo for an APEX module.
     */
    private final boolean mIsApex;

    /*
     * The list of users the package is installed for.
     */
    // NOTE: Not a part of the Parcelable representation of this object.
    private final IntArray mInstalledUsers;

    /**
     * A mapping between user and an inode of theirs CE data snapshot.
     */
    // NOTE: Not a part of the Parcelable representation of this object.
    private final SparseLongArray mCeSnapshotInodes;

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
    public void addPendingBackup(int userId) {
        mPendingBackups.add(userId);
    }

    /** @hide */
    public IntArray getPendingBackups() {
        return mPendingBackups;
    }

    /** @hide */
    public ArrayList<RestoreInfo> getPendingRestores() {
        return mPendingRestores;
    }

    /** @hide */
    public RestoreInfo getRestoreInfo(int userId) {
        for (RestoreInfo ri : mPendingRestores) {
            if (ri.userId == userId) {
                return ri;
            }
        }

        return null;
    }

    /** @hide */
    public void removeRestoreInfo(RestoreInfo ri) {
        mPendingRestores.remove(ri);
    }

    /** @hide */
    public boolean isApex() {
        return mIsApex;
    }

    /** @hide */
    public IntArray getInstalledUsers() {
        return mInstalledUsers;
    }

    /** @hide */
    public SparseLongArray getCeSnapshotInodes() {
        return mCeSnapshotInodes;
    }

    /** @hide */
    public void putCeSnapshotInode(int userId, long ceSnapshotInode) {
        mCeSnapshotInodes.put(userId, ceSnapshotInode);
    }

    /** @hide */
    public void removePendingBackup(int userId) {
        int idx = mPendingBackups.indexOf(userId);
        if (idx != -1) {
            mPendingBackups.remove(idx);
        }
    }

    /** @hide */
    public void removePendingRestoreInfo(int userId) {
        removeRestoreInfo(getRestoreInfo(userId));
    }

    /** @hide */
    public PackageRollbackInfo(VersionedPackage packageRolledBackFrom,
            VersionedPackage packageRolledBackTo,
            @NonNull IntArray pendingBackups, @NonNull ArrayList<RestoreInfo> pendingRestores,
            boolean isApex, @NonNull IntArray installedUsers,
            @NonNull SparseLongArray ceSnapshotInodes) {
        this.mVersionRolledBackFrom = packageRolledBackFrom;
        this.mVersionRolledBackTo = packageRolledBackTo;
        this.mPendingBackups = pendingBackups;
        this.mPendingRestores = pendingRestores;
        this.mIsApex = isApex;
        this.mInstalledUsers = installedUsers;
        this.mCeSnapshotInodes = ceSnapshotInodes;
    }

    private PackageRollbackInfo(Parcel in) {
        this.mVersionRolledBackFrom = VersionedPackage.CREATOR.createFromParcel(in);
        this.mVersionRolledBackTo = VersionedPackage.CREATOR.createFromParcel(in);
        this.mIsApex = in.readBoolean();
        this.mPendingRestores = null;
        this.mPendingBackups = null;
        this.mInstalledUsers = null;
        this.mCeSnapshotInodes = null;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel out, int flags) {
        mVersionRolledBackFrom.writeToParcel(out, flags);
        mVersionRolledBackTo.writeToParcel(out, flags);
        out.writeBoolean(mIsApex);
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
