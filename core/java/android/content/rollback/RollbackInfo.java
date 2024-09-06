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

import android.annotation.FlaggedApi;
import android.annotation.NonNull;
import android.annotation.SystemApi;
import android.content.pm.PackageManager;
import android.content.pm.VersionedPackage;
import android.os.Parcel;
import android.os.Parcelable;

import java.util.List;

/**
 * Information about a set of packages that can be, or already have been rolled back together.
 *
 * @hide
 */
@SystemApi
public final class RollbackInfo implements Parcelable {

    /** A unique identifier for the rollback. */
    private final int mRollbackId;

    private final List<PackageRollbackInfo> mPackages;

    private final List<VersionedPackage> mCausePackages;

    private final boolean mIsStaged;
    private int mCommittedSessionId;
    private int mRollbackImpactLevel;

    /** @hide */
    public RollbackInfo(
            int rollbackId,
            List<PackageRollbackInfo> packages,
            boolean isStaged,
            List<VersionedPackage> causePackages,
            int committedSessionId,
            @PackageManager.RollbackImpactLevel int rollbackImpactLevel) {
        this.mRollbackId = rollbackId;
        this.mPackages = packages;
        this.mIsStaged = isStaged;
        this.mCausePackages = causePackages;
        this.mCommittedSessionId = committedSessionId;
        this.mRollbackImpactLevel = rollbackImpactLevel;
    }

    /** @hide */
    public RollbackInfo(
            int rollbackId,
            List<PackageRollbackInfo> packages,
            boolean isStaged,
            List<VersionedPackage> causePackages,
            int committedSessionId) {
        // If impact level is not set default to 0
        this(
                rollbackId,
                packages,
                isStaged,
                causePackages,
                committedSessionId,
                PackageManager.ROLLBACK_USER_IMPACT_LOW);
    }

    private RollbackInfo(Parcel in) {
        mRollbackId = in.readInt();
        mPackages = in.createTypedArrayList(PackageRollbackInfo.CREATOR);
        mIsStaged = in.readBoolean();
        mCausePackages = in.createTypedArrayList(VersionedPackage.CREATOR);
        mCommittedSessionId = in.readInt();
        mRollbackImpactLevel = in.readInt();
    }

    /** Returns a unique identifier for this rollback. */
    public int getRollbackId() {
        return mRollbackId;
    }

    /** Returns the list of package that are rolled back. */
    @NonNull
    public List<PackageRollbackInfo> getPackages() {
        return mPackages;
    }

    /** Returns true if this rollback requires reboot to take effect after being committed. */
    public boolean isStaged() {
        return mIsStaged;
    }

    /**
     * Returns the session ID for the committed rollback for staged rollbacks. Only applicable for
     * rollbacks that have been committed.
     */
    public int getCommittedSessionId() {
        return mCommittedSessionId;
    }

    /**
     * Sets the session ID for the committed rollback for staged rollbacks.
     *
     * @hide
     */
    public void setCommittedSessionId(int sessionId) {
        mCommittedSessionId = sessionId;
    }

    /**
     * Gets the list of package versions that motivated this rollback. As provided to {@link
     * #commitRollback} when the rollback was committed. This is only applicable for rollbacks that
     * have been committed.
     */
    @NonNull
    public List<VersionedPackage> getCausePackages() {
        return mCausePackages;
    }

    /**
     * Get rollback impact level. Refer {@link
     * android.content.pm.PackageInstaller.SessionParams#setRollbackImpactLevel(int)} for more info
     * on impact level.
     */
    @FlaggedApi(android.crashrecovery.flags.Flags.FLAG_ENABLE_CRASHRECOVERY)
    public @PackageManager.RollbackImpactLevel int getRollbackImpactLevel() {
        return mRollbackImpactLevel;
    }

    /**
     * Set rollback impact level. Refer {@link
     * android.content.pm.PackageInstaller.SessionParams#setRollbackImpactLevel(int)} for more info
     * on impact level.
     *
     * @hide
     */
    public void setRollbackImpactLevel(
            @PackageManager.RollbackImpactLevel int rollbackImpactLevel) {
        mRollbackImpactLevel = rollbackImpactLevel;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel out, int flags) {
        out.writeInt(mRollbackId);
        out.writeTypedList(mPackages);
        out.writeBoolean(mIsStaged);
        out.writeTypedList(mCausePackages);
        out.writeInt(mCommittedSessionId);
        out.writeInt(mRollbackImpactLevel);
    }

    public static final @android.annotation.NonNull Parcelable.Creator<RollbackInfo> CREATOR =
            new Parcelable.Creator<RollbackInfo>() {
                public RollbackInfo createFromParcel(Parcel in) {
                    return new RollbackInfo(in);
                }

                public RollbackInfo[] newArray(int size) {
                    return new RollbackInfo[size];
                }
            };
}
