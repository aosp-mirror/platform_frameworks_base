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
import android.app.admin.BooleanPolicyValue;
import android.app.admin.DevicePolicyIdentifiers;
import android.app.admin.DevicePolicyManager;
import android.app.admin.IntegerPolicyValue;
import android.app.admin.IntentFilterPolicyKey;
import android.app.admin.LockTaskPolicy;
import android.app.admin.NoArgsPolicyKey;
import android.app.admin.PackagePermissionPolicyKey;
import android.app.admin.PackagePolicyKey;
import android.app.admin.PolicyKey;
import android.app.admin.PolicyValue;
import android.content.ComponentName;
import android.content.Context;
import android.content.IntentFilter;
import android.os.Bundle;

import com.android.internal.util.function.QuadFunction;
import com.android.modules.utils.TypedXmlPullParser;
import com.android.modules.utils.TypedXmlSerializer;

import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class PolicyDefinition<V> {
    private static final int POLICY_FLAG_NONE = 0;

    // Only use this flag if a policy can not be applied locally.
    private static final int POLICY_FLAG_GLOBAL_ONLY_POLICY = 1;

    // Only use this flag if a policy can not be applied globally.
    private static final int POLICY_FLAG_LOCAL_ONLY_POLICY = 1 << 1;

    // Only use this flag if a policy is inheritable by child profile from parent.
    private static final int POLICY_FLAG_INHERITABLE = 1 << 2;

    // Use this flag if admin policies should be treated independently of each other and should not
    // have any resolution logic applied, this should only be used for very limited policies were
    // this would make sense and the enforcing logic should handle it appropriately, e.g.
    // application restrictions set by different admins for a single package should not be merged,
    // but saved and queried independent of each other.
    // Currently, support is  added for local only policies, if you need to add a non coexistable
    // global policy please add support.
    private static final int POLICY_FLAG_NON_COEXISTABLE_POLICY = 1 << 3;

    private static final MostRestrictive<Boolean> FALSE_MORE_RESTRICTIVE = new MostRestrictive<>(
            List.of(new BooleanPolicyValue(false), new BooleanPolicyValue(true)));

    private static final MostRestrictive<Boolean> TRUE_MORE_RESTRICTIVE = new MostRestrictive<>(
            List.of(new BooleanPolicyValue(true), new BooleanPolicyValue(false)));

    static PolicyDefinition<Boolean> AUTO_TIMEZONE = new PolicyDefinition<>(
            new NoArgsPolicyKey(DevicePolicyIdentifiers.AUTO_TIMEZONE_POLICY),
            // auto timezone is disabled by default, hence enabling it is more restrictive.
            TRUE_MORE_RESTRICTIVE,
            POLICY_FLAG_GLOBAL_ONLY_POLICY,
            (Boolean value, Context context, Integer userId, PolicyKey policyKey) ->
                    PolicyEnforcerCallbacks.setAutoTimezoneEnabled(value, context),
            new BooleanPolicySerializer());

    // This is saved in the static map sPolicyDefinitions so that we're able to reconstruct the
    // actual policy with the correct arguments (packageName and permission name)
    // when reading the policies from xml.
    static final PolicyDefinition<Integer> GENERIC_PERMISSION_GRANT =
            new PolicyDefinition<>(
                    new PackagePermissionPolicyKey(DevicePolicyIdentifiers.PERMISSION_GRANT_POLICY),
                    // TODO: is this really the best mechanism, what makes denied more
                    //  restrictive than
                    //  granted?
                    new MostRestrictive<>(
                            List.of(
                                    new IntegerPolicyValue(
                                            DevicePolicyManager.PERMISSION_GRANT_STATE_DENIED),
                                    new IntegerPolicyValue(
                                            DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED),
                                    new IntegerPolicyValue(
                                            DevicePolicyManager.PERMISSION_GRANT_STATE_DEFAULT))),
                    POLICY_FLAG_LOCAL_ONLY_POLICY,
                    PolicyEnforcerCallbacks::setPermissionGrantState,
                    new IntegerPolicySerializer());

    /**
     * Passing in {@code null} for {@code packageName} or {@code permissionName} will return a
     * {@link #GENERIC_PERMISSION_GRANT}.
     */
    static PolicyDefinition<Integer> PERMISSION_GRANT(
            @NonNull String packageName, @NonNull String permissionName) {
        if (packageName == null || permissionName == null) {
            return GENERIC_PERMISSION_GRANT;
        }
        return GENERIC_PERMISSION_GRANT.createPolicyDefinition(
                new PackagePermissionPolicyKey(
                        DevicePolicyIdentifiers.PERMISSION_GRANT_POLICY,
                        packageName,
                        permissionName));
    }

    static PolicyDefinition<LockTaskPolicy> LOCK_TASK = new PolicyDefinition<>(
            new NoArgsPolicyKey(DevicePolicyIdentifiers.LOCK_TASK_POLICY),
            new TopPriority<>(List.of(
                    // TODO(b/258166155): add correct device lock role name
                    EnforcingAdmin.getRoleAuthorityOf(
                            "android.app.role.SYSTEM_FINANCED_DEVICE_CONTROLLER"),
                    EnforcingAdmin.DPC_AUTHORITY)),
            POLICY_FLAG_LOCAL_ONLY_POLICY,
            (LockTaskPolicy value, Context context, Integer userId, PolicyKey policyKey) ->
                    PolicyEnforcerCallbacks.setLockTask(value, context, userId),
            new LockTaskPolicySerializer());

    static PolicyDefinition<Set<String>> USER_CONTROLLED_DISABLED_PACKAGES =
            new PolicyDefinition<>(
                    new NoArgsPolicyKey(
                            DevicePolicyIdentifiers.USER_CONTROL_DISABLED_PACKAGES_POLICY),
                    new StringSetUnion(),
                    (Set<String> value, Context context, Integer userId, PolicyKey policyKey) ->
                            PolicyEnforcerCallbacks.setUserControlDisabledPackages(value, userId),
                    new StringSetPolicySerializer());

    // This is saved in the static map sPolicyDefinitions so that we're able to reconstruct the
    // actual policy with the correct arguments (i.e. packageName) when reading the policies from
    // xml.
    static PolicyDefinition<ComponentName> GENERIC_PERSISTENT_PREFERRED_ACTIVITY =
            new PolicyDefinition<>(
                    new IntentFilterPolicyKey(
                            DevicePolicyIdentifiers.PERSISTENT_PREFERRED_ACTIVITY_POLICY),
            new TopPriority<>(List.of(
                    // TODO(b/258166155): add correct device lock role name
                    EnforcingAdmin.getRoleAuthorityOf(
                            "android.app.role.SYSTEM_FINANCED_DEVICE_CONTROLLER"),
                    EnforcingAdmin.DPC_AUTHORITY)),
            POLICY_FLAG_LOCAL_ONLY_POLICY,
            PolicyEnforcerCallbacks::addPersistentPreferredActivity,
            new ComponentNamePolicySerializer());

    /**
     * Passing in {@code null} for {@code intentFilter} will return
     * {@link #GENERIC_PERSISTENT_PREFERRED_ACTIVITY}.
     */
    static PolicyDefinition<ComponentName> PERSISTENT_PREFERRED_ACTIVITY(
            IntentFilter intentFilter) {
        if (intentFilter == null) {
            return GENERIC_PERSISTENT_PREFERRED_ACTIVITY;
        }
        return GENERIC_PERSISTENT_PREFERRED_ACTIVITY.createPolicyDefinition(
                new IntentFilterPolicyKey(
                        DevicePolicyIdentifiers.PERSISTENT_PREFERRED_ACTIVITY_POLICY,
                        intentFilter));
    }

    // This is saved in the static map sPolicyDefinitions so that we're able to reconstruct the
    // actual policy with the correct arguments (i.e. packageName) when reading the policies from
    // xml.
    static PolicyDefinition<Boolean> GENERIC_PACKAGE_UNINSTALL_BLOCKED =
            new PolicyDefinition<>(
                    new PackagePolicyKey(
                            DevicePolicyIdentifiers.PACKAGE_UNINSTALL_BLOCKED_POLICY),
                    TRUE_MORE_RESTRICTIVE,
                    POLICY_FLAG_LOCAL_ONLY_POLICY,
                    PolicyEnforcerCallbacks::setUninstallBlocked,
                    new BooleanPolicySerializer());

    /**
     * Passing in {@code null} for {@code packageName} will return
     * {@link #GENERIC_PACKAGE_UNINSTALL_BLOCKED}.
     */
    static PolicyDefinition<Boolean> PACKAGE_UNINSTALL_BLOCKED(
            String packageName) {
        if (packageName == null) {
            return GENERIC_PACKAGE_UNINSTALL_BLOCKED;
        }
        return GENERIC_PACKAGE_UNINSTALL_BLOCKED.createPolicyDefinition(
                new PackagePolicyKey(
                        DevicePolicyIdentifiers.PACKAGE_UNINSTALL_BLOCKED_POLICY, packageName));
    }

    // This is saved in the static map sPolicyDefinitions so that we're able to reconstruct the
    // actual policy with the correct arguments (i.e. packageName) when reading the policies from
    // xml.
    static PolicyDefinition<Bundle> GENERIC_APPLICATION_RESTRICTIONS =
            new PolicyDefinition<>(
                    new PackagePolicyKey(
                            DevicePolicyIdentifiers.APPLICATION_RESTRICTIONS_POLICY),
                    // Don't need to take in a resolution mechanism since its never used, but might
                    // need some refactoring to not always assume a non-null mechanism.
                    new MostRecent<>(),
                    POLICY_FLAG_LOCAL_ONLY_POLICY | POLICY_FLAG_NON_COEXISTABLE_POLICY,
                    // Application restrictions are now stored and retrieved from DPMS, so no
                    // enforcing is required, however DPMS calls into UM to set restrictions for
                    // backwards compatibility.
                    (Bundle value, Context context, Integer userId, PolicyKey policyKey) -> true,
                    new BundlePolicySerializer());

    /**
     * Passing in {@code null} for {@code packageName} will return
     * {@link #GENERIC_APPLICATION_RESTRICTIONS}.
     */
    static PolicyDefinition<Bundle> APPLICATION_RESTRICTIONS(
            String packageName) {
        if (packageName == null) {
            return GENERIC_APPLICATION_RESTRICTIONS;
        }
        return GENERIC_APPLICATION_RESTRICTIONS.createPolicyDefinition(
                new PackagePolicyKey(
                        DevicePolicyIdentifiers.APPLICATION_RESTRICTIONS_POLICY, packageName));
    }

    static PolicyDefinition<Long> RESET_PASSWORD_TOKEN = new PolicyDefinition<>(
            new NoArgsPolicyKey(DevicePolicyIdentifiers.RESET_PASSWORD_TOKEN_POLICY),
            // Don't need to take in a resolution mechanism since its never used, but might
            // need some refactoring to not always assume a non-null mechanism.
            new MostRecent<>(),
            POLICY_FLAG_LOCAL_ONLY_POLICY | POLICY_FLAG_NON_COEXISTABLE_POLICY,
            // DevicePolicyManagerService handles the enforcement, this just takes care of storage
            (Long value, Context context, Integer userId, PolicyKey policyKey) -> true,
            new LongPolicySerializer());

    private static final Map<String, PolicyDefinition<?>> sPolicyDefinitions = Map.of(
            DevicePolicyIdentifiers.AUTO_TIMEZONE_POLICY, AUTO_TIMEZONE,
            DevicePolicyIdentifiers.PERMISSION_GRANT_POLICY, GENERIC_PERMISSION_GRANT,
            DevicePolicyIdentifiers.LOCK_TASK_POLICY, LOCK_TASK,
            DevicePolicyIdentifiers.USER_CONTROL_DISABLED_PACKAGES_POLICY,
            USER_CONTROLLED_DISABLED_PACKAGES,
            DevicePolicyIdentifiers.PERSISTENT_PREFERRED_ACTIVITY_POLICY,
            GENERIC_PERSISTENT_PREFERRED_ACTIVITY,
            DevicePolicyIdentifiers.PACKAGE_UNINSTALL_BLOCKED_POLICY,
            GENERIC_PACKAGE_UNINSTALL_BLOCKED,
            DevicePolicyIdentifiers.APPLICATION_RESTRICTIONS_POLICY,
            GENERIC_APPLICATION_RESTRICTIONS,
            DevicePolicyIdentifiers.RESET_PASSWORD_TOKEN_POLICY,
            RESET_PASSWORD_TOKEN
    );

    private final PolicyKey mPolicyKey;
    private final ResolutionMechanism<V> mResolutionMechanism;
    private final int mPolicyFlags;
    // A function that accepts  policy to apple, context, userId, callback arguments, and returns
    // true if the policy has been enforced successfully.
    private final QuadFunction<V, Context, Integer, PolicyKey, Boolean> mPolicyEnforcerCallback;
    private final PolicySerializer<V> mPolicySerializer;

    private PolicyDefinition<V> createPolicyDefinition(PolicyKey key) {
        return new PolicyDefinition<>(key, mResolutionMechanism, mPolicyFlags,
                mPolicyEnforcerCallback, mPolicySerializer);
    }

    @NonNull
    PolicyKey getPolicyKey() {
        return mPolicyKey;
    }

    @NonNull
    ResolutionMechanism<V> getResolutionMechanism() {
        return mResolutionMechanism;
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

    /**
     * Returns {@code true} if the policy is inheritable by child profiles.
     */
    boolean isInheritable() {
        return (mPolicyFlags & POLICY_FLAG_INHERITABLE) != 0;
    }

    /**
     * Returns {@code true} if the policy engine should not try to resolve policies set by different
     * admins and should just store it and pass it on to the enforcing logic.
     */
    boolean isNonCoexistablePolicy() {
        return (mPolicyFlags & POLICY_FLAG_NON_COEXISTABLE_POLICY) != 0;
    }

    @Nullable
    PolicyValue<V> resolvePolicy(LinkedHashMap<EnforcingAdmin, PolicyValue<V>> adminsPolicy) {
        return mResolutionMechanism.resolve(adminsPolicy);
    }

    boolean enforcePolicy(@Nullable V value, Context context, int userId) {
        return mPolicyEnforcerCallback.apply(value, context, userId, mPolicyKey);
    }

    /**
     * Callers must ensure that {@code policyType} have implemented an appropriate
     * {@link Object#equals} implementation.
     */
    private PolicyDefinition(
            PolicyKey key,
            ResolutionMechanism<V> resolutionMechanism,
            QuadFunction<V, Context, Integer, PolicyKey, Boolean> policyEnforcerCallback,
            PolicySerializer<V> policySerializer) {
        this(key, resolutionMechanism, POLICY_FLAG_NONE, policyEnforcerCallback, policySerializer);
    }

    /**
     * Callers must ensure that custom {@code policyKeys} and {@code V} have an appropriate
     * {@link Object#equals} and {@link Object#hashCode()} implementation.
     */
    private PolicyDefinition(
            PolicyKey policyKey,
            ResolutionMechanism<V> resolutionMechanism,
            int policyFlags,
            QuadFunction<V, Context, Integer, PolicyKey, Boolean> policyEnforcerCallback,
            PolicySerializer<V> policySerializer) {
        mPolicyKey = policyKey;
        mResolutionMechanism = resolutionMechanism;
        mPolicyFlags = policyFlags;
        mPolicyEnforcerCallback = policyEnforcerCallback;
        mPolicySerializer = policySerializer;

        if (isNonCoexistablePolicy() && !isLocalOnlyPolicy()) {
            throw new UnsupportedOperationException("Non-coexistable global policies not supported,"
                    + "please add support.");
        }
        // TODO: maybe use this instead of manually adding to the map
//        sPolicyDefinitions.put(policyDefinitionKey, this);
    }

    void saveToXml(TypedXmlSerializer serializer) throws IOException {
        // TODO: here and elsewhere, add tags to ensure attributes aren't overridden by duplication.
        mPolicyKey.saveToXml(serializer);
    }

    static <V> PolicyDefinition<V> readFromXml(TypedXmlPullParser parser)
            throws XmlPullParserException, IOException {
        // TODO: can we avoid casting?
        PolicyKey policyKey = readPolicyKeyFromXml(parser);
        PolicyDefinition<V> genericPolicyDefinition =
                (PolicyDefinition<V>) sPolicyDefinitions.get(policyKey.getIdentifier());
        return genericPolicyDefinition.createPolicyDefinition(policyKey);
    }

    static <V> PolicyKey readPolicyKeyFromXml(TypedXmlPullParser parser)
            throws XmlPullParserException, IOException {
        // TODO: can we avoid casting?
        PolicyKey policyKey = PolicyKey.readGenericPolicyKeyFromXml(parser);
        PolicyDefinition<PolicyValue<V>> genericPolicyDefinition =
                (PolicyDefinition<PolicyValue<V>>) sPolicyDefinitions.get(
                        policyKey.getIdentifier());
        return genericPolicyDefinition.mPolicyKey.readFromXml(parser);
    }

    void savePolicyValueToXml(TypedXmlSerializer serializer, String attributeName, V value)
            throws IOException {
        mPolicySerializer.saveToXml(mPolicyKey, serializer, attributeName, value);
    }

    @Nullable
    PolicyValue<V> readPolicyValueFromXml(TypedXmlPullParser parser, String attributeName) {
        return mPolicySerializer.readFromXml(parser, attributeName);
    }

    @Override
    public String toString() {
        return "PolicyDefinition{ mPolicyKey= " + mPolicyKey + ", mResolutionMechanism= "
                + mResolutionMechanism + ", mPolicyFlags= " + mPolicyFlags + " }";
    }
}
