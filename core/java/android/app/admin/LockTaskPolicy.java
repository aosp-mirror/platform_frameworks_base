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

import java.util.Arrays;
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
        return super.getValue();
    }

    /**
     * @hide
     */
    public LockTaskPolicy(@NonNull Set<String> packages) {
        Objects.requireNonNull(packages);
        mPackages.addAll(packages);
    }

    /**
     * @hide
     */
    public LockTaskPolicy(@NonNull Set<String> packages, int flags) {
        Objects.requireNonNull(packages);
        mPackages = new HashSet<>(packages);
        mFlags = flags;
        setValue(this);
    }

    private LockTaskPolicy(Parcel source) {
        String[] packages = Objects.requireNonNull(source.readStringArray());
        mPackages = new HashSet<>(Arrays.stream(packages).toList());
        mFlags = source.readInt();
    }

    /**
     * @hide
     */
    public LockTaskPolicy(LockTaskPolicy policy) {
        mPackages = new HashSet<>(policy.mPackages);
        mFlags = policy.mFlags;
    }

    /**
     * @hide
     */
    public void setPackages(@NonNull Set<String> packages) {
        Objects.requireNonNull(packages);
        mPackages = new HashSet<>(packages);
    }

    /**
     * @hide
     */
    public void setFlags(int flags) {
        mFlags = flags;
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
        dest.writeArray(mPackages.toArray(new String[0]));
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
