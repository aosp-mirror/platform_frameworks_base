/*
 * Copyright (C) 2023 The Android Open Source Project
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

package android.app.admin;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.os.Parcel;
import android.os.Parcelable;

import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

/**
 * Class to represent a lock task policy, this is a combination of lock task packages (see
 * {@link DevicePolicyManager#setLockTaskPackages}) and lock task features (see
 * {@link DevicePolicyManager#setLockTaskFeatures}.
 *
 * @hide
 */
@SystemApi
public final class LockTaskPolicy extends PolicyValue<LockTaskPolicy> {
    /**
     * @hide
     */
    // We default on the power button menu, in order to be consistent with pre-P behaviour
    public static final int DEFAULT_LOCK_TASK_FLAG =
            DevicePolicyManager.LOCK_TASK_FEATURE_GLOBAL_ACTIONS;

    private Set<String> mPackages = new HashSet<>();
    private int mFlags = DEFAULT_LOCK_TASK_FLAG;

    /**
     * Returns the list of packages allowed to start the lock task mode.
     */
    @NonNull
    public Set<String> getPackages() {
        return mPackages;
    }

    /**
     * Returns which system features are enabled for LockTask mode.
     */
    public @DevicePolicyManager.LockTaskFeature int getFlags() {
        return mFlags;
    }

    // Overriding to hide
    /**
     * @hide
     */
    @Override
    @NonNull
    public LockTaskPolicy getValue() {
        return this;
    }

    /**
     * @hide
     */
    public LockTaskPolicy(@Nullable Set<String> packages) {
        if (packages != null) {
            setPackagesInternal(packages);
        }
        setValue(this);
    }

    /**
     * @hide
     */
    public LockTaskPolicy(int flags) {
        mFlags = flags;
        setValue(this);
    }

    /**
     * @hide
     */
    public LockTaskPolicy(@Nullable Set<String> packages, int flags) {
        if (packages != null) {
            setPackagesInternal(packages);
        }
        mFlags = flags;
        setValue(this);
    }

    private LockTaskPolicy(Parcel source) {
        int size = source.readInt();
        mPackages = new HashSet<>();
        for (int i = 0; i < size; i++) {
            mPackages.add(source.readString());
        }
        mFlags = source.readInt();
        setValue(this);
    }

    /**
     * @hide
     */
    public LockTaskPolicy(LockTaskPolicy policy) {
        mPackages = new HashSet<>(policy.mPackages);
        mFlags = policy.mFlags;
        setValue(this);
    }

    /**
     * @hide
     */
    public void setPackages(@NonNull Set<String> packages) {
        Objects.requireNonNull(packages);
        setPackagesInternal(packages);
    }

    /**
     * @hide
     */
    public void setFlags(int flags) {
        mFlags = flags;
    }

    private void setPackagesInternal(Set<String> packages) {
        for (String p : packages) {
            PolicySizeVerifier.enforceMaxPackageNameLength(p);
        }
        mPackages = new HashSet<>(packages);
    }

    @Override
    public boolean equals(@Nullable Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        LockTaskPolicy other = (LockTaskPolicy) o;
        return Objects.equals(mPackages, other.mPackages)
                && mFlags == other.mFlags;
    }

    @Override
    public int hashCode() {
        return Objects.hash(mPackages, mFlags);
    }

    @Override
    public String toString() {
        return "LockTaskPolicy {mPackages= " + String.join(", ", mPackages) + "; mFlags= "
                + mFlags + " }";
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeInt(mPackages.size());
        for (String p : mPackages) {
            dest.writeString(p);
        }
        dest.writeInt(mFlags);
    }

    @NonNull
    public static final Parcelable.Creator<LockTaskPolicy> CREATOR =
            new Parcelable.Creator<LockTaskPolicy>() {
                @Override
                public LockTaskPolicy createFromParcel(Parcel source) {
                    return new LockTaskPolicy(source);
                }

                @Override
                public LockTaskPolicy[] newArray(int size) {
                    return new LockTaskPolicy[size];
                }
            };
}
