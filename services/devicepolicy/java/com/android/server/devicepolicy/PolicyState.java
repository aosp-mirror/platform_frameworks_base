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
import android.app.admin.PolicyValue;
import android.app.admin.flags.Flags;
import android.util.IndentingPrintWriter;

import com.android.internal.util.XmlUtils;
import com.android.modules.utils.TypedXmlPullParser;
import com.android.modules.utils.TypedXmlSerializer;
import com.android.server.utils.Slogf;

import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Objects;

/**
 * Class containing all values set for a certain policy by different admins.
 */
final class PolicyState<V> {

    private static final String TAG = "PolicyState";
    private static final String TAG_ADMIN_POLICY_ENTRY = "admin-policy-entry";

    private static final String TAG_POLICY_DEFINITION_ENTRY = "policy-definition-entry";
    private static final String TAG_RESOLVED_VALUE_ENTRY = "resolved-value-entry";
    private static final String TAG_ENFORCING_ADMIN_ENTRY = "enforcing-admin-entry";
    private static final String TAG_POLICY_VALUE_ENTRY = "policy-value-entry";
    private final PolicyDefinition<V> mPolicyDefinition;
    private final LinkedHashMap<EnforcingAdmin, PolicyValue<V>> mPoliciesSetByAdmins =
            new LinkedHashMap<>();
    private PolicyValue<V> mCurrentResolvedPolicy;

    PolicyState(@NonNull PolicyDefinition<V> policyDefinition) {
        mPolicyDefinition = Objects.requireNonNull(policyDefinition);
    }

    private PolicyState(
            @NonNull PolicyDefinition<V> policyDefinition,
            @NonNull LinkedHashMap<EnforcingAdmin, PolicyValue<V>> policiesSetByAdmins,
            PolicyValue<V> currentEnforcedPolicy) {
        Objects.requireNonNull(policyDefinition);
        Objects.requireNonNull(policiesSetByAdmins);

        mPolicyDefinition = policyDefinition;
        mPoliciesSetByAdmins.putAll(policiesSetByAdmins);
        mCurrentResolvedPolicy = currentEnforcedPolicy;
    }

    /**
     * Returns {@code true} if the resolved policy has changed, {@code false} otherwise.
     */
    boolean addPolicy(@NonNull EnforcingAdmin admin, @Nullable PolicyValue<V> policy) {
        Objects.requireNonNull(admin);

        //LinkedHashMap doesn't update the insertion order of existing keys, removing the existing
        // key will cause it to update.
        mPoliciesSetByAdmins.remove(admin);
        mPoliciesSetByAdmins.put(admin, policy);

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
            @NonNull EnforcingAdmin admin, @Nullable PolicyValue<V> policy,
            LinkedHashMap<EnforcingAdmin, PolicyValue<V>> globalPoliciesSetByAdmins) {
        mPoliciesSetByAdmins.put(Objects.requireNonNull(admin), policy);

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
            LinkedHashMap<EnforcingAdmin, PolicyValue<V>> globalPoliciesSetByAdmins) {
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
    boolean resolvePolicy(LinkedHashMap<EnforcingAdmin, PolicyValue<V>> globalPoliciesSetByAdmins) {
        //Non coexistable policies don't need resolving
        if (mPolicyDefinition.isNonCoexistablePolicy()) {
            return false;
        }
        // Add global policies first then override with local policies for the same admin.
        LinkedHashMap<EnforcingAdmin, PolicyValue<V>> mergedPolicies =
                new LinkedHashMap<>(globalPoliciesSetByAdmins);
        mergedPolicies.putAll(mPoliciesSetByAdmins);

        PolicyValue<V> resolvedPolicy = mPolicyDefinition.resolvePolicy(mergedPolicies);
        boolean policyChanged = !Objects.equals(resolvedPolicy, mCurrentResolvedPolicy);
        mCurrentResolvedPolicy = resolvedPolicy;

        return policyChanged;
    }

    @NonNull
    LinkedHashMap<EnforcingAdmin, PolicyValue<V>> getPoliciesSetByAdmins() {
        return new LinkedHashMap<>(mPoliciesSetByAdmins);
    }

    private boolean resolvePolicy() {
        //Non coexistable policies don't need resolving
        if (mPolicyDefinition.isNonCoexistablePolicy()) {
            return false;
        }
        PolicyValue<V> resolvedPolicy = mPolicyDefinition.resolvePolicy(mPoliciesSetByAdmins);
        boolean policyChanged = !Objects.equals(resolvedPolicy, mCurrentResolvedPolicy);
        mCurrentResolvedPolicy = resolvedPolicy;

        return policyChanged;
    }

    @Nullable
    PolicyValue<V> getCurrentResolvedPolicy() {
        return mCurrentResolvedPolicy;
    }

    android.app.admin.PolicyState<V> getParcelablePolicyState() {
        LinkedHashMap<android.app.admin.EnforcingAdmin, PolicyValue<V>> adminPolicies =
                new LinkedHashMap<>();
        for (EnforcingAdmin admin : mPoliciesSetByAdmins.keySet()) {
            adminPolicies.put(admin.getParcelableAdmin(), mPoliciesSetByAdmins.get(admin));
        }
        return new android.app.admin.PolicyState<>(adminPolicies, mCurrentResolvedPolicy,
                mPolicyDefinition.getResolutionMechanism().getParcelableResolutionMechanism());
    }

    @Override
    public String toString() {
        return "\nPolicyKey - " + mPolicyDefinition.getPolicyKey()
                + "\nmPolicyDefinition= \n\t" + mPolicyDefinition
                + "\nmPoliciesSetByAdmins= \n\t" + mPoliciesSetByAdmins
                + ",\nmCurrentResolvedPolicy= \n\t" + mCurrentResolvedPolicy + " }";
    }

    public void dump(IndentingPrintWriter pw) {
        pw.println(mPolicyDefinition.getPolicyKey());
        pw.increaseIndent();

        pw.println("Per-admin Policy:");
        pw.increaseIndent();
        if (mPoliciesSetByAdmins.size() == 0) {
            pw.println("null");
        } else {
            for (EnforcingAdmin admin : mPoliciesSetByAdmins.keySet()) {
                pw.println(admin);
                pw.increaseIndent();
                pw.println(mPoliciesSetByAdmins.get(admin));
                pw.decreaseIndent();
            }
        }
        pw.decreaseIndent();

        pw.printf("Resolved Policy (%s):\n",
                mPolicyDefinition.getResolutionMechanism().getClass().getSimpleName());
        pw.increaseIndent();
        pw.println(mCurrentResolvedPolicy);
        pw.decreaseIndent();

        pw.decreaseIndent();
    }

    void saveToXml(TypedXmlSerializer serializer) throws IOException {
        serializer.startTag(/* namespace= */ null, TAG_POLICY_DEFINITION_ENTRY);
        mPolicyDefinition.saveToXml(serializer);
        serializer.endTag(/* namespace= */ null, TAG_POLICY_DEFINITION_ENTRY);

        if (mCurrentResolvedPolicy != null) {
            serializer.startTag(/* namespace= */ null, TAG_RESOLVED_VALUE_ENTRY);
            mPolicyDefinition.savePolicyValueToXml(
                    serializer, mCurrentResolvedPolicy.getValue());
            serializer.endTag(/* namespace= */ null, TAG_RESOLVED_VALUE_ENTRY);
        }

        for (EnforcingAdmin admin : mPoliciesSetByAdmins.keySet()) {
            serializer.startTag(/* namespace= */ null, TAG_ADMIN_POLICY_ENTRY);

            if (mPoliciesSetByAdmins.get(admin) != null) {
                serializer.startTag(/* namespace= */ null, TAG_POLICY_VALUE_ENTRY);
                mPolicyDefinition.savePolicyValueToXml(
                        serializer, mPoliciesSetByAdmins.get(admin).getValue());
                serializer.endTag(/* namespace= */ null, TAG_POLICY_VALUE_ENTRY);
            }

            serializer.startTag(/* namespace= */ null, TAG_ENFORCING_ADMIN_ENTRY);
            admin.saveToXml(serializer);
            serializer.endTag(/* namespace= */ null, TAG_ENFORCING_ADMIN_ENTRY);

            serializer.endTag(/* namespace= */ null, TAG_ADMIN_POLICY_ENTRY);
        }
    }

    @Nullable
    static <V> PolicyState<V> readFromXml(
            PolicyDefinition<V> policyDefinition, TypedXmlPullParser parser)
            throws IOException, XmlPullParserException {
        PolicyValue<V> currentResolvedPolicy = null;

        LinkedHashMap<EnforcingAdmin, PolicyValue<V>> policiesSetByAdmins = new LinkedHashMap<>();
        int outerDepth = parser.getDepth();
        while (XmlUtils.nextElementWithin(parser, outerDepth)) {
            String tag = parser.getName();
            switch (tag) {
                case TAG_ADMIN_POLICY_ENTRY:
                    PolicyValue<V> value = null;
                    EnforcingAdmin admin = null;
                    int adminPolicyDepth = parser.getDepth();
                    while (XmlUtils.nextElementWithin(parser, adminPolicyDepth)) {
                        String adminPolicyTag = parser.getName();
                        switch (adminPolicyTag) {
                            case TAG_ENFORCING_ADMIN_ENTRY:
                                admin = EnforcingAdmin.readFromXml(parser);
                                if (admin == null) {
                                    Slogf.wtf(TAG, "Error Parsing TAG_ENFORCING_ADMIN_ENTRY, "
                                            + "EnforcingAdmin is null");
                                }
                                break;
                            case TAG_POLICY_VALUE_ENTRY:
                                value = policyDefinition.readPolicyValueFromXml(parser);
                                if (value == null) {
                                    Slogf.wtf(TAG, "Error Parsing TAG_POLICY_VALUE_ENTRY, "
                                            + "PolicyValue is null");
                                }
                                break;
                        }
                    }
                    if (admin != null && value != null) {
                        policiesSetByAdmins.put(admin, value);
                    } else {
                        Slogf.wtf(TAG, "Error Parsing TAG_ADMIN_POLICY_ENTRY for "
                                + (policyDefinition == null ? "unknown policy" : "policy with "
                                + "definition " + policyDefinition) + ", EnforcingAdmin is: "
                                + (admin == null ? "null" : admin) + ", value is : "
                                + (value == null ? "null" : value));
                    }
                    break;
                case TAG_POLICY_DEFINITION_ENTRY:
                    if (Flags.dontReadPolicyDefinition()) {
                        // Should be passed by the caller.
                        Objects.requireNonNull(policyDefinition);
                    } else {
                        policyDefinition = PolicyDefinition.readFromXml(parser);
                        if (policyDefinition == null) {
                            Slogf.wtf(TAG, "Error Parsing TAG_POLICY_DEFINITION_ENTRY, "
                                    + "PolicyDefinition is null");
                        }
                    }
                    break;

                case TAG_RESOLVED_VALUE_ENTRY:
                    if (policyDefinition == null) {
                        Slogf.wtf(TAG, "Error Parsing TAG_RESOLVED_VALUE_ENTRY, "
                                + "policyDefinition is null");
                        break;
                    }
                    currentResolvedPolicy = policyDefinition.readPolicyValueFromXml(parser);
                    if (currentResolvedPolicy == null) {
                        Slogf.wtf(TAG, "Error Parsing TAG_RESOLVED_VALUE_ENTRY for "
                                + (policyDefinition == null ? "unknown policy" : "policy with "
                                + "definition " + policyDefinition) + ", "
                                + "currentResolvedPolicy is null");
                    }
                    break;
                default:
                    Slogf.wtf(TAG, "Unknown tag: " + tag);
            }
        }
        if (policyDefinition != null) {
            return new PolicyState<V>(policyDefinition, policiesSetByAdmins, currentResolvedPolicy);
        } else {
            Slogf.wtf(TAG, "Error parsing policyState, policyDefinition is null");
            return null;
        }
    }



    PolicyDefinition<V> getPolicyDefinition() {
        return mPolicyDefinition;
    }
}
