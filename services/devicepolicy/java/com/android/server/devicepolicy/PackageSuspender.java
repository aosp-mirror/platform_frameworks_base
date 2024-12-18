/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.server.devicepolicy;

import static com.android.server.devicepolicy.DevicePolicyManagerService.LOG_TAG;

import android.annotation.Nullable;
import android.content.pm.PackageManagerInternal;
import android.util.ArraySet;

import com.android.server.utils.Slogf;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * Helper class for calling into PackageManagerInternal.setPackagesSuspendedByAdmin.
 * Two main things this class encapsulates:
 *    1. Handling of the DPM internal suspension exemption list
 *    2. Calculating the failed packages result in the context of coexistence
 *
 * 1 is handled by the two internal methods {@link #suspendWithExemption(Set)} and
 * {@link #unsuspendWithExemption(Set)} where the exemption list is taken into consideration
 * before and after calling {@link PackageManagerInternal#setPackagesSuspendedByAdmin}.
 * In order to compute 2, the resolved package suspension state before and after suspension is
 * needed as multiple admins can both suspend the same packages under coexistence.
 */
public class PackageSuspender {

    private final Set<String> mSuspendedPackageBefore;
    private final Set<String> mSuspendedPackageAfter;
    private final List<String> mExemptedPackages;
    private final PackageManagerInternal mPackageManager;
    private final int mUserId;

    public PackageSuspender(@Nullable Set<String> suspendedPackageBefore,
            @Nullable Set<String> suspendedPackageAfter, List<String> exemptedPackages,
            PackageManagerInternal pmi, int userId) {
        mSuspendedPackageBefore =
                suspendedPackageBefore != null ? suspendedPackageBefore : Collections.emptySet();
        mSuspendedPackageAfter =
                suspendedPackageAfter != null ? suspendedPackageAfter : Collections.emptySet();
        mExemptedPackages = exemptedPackages;
        mPackageManager = pmi;
        mUserId = userId;
    }

    /**
     * Suspend packages that are requested by a single admin
     *
     * @return a list of packages that the admin has requested to suspend but could not be
     * suspended, due to DPM and PackageManager exemption list.
     *
     */
    public String[] suspend(Set<String> packages) {
        // When suspending, call PM with the list of packages admin has requested, even if some
        // of these packages are already in suspension (some other admin might have already
        // suspended them). We do it this way so that we can simply return the failed list from
        // PackageManager to the caller as the accurate list of unsuspended packages.
        // This is different from the unsuspend() logic, please see below.
        //
        // For example admin A already suspended package 1, 2 and 3, but package 3 is
        // PackageManager-exempted. Now admin B wants to suspend package 2, 3 and 4 (2 and 4 are
        // suspendable). We need to return package 3 as the unsuspended package here, and we ask
        // PackageManager to suspend package 2, 3 and 4 here (who will return package 3 in the
        // failed list, and package 2 is already suspended).
        Set<String> result = suspendWithExemption(packages);
        return result.toArray(String[]::new);
    }

    /**
     * Suspend packages considering the exemption list.
     *
     * @return the list of packages that couldn't be suspended, either due to the exemption list,
     * or due to failures from PackageManagerInternal itself.
     */
    private Set<String> suspendWithExemption(Set<String> packages) {
        Set<String> packagesToSuspend = new ArraySet<>(packages);
        // Any original packages that are also in the exempted list will not be suspended and hence
        // will appear in the final result.
        Set<String> result = new ArraySet<>(mExemptedPackages);
        result.retainAll(packagesToSuspend);
        // Remove exempted packages before calling PackageManager
        packagesToSuspend.removeAll(mExemptedPackages);
        String[] failedPackages = mPackageManager.setPackagesSuspendedByAdmin(
                mUserId, packagesToSuspend.toArray(String[]::new), true);
        if (failedPackages == null) {
            Slogf.w(LOG_TAG, "PM failed to suspend packages (%s)", packages);
            return packages;
        } else {
            result.addAll(Arrays.asList(failedPackages));
            return result;
        }
    }

    /**
     * Unsuspend packages that are requested by a single admin
     *
     * @return a list of packages that the admin has requested to unsuspend but could not be
     * unsuspended, due to other amdin's policy or PackageManager restriction.
     *
     */
    public String[] unsuspend(Set<String> packages) {
        // Unlike suspend(), when unsuspending, call PackageManager with the delta of resolved
        // suspended packages list and not what the admin has requested. This is because some
        // packages might still be subject to another admin's suspension request.
        Set<String> packagesToUnsuspend = new ArraySet<>(mSuspendedPackageBefore);
        packagesToUnsuspend.removeAll(mSuspendedPackageAfter);

        // To calculate the result (which packages are not unsuspended), start with packages that
        // are still subject to another admin's suspension policy. This is calculated by
        // intersecting the packages argument with mSuspendedPackageAfter.
        Set<String> result = new ArraySet<>(packages);
        result.retainAll(mSuspendedPackageAfter);
        // Remove mExemptedPackages since they can't be suspended to start with.
        result.removeAll(mExemptedPackages);
        // Finally make the unsuspend() request and add packages that PackageManager can't unsuspend
        // to the result.
        result.addAll(unsuspendWithExemption(packagesToUnsuspend));
        return result.toArray(String[]::new);
    }

    /**
     * Unsuspend packages considering the exemption list.
     *
     * @return the list of packages that couldn't be unsuspended, either due to the exemption list,
     * or due to failures from PackageManagerInternal itself.
     */
    private Set<String> unsuspendWithExemption(Set<String> packages) {
        // when unsuspending, no need to consider exemption list since by definition they can't
        // be suspended to begin with.
        String[] failedPackages = mPackageManager.setPackagesSuspendedByAdmin(
                mUserId, packages.toArray(String[]::new), false);
        if (failedPackages == null) {
            Slogf.w(LOG_TAG, "PM failed to unsuspend packages (%s)", packages);
        }
        return new ArraySet<>(failedPackages);
    }
}
