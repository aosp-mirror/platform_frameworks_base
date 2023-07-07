/*
 * Copyright (C) 2022 The Android Open Source Project
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

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.ArraySet;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Collections;
import java.util.Objects;
import java.util.Set;


/**
 * A generic class that defines which APK packages are in scope for some device policy.
 * <p>
 * The packages can be defined using either an allowlist or a blocklist.
 * In allowlist mode, it could optionally include all system packages
 * that meet the specific criteria of the device policy in question.
 */
public final class PackagePolicy implements Parcelable {

    /**
     * PackagePolicy type indicator for {@link PackagePolicy}
     * <p>
     * This constant indicates that all packages are allowed except for the packages returned by
     * {@link PackagePolicy#getPackageNames()}, which acts as a denylist.
     * @see #PACKAGE_POLICY_ALLOWLIST_AND_SYSTEM
     * @see #PACKAGE_POLICY_ALLOWLIST
     */
    public static final int PACKAGE_POLICY_BLOCKLIST = 1;

    /**
     * PackagePolicy type indicator for {@link PackagePolicy}
     * <p>
     * This constant indicates system packages are allowed in addition to the packages returned by
     * {@link PackagePolicy#getPackageNames()}, which acts as an allowlist.
     *
     * <p>Functions that accept {@link PackagePolicy} will further clarify
     * how this policy is interpreted.
     *
     * @see #PACKAGE_POLICY_BLOCKLIST
     * @see #PACKAGE_POLICY_ALLOWLIST
     */
    public static final int PACKAGE_POLICY_ALLOWLIST_AND_SYSTEM = 2;

    /**
     * PackagePolicy type indicator for {@link PackagePolicy}
     * <p>
     * This constant indicates that all packages are denied except for the packages returned by
     * {@link PackagePolicy#getPackageNames()}, which acts as an allowlist.
     *
     * @see #PACKAGE_POLICY_BLOCKLIST
     * @see #PACKAGE_POLICY_ALLOWLIST_AND_SYSTEM
     */
    public static final int PACKAGE_POLICY_ALLOWLIST = 3;

    /**
     *
     * @hide
     */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = {"PACKAGE_POLICY_"}, value = {
            PACKAGE_POLICY_BLOCKLIST,
            PACKAGE_POLICY_ALLOWLIST_AND_SYSTEM,
            PACKAGE_POLICY_ALLOWLIST
    })
    public @interface PackagePolicyType {}

    private @PackagePolicyType int mPolicyType;

    private ArraySet<String> mPackageNames;

    /**
     * Create the package policy
     * @param policyType indicates how to interpret this policy

     * @see PackagePolicy#PackagePolicy(int, Set)
     */
    public PackagePolicy(@PackagePolicyType int policyType) {
        this(policyType, Collections.emptySet());
    }

    /**
     * Create the package policy
     * @param policyType indicates how to interpret this policy
     * @param packageNames allowlist or a denylist, based on policyType
     */
    public PackagePolicy(@PackagePolicyType int policyType, @NonNull Set<String> packageNames) {
        if (policyType != PACKAGE_POLICY_BLOCKLIST
                && policyType != PACKAGE_POLICY_ALLOWLIST_AND_SYSTEM
                && policyType != PACKAGE_POLICY_ALLOWLIST) {
            throw new IllegalArgumentException("Invalid policy type");
        }
        mPolicyType = policyType;
        mPackageNames = new ArraySet<>(packageNames);
    }

    private PackagePolicy(Parcel in) {
        mPolicyType = in.readInt();
        mPackageNames = (ArraySet<String>) in.readArraySet(null);
    }

    /**
     * Returns the current policy type
     */
    public @PackagePolicyType int getPolicyType() {
        return mPolicyType;
    }

    /**
     * Returns the list of packages to use as an allow/deny list based on policy type
     */
    @NonNull
    public Set<String> getPackageNames() {
        return Collections.unmodifiableSet(mPackageNames);
    }

    /**
     * Evaluates the packageName provided against this policy to determine if the package should be
     * allowed.
     *
     * If the policy type is {@link #PACKAGE_POLICY_ALLOWLIST_AND_SYSTEM},
     * the systemPackage will be used in addition to package names of this policy's
     * {@link #getPackageNames()}
     *
     * @param packageName  the name of the package to test
     * @param systemPackages list of packages identified as system packages
     * @return true if the package is allowed, false if the package is denied
     * @hide
     */
    public boolean isPackageAllowed(@NonNull String packageName,
            @NonNull Set<String> systemPackages) {
        if (mPolicyType == PACKAGE_POLICY_BLOCKLIST) {
            return !mPackageNames.contains(packageName);
        }
        return mPackageNames.contains(packageName)
                || (mPolicyType == PACKAGE_POLICY_ALLOWLIST_AND_SYSTEM
                      && systemPackages.contains(packageName));
    }

    /**
     * @see Parcelable.Creator
     */
    @NonNull
    public static final Creator<PackagePolicy> CREATOR = new Creator<PackagePolicy>() {
        @Override
        public PackagePolicy createFromParcel(Parcel in) {
            return new PackagePolicy(in);
        }

        @Override
        public PackagePolicy[] newArray(int size) {
            return new PackagePolicy[size];
        }
    };

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeInt(mPolicyType);
        dest.writeArraySet(mPackageNames);
    }

    @Override
    public boolean equals(Object thatObject) {
        if (this == thatObject) {
            return true;
        }
        if (!(thatObject instanceof PackagePolicy)) {
            return false;
        }
        PackagePolicy that = (PackagePolicy) thatObject;
        return mPolicyType == that.mPolicyType && mPackageNames.equals(that.mPackageNames);
    }

    @Override
    public int hashCode() {
        return Objects.hash(mPolicyType, mPackageNames);
    }

    @Override
    public int describeContents() {
        return 0;
    }

}
