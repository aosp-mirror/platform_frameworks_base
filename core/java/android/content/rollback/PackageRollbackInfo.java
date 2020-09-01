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
import android.annotation.TestApi;
import android.content.pm.PackageManager;
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
@SystemApi @TestApi
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

    /**
     * Whether this instance represents the PackageRollbackInfo for an APK in APEX.
     */
    private final boolean mIsApkInApex;

    /*
     * The list of users for which snapshots have been saved.
     */
    // NOTE: Not a part of the Parcelable representation of this object.
    private final IntArray mSnapshottedUsers;

    /**
     * A mapping between user and an inode of theirs CE data snapshot.
     */
    // NOTE: Not a part of the Parcelable representation of this object.
    private final SparseLongArray mCeSnapshotInodes;

    /**
     * The userdata policy to execute when a rollback for this package is committed.
     */
    private final int mRollbackDataPolicy;

    /**
     * Returns the name of the package to roll back from.
     */
    @NonNull
    public String getPackageName() {
        return mVersionRolledBackFrom.getPackageName();
    }

    /**
     * Returns the version of the package rolled back from.
     */
    @NonNull
    public VersionedPackage getVersionRolledBackFrom() {
        return mVersionRolledBackFrom;
    }

    /**
     * Returns the version of the package rolled back to.
     */
    @NonNull
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
    public @PackageManager.RollbackDataPolicy int getRollbackDataPolicy() {
        return mRollbackDataPolicy;
    }
    /** @hide */
    public boolean isApkInApex() {
        return mIsApkInApex;
    }

    /** @hide */
    public IntArray getSnapshottedUsers() {
        return mSnapshottedUsers;
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
            boolean isApex, boolean isApkInApex, @NonNull IntArray snapshottedUsers,
            @NonNull SparseLongArray ceSnapshotInodes) {
        this(packageRolledBackFrom, packageRolledBackTo, pendingBackups, pendingRestores, isApex,
                isApkInApex, snapshottedUsers, ceSnapshotInodes,
                PackageManager.RollbackDataPolicy.RESTORE);
    }

    /** @hide */
    public PackageRollbackInfo(VersionedPackage packageRolledBackFrom,
            VersionedPackage packageRolledBackTo,
            @NonNull IntArray pendingBackups, @NonNull ArrayList<RestoreInfo> pendingRestores,
            boolean isApex, boolean isApkInApex, @NonNull IntArray snapshottedUsers,
            @NonNull SparseLongArray ceSnapshotInodes,
            @PackageManager.RollbackDataPolicy int rollbackDataPolicy) {
        this.mVersionRolledBackFrom = packageRolledBackFrom;
        this.mVersionRolledBackTo = packageRolledBackTo;
        this.mPendingBackups = pendingBackups;
        this.mPendingRestores = pendingRestores;
        this.mIsApex = isApex;
        this.mRollbackDataPolicy = rollbackDataPolicy;
        this.mIsApkInApex = isApkInApex;
        this.mSnapshottedUsers = snapshottedUsers;
        this.mCeSnapshotInodes = ceSnapshotInodes;
    }

    private PackageRollbackInfo(Parcel in) {
        this.mVersionRolledBackFrom = VersionedPackage.CREATOR.createFromParcel(in);
        this.mVersionRolledBackTo = VersionedPackage.CREATOR.createFromParcel(in);
        this.mIsApex = in.readBoolean();
        this.mIsApkInApex = in.readBoolean();
        this.mPendingRestores = null;
        this.mPendingBackups = null;
        this.mSnapshottedUsers = null;
        this.mCeSnapshotInodes = null;
        this.mRollbackDataPolicy = PackageManager.RollbackDataPolicy.RESTORE;
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
        out.writeBoolean(mIsApkInApex);
    }

    public static final @NonNull Parcelable.Creator<PackageRollbackInfo> CREATOR =
            new Parcelable.Creator<PackageRollbackInfo>() {
        public PackageRollbackInfo createFromParcel(Parcel in) {
            return new PackageRollbackInfo(in);
        }

        public PackageRollbackInfo[] newArray(int size) {
            return new PackageRollbackInfo[size];
        }
    };
}
