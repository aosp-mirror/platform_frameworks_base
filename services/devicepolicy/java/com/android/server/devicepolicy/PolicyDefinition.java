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
import android.app.admin.DevicePolicyManager;
import android.content.Context;

import com.android.internal.util.function.QuadFunction;

import java.util.LinkedHashMap;
import java.util.List;

final class PolicyDefinition<V> {
    private static final int POLICY_FLAG_NONE = 0;
    // Only use this flag if a policy can not be applied locally.
    private static final int POLICY_FLAG_GLOBAL_ONLY_POLICY = 1;

    // Only use this flag if a policy can not be applied globally.
    private static final int POLICY_FLAG_LOCAL_ONLY_POLICY = 1 << 1;

    private static final MostRestrictive<Boolean> FALSE_MORE_RESTRICTIVE = new MostRestrictive<>(
            List.of(false, true));

    static PolicyDefinition<Boolean> AUTO_TIMEZONE = new PolicyDefinition<>(
            DevicePolicyManager.AUTO_TIMEZONE_POLICY,
            // auto timezone is enabled by default, hence disabling it is more restrictive.
            FALSE_MORE_RESTRICTIVE,
            POLICY_FLAG_GLOBAL_ONLY_POLICY,
            (Boolean value, Context context, Integer userId, String[] args) ->
                    PolicyEnforcerCallbacks.setAutoTimezoneEnabled(value, context));

    static PolicyDefinition<Integer> PERMISSION_GRANT(
            @NonNull String packageName, @NonNull String permission) {
        return new PolicyDefinition<>(
                DevicePolicyManager.PERMISSION_GRANT_POLICY(packageName, permission),
                // TODO: is this really the best mechanism, what makes denied more restrictive than
                //  granted?
                new MostRestrictive<>(
                        List.of(DevicePolicyManager.PERMISSION_GRANT_STATE_DENIED,
                                DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED,
                                DevicePolicyManager.PERMISSION_GRANT_STATE_DEFAULT)),
                POLICY_FLAG_LOCAL_ONLY_POLICY,
                PolicyEnforcerCallbacks::setPermissionGrantState,
                new String[]{packageName, permission});
    }

    private final String mKey;
    private final ResolutionMechanism<V> mResolutionMechanism;
    private final int mPolicyFlags;
    // A function that accepts  policy to apple, context, userId, callback arguments, and returns
    // true if the policy has been enforced successfully.
    private final QuadFunction<V, Context, Integer, String[], Boolean> mPolicyEnforcerCallback;
    private final String[] mCallbackArgs;

    String getKey() {
        return mKey;
    }

    /**
     * Returns {@code true} if the policy is a global policy by nature and can't be applied locally.
     */
    boolean isGlobalOnlyPolicy() {
        return (mPolicyFlags & POLICY_FLAG_GLOBAL_ONLY_POLICY) != 0;
    }

    /**
     * Returns {@code true} if the policy is a local policy by nature and can't be applied globally.
     */
    boolean isLocalOnlyPolicy() {
        return (mPolicyFlags & POLICY_FLAG_LOCAL_ONLY_POLICY) != 0;
    }

    @Nullable
    V resolvePolicy(LinkedHashMap<EnforcingAdmin, V> adminsPolicy) {
        return mResolutionMechanism.resolve(adminsPolicy);
    }

    boolean enforcePolicy(@Nullable V value, Context context, int userId) {
        return mPolicyEnforcerCallback.apply(value, context, userId, mCallbackArgs);
    }

    /**
     * Callers must ensure that {@code policyType} have implemented an appropriate
     * {@link Object#equals)} implementation.
     */
    private PolicyDefinition(
            String key,
            ResolutionMechanism<V> resolutionMechanism,
            QuadFunction<V, Context, Integer, String[], Boolean> policyEnforcerCallback,
            String[] callbackArgs) {
        this(key, resolutionMechanism, POLICY_FLAG_NONE, policyEnforcerCallback,
                callbackArgs);
    }

    /**
     * Callers must ensure that {@code policyType} have implemented an appropriate
     * {@link Object#equals)} implementation.
     */
    private PolicyDefinition(
            String key,
            ResolutionMechanism<V> resolutionMechanism,
            QuadFunction<V, Context, Integer, String[], Boolean> policyEnforcerCallback) {
        this(key, resolutionMechanism, policyEnforcerCallback, /* callbackArgs= */ null);
    }

    /**
     * Callers must ensure that {@code policyType} have implemented an appropriate
     * {@link Object#equals)} implementation.
     */
    private PolicyDefinition(
            String key,
            ResolutionMechanism<V> resolutionMechanism,
            int policyFlags,
            QuadFunction<V, Context, Integer, String[], Boolean> policyEnforcerCallback,
            String[] callbackArgs) {
        mKey = key;
        mResolutionMechanism = resolutionMechanism;
        mPolicyFlags = policyFlags;
        mPolicyEnforcerCallback = policyEnforcerCallback;
        mCallbackArgs = callbackArgs;
    }

    /**
     * Callers must ensure that {@code policyType} have implemented an appropriate
     * {@link Object#equals)} implementation.
     */
    private PolicyDefinition(
            String key,
            ResolutionMechanism<V> resolutionMechanism,
            int policyFlags,
            QuadFunction<V, Context, Integer, String[], Boolean> policyEnforcerCallback) {
        this(key, resolutionMechanism, policyFlags, policyEnforcerCallback,
                /* callbackArgs= */ null);
    }
}
