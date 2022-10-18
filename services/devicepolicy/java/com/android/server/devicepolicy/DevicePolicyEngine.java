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

package com.android.server.devicepolicy;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.os.UserHandle;
import android.util.SparseArray;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Class responsible for setting, resolving, and enforcing policies set by multiple management
 * admins on the device.
 */
final class DevicePolicyEngine {
    private static final String TAG = "DevicePolicyEngine";

    private final Context mContext;
    private final Object mLock = new Object();

    /**
     * Map of <userId, Map<policyKey, policyState>>
     */
    private final SparseArray<Map<String, PolicyState<?>>> mUserPolicies;

    /**
     * Map of <policyKey, policyState>
     */
    private final Map<String, PolicyState<?>> mGlobalPolicies;

    DevicePolicyEngine(@NonNull Context context) {
        mContext = Objects.requireNonNull(context);
        mUserPolicies = new SparseArray<>();
        mGlobalPolicies = new HashMap<>();
    }

    // TODO: add more documentation on broadcasts/callbacks to use to get current enforced values
    /**
     * Set the policy for the provided {@code policyDefinition}
     * (see {@link PolicyDefinition}) and {@code enforcingAdmin} to the provided {@code value}.
     * Returns {@code true} if the enforced policy has been changed.
     *
     */
    <V> boolean setLocalPolicy(
            @NonNull PolicyDefinition<V> policyDefinition,
            @NonNull EnforcingAdmin enforcingAdmin,
            @NonNull V value,
            int userId) {

        Objects.requireNonNull(policyDefinition);
        Objects.requireNonNull(enforcingAdmin);
        Objects.requireNonNull(value);

        synchronized (mLock) {
            PolicyState<V> policyState = getLocalPolicyStateLocked(policyDefinition, userId);

            boolean policyChanged = policyState.setPolicy(enforcingAdmin, value);

            if (policyChanged) {
                enforcePolicy(policyDefinition, policyState.getCurrentResolvedPolicy(), userId);
            }
            return policyChanged;
        }
    }

    // TODO: add more documentation on broadcasts/callbacks to use to get current enforced values
    /**
     * Set the policy for the provided {@code policyDefinition}
     * (see {@link PolicyDefinition}) and {@code enforcingAdmin} to the provided {@code value}.
     * Returns {@code true} if the enforced policy has been changed.
     *
     */
    <V> boolean setGlobalPolicy(
            @NonNull PolicyDefinition<V> policyDefinition,
            @NonNull EnforcingAdmin enforcingAdmin,
            @NonNull V value) {

        Objects.requireNonNull(policyDefinition);
        Objects.requireNonNull(enforcingAdmin);
        Objects.requireNonNull(value);

        synchronized (mLock) {
            PolicyState<V> policyState = getGlobalPolicyStateLocked(policyDefinition);

            boolean policyChanged = policyState.setPolicy(enforcingAdmin, value);

            if (policyChanged) {
                enforcePolicy(policyDefinition, policyState.getCurrentResolvedPolicy(),
                        UserHandle.USER_ALL);
            }
            return policyChanged;
        }
    }


    // TODO: add more documentation on broadcasts/callbacks to use to get current enforced values
    /**
     * Removes any previously set policy for the provided {@code policyDefinition}
     * (see {@link PolicyDefinition}) and {@code enforcingAdmin}.
     * Returns {@code true} if the enforced policy has been changed.
     *
     */
    <V> boolean removeLocalPolicy(
            @NonNull PolicyDefinition<V> policyDefinition,
            @NonNull EnforcingAdmin enforcingAdmin,
            int userId) {

        Objects.requireNonNull(policyDefinition);
        Objects.requireNonNull(enforcingAdmin);

        synchronized (mLock) {
            PolicyState<V> policyState = getLocalPolicyStateLocked(policyDefinition, userId);
            boolean policyChanged = policyState.removePolicy(enforcingAdmin);

            if (policyChanged) {
                enforcePolicy(policyDefinition, policyState.getCurrentResolvedPolicy(), userId);
            }
            return policyChanged;
        }
    }

    // TODO: add more documentation on broadcasts/callbacks to use to get current enforced values
    /**
     * Removes any previously set policy for the provided {@code policyDefinition}
     * (see {@link PolicyDefinition}) and {@code enforcingAdmin}.
     * Returns {@code true} if the enforced policy has been changed.
     *
     */
    <V> boolean removeGlobalPolicy(
            @NonNull PolicyDefinition<V> policyDefinition,
            @NonNull EnforcingAdmin enforcingAdmin) {

        Objects.requireNonNull(policyDefinition);
        Objects.requireNonNull(enforcingAdmin);

        synchronized (mLock) {
            PolicyState<V> policyState = getGlobalPolicyStateLocked(policyDefinition);
            boolean policyChanged = policyState.removePolicy(enforcingAdmin);

            if (policyChanged) {
                enforcePolicy(policyDefinition, policyState.getCurrentResolvedPolicy(),
                        UserHandle.USER_ALL);
            }
            return policyChanged;
        }
    }

    /**
     * Retrieves policies set by all admins for the provided {@code policyDefinition}.
     *
     */
    <V> PolicyState<V> getLocalPolicy(@NonNull PolicyDefinition<V> policyDefinition, int userId) {
        Objects.requireNonNull(policyDefinition);

        synchronized (mLock) {
            return getLocalPolicyStateLocked(policyDefinition, userId);
        }
    }

    /**
     * Retrieves policies set by all admins for the provided {@code policyDefinition}.
     *
     */
    <V> PolicyState<V> getGlobalPolicy(@NonNull PolicyDefinition<V> policyDefinition) {
        Objects.requireNonNull(policyDefinition);

        synchronized (mLock) {
            return getGlobalPolicyStateLocked(policyDefinition);
        }
    }

    @NonNull
    private <V> PolicyState<V> getLocalPolicyStateLocked(
            PolicyDefinition<V> policyDefinition, int userId) {

        if (policyDefinition.isGlobalOnlyPolicy()) {
            throw new IllegalArgumentException("Can't set global policy "
                    + policyDefinition.getKey() + " locally.");
        }

        if (!mUserPolicies.contains(userId)) {
            mUserPolicies.put(userId, new HashMap<>());
        }
        if (!mUserPolicies.get(userId).containsKey(policyDefinition.getKey())) {
            mUserPolicies.get(userId).put(
                    policyDefinition.getKey(), new PolicyState<>(policyDefinition));
        }
        return getPolicyState(mUserPolicies.get(userId), policyDefinition);
    }

    @NonNull
    private <V> PolicyState<V> getGlobalPolicyStateLocked(PolicyDefinition<V> policyDefinition) {

        if (policyDefinition.isLocalOnlyPolicy()) {
            throw new IllegalArgumentException("Can't set local policy "
                    + policyDefinition.getKey() + " globally.");
        }

        if (!mGlobalPolicies.containsKey(policyDefinition.getKey())) {
            mGlobalPolicies.put(
                    policyDefinition.getKey(), new PolicyState<>(policyDefinition));
        }
        return getPolicyState(mGlobalPolicies, policyDefinition);
    }

    private static <V> PolicyState<V> getPolicyState(
            Map<String, PolicyState<?>> policies, PolicyDefinition<V> policyDefinition) {
        try {
            // This will not throw an exception because policyDefinition is of type V, so unless
            // we've created two policies with the same key but different types - we can only have
            // stored a PolicyState of the right type.
            PolicyState<V> policyState = (PolicyState<V>) policies.get(
                    policyDefinition.getKey());
            return policyState;
        } catch (ClassCastException exception) {
            // TODO: handle exception properly
            throw new IllegalArgumentException();
        }
    }

    private <V> void enforcePolicy(
            PolicyDefinition<V> policyDefinition, @Nullable V policyValue, int userId) {
        // TODO: null policyValue means remove any enforced policies, ensure callbacks handle this
        //  properly
        policyDefinition.enforcePolicy(policyValue, mContext, userId);
        // TODO: send broadcast or call callback to notify admins of policy change
        // TODO: notify calling admin of result (e.g. success, runtime failure, policy set by
        //  a different admin)
    }
}
