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
import android.util.Log;

import com.android.internal.util.XmlUtils;
import com.android.modules.utils.TypedXmlPullParser;
import com.android.modules.utils.TypedXmlSerializer;

import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Objects;

/**
 * Class containing all values set for a certain policy by different admins.
 */
final class PolicyState<V> {
    private static final String TAG_ADMIN_POLICY_ENTRY = "admin-policy-entry";
    private static final String TAG_ENFORCING_ADMIN_ENTRY = "enforcing-admin-entry";
    private static final String ATTR_POLICY_VALUE = "policy-value";
    private static final String ATTR_RESOLVED_POLICY = "resolved-policy";

    private final PolicyDefinition<V> mPolicyDefinition;
    private final LinkedHashMap<EnforcingAdmin, V> mPoliciesSetByAdmins = new LinkedHashMap<>();
    private V mCurrentResolvedPolicy;

    PolicyState(@NonNull PolicyDefinition<V> policyDefinition) {
        mPolicyDefinition = Objects.requireNonNull(policyDefinition);
    }

    private PolicyState(
            @NonNull PolicyDefinition<V> policyDefinition,
            @NonNull LinkedHashMap<EnforcingAdmin, V> policiesSetByAdmins,
            V currentEnforcedPolicy) {
        Objects.requireNonNull(policyDefinition);
        Objects.requireNonNull(policiesSetByAdmins);

        mPolicyDefinition = policyDefinition;
        mPoliciesSetByAdmins.putAll(policiesSetByAdmins);
        mCurrentResolvedPolicy = currentEnforcedPolicy;
    }

    /**
     * Returns {@code true} if the resolved policy has changed, {@code false} otherwise.
     */
    boolean addPolicy(@NonNull EnforcingAdmin admin, @NonNull V policy) {
        mPoliciesSetByAdmins.put(Objects.requireNonNull(admin), Objects.requireNonNull(policy));

        return resolvePolicy();
    }

    /**
     * Takes into account global policies set by the admin when resolving the policy, this is only
     * relevant to local policies that can be applied globally as well.
     *
     * <p> Note that local policies set by an admin takes precedence over global policies set by the
     * same admin.
     *
     * Returns {@code true} if the resolved policy has changed, {@code false} otherwise.
     */
    boolean addPolicy(
            @NonNull EnforcingAdmin admin, @NonNull V policy,
            LinkedHashMap<EnforcingAdmin, V> globalPoliciesSetByAdmins) {
        mPoliciesSetByAdmins.put(Objects.requireNonNull(admin), Objects.requireNonNull(policy));

        return resolvePolicy(globalPoliciesSetByAdmins);
    }

    /**
     * Returns {@code true} if the resolved policy has changed, {@code false} otherwise.
     */
    boolean removePolicy(@NonNull EnforcingAdmin admin) {
        Objects.requireNonNull(admin);

        if (mPoliciesSetByAdmins.remove(admin) == null) {
            return false;
        }

        return resolvePolicy();
    }

    /**
     * Takes into account global policies set by the admin when resolving the policy, this is only
     * relevant to local policies that can be applied globally as well.
     *
     * <p> Note that local policies set by an admin takes precedence over global policies set by the
     * same admin.
     *
     * Returns {@code true} if the resolved policy has changed, {@code false} otherwise.
     */
    boolean removePolicy(
            @NonNull EnforcingAdmin admin,
            LinkedHashMap<EnforcingAdmin, V> globalPoliciesSetByAdmins) {
        Objects.requireNonNull(admin);

        if (mPoliciesSetByAdmins.remove(admin) == null) {
            return false;
        }

        return resolvePolicy(globalPoliciesSetByAdmins);
    }

    /**
     * Takes into account global policies set by the admin when resolving the policy, this is only
     * relevant to local policies that can be applied globally as well.
     *
     * <p> Note that local policies set by an admin takes precedence over global policies set by the
     * same admin.
     *
     * Returns {@code true} if the resolved policy has changed, {@code false} otherwise.
     */
    boolean resolvePolicy(LinkedHashMap<EnforcingAdmin, V> globalPoliciesSetByAdmins) {
        // Add global policies first then override with local policies for the same admin.
        LinkedHashMap<EnforcingAdmin, V> mergedPolicies =
                new LinkedHashMap<>(globalPoliciesSetByAdmins);
        mergedPolicies.putAll(mPoliciesSetByAdmins);

        V resolvedPolicy = mPolicyDefinition.resolvePolicy(mergedPolicies);
        boolean policyChanged = !Objects.equals(resolvedPolicy, mCurrentResolvedPolicy);
        mCurrentResolvedPolicy = resolvedPolicy;

        return policyChanged;
    }

    @NonNull
    LinkedHashMap<EnforcingAdmin, V> getPoliciesSetByAdmins() {
        return new LinkedHashMap<>(mPoliciesSetByAdmins);
    }

    private boolean resolvePolicy() {
        V resolvedPolicy = mPolicyDefinition.resolvePolicy(mPoliciesSetByAdmins);
        boolean policyChanged = !Objects.equals(resolvedPolicy, mCurrentResolvedPolicy);
        mCurrentResolvedPolicy = resolvedPolicy;

        return policyChanged;
    }

    @Nullable
    V getCurrentResolvedPolicy() {
        return mCurrentResolvedPolicy;
    }

    @Override
    public String toString() {
        return "PolicyState { mPolicyDefinition= " + mPolicyDefinition + ", mPoliciesSetByAdmins= "
                + mPoliciesSetByAdmins + ", mCurrentResolvedPolicy= " + mCurrentResolvedPolicy
                + " }";
    }

    void saveToXml(TypedXmlSerializer serializer) throws IOException {
        mPolicyDefinition.saveToXml(serializer);

        if (mCurrentResolvedPolicy != null) {
            mPolicyDefinition.savePolicyValueToXml(
                    serializer, ATTR_RESOLVED_POLICY, mCurrentResolvedPolicy);
        }

        for (EnforcingAdmin admin : mPoliciesSetByAdmins.keySet()) {
            serializer.startTag(/* namespace= */ null, TAG_ADMIN_POLICY_ENTRY);

            mPolicyDefinition.savePolicyValueToXml(
                    serializer, ATTR_POLICY_VALUE, mPoliciesSetByAdmins.get(admin));

            serializer.startTag(/* namespace= */ null, TAG_ENFORCING_ADMIN_ENTRY);
            admin.saveToXml(serializer);
            serializer.endTag(/* namespace= */ null, TAG_ENFORCING_ADMIN_ENTRY);

            serializer.endTag(/* namespace= */ null, TAG_ADMIN_POLICY_ENTRY);
        }
    }

    static <V> PolicyState<V> readFromXml(TypedXmlPullParser parser)
            throws IOException, XmlPullParserException {

        PolicyDefinition<V> policyDefinition = PolicyDefinition.readFromXml(parser);

        V currentResolvedPolicy = policyDefinition.readPolicyValueFromXml(
                parser, ATTR_RESOLVED_POLICY);

        LinkedHashMap<EnforcingAdmin, V> policiesSetByAdmins = new LinkedHashMap<>();
        int outerDepth = parser.getDepth();
        while (XmlUtils.nextElementWithin(parser, outerDepth)) {
            String tag = parser.getName();
            if (TAG_ADMIN_POLICY_ENTRY.equals(tag)) {
                V value = policyDefinition.readPolicyValueFromXml(
                        parser, ATTR_POLICY_VALUE);
                EnforcingAdmin admin;
                int adminPolicyDepth = parser.getDepth();
                if (XmlUtils.nextElementWithin(parser, adminPolicyDepth)
                        && parser.getName().equals(TAG_ENFORCING_ADMIN_ENTRY)) {
                    admin = EnforcingAdmin.readFromXml(parser);
                    policiesSetByAdmins.put(admin, value);
                }
            } else {
                Log.e(DevicePolicyEngine.TAG, "Unknown tag: " + tag);
            }
        }
        return new PolicyState<V>(policyDefinition, policiesSetByAdmins, currentResolvedPolicy);
    }
}
