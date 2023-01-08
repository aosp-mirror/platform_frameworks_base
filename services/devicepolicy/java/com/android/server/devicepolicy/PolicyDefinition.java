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
import android.app.admin.PolicyUpdatesReceiver;
import android.content.Context;
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

    private static final MostRestrictive<Boolean> FALSE_MORE_RESTRICTIVE = new MostRestrictive<>(
            List.of(false, true));

    private static final String ATTR_POLICY_KEY = "policy-key";
    private static final String ATTR_POLICY_DEFINITION_KEY = "policy-type-key";
    private static final String ATTR_CALLBACK_ARGS_SIZE = "size";
    private static final String ATTR_CALLBACK_ARGS_KEY = "key";
    private static final String ATTR_CALLBACK_ARGS_VALUE = "value";


    static PolicyDefinition<Boolean> AUTO_TIMEZONE = new PolicyDefinition<>(
            DevicePolicyManager.AUTO_TIMEZONE_POLICY,
            // auto timezone is enabled by default, hence disabling it is more restrictive.
            FALSE_MORE_RESTRICTIVE,
            POLICY_FLAG_GLOBAL_ONLY_POLICY,
            (Boolean value, Context context, Integer userId, Bundle args) ->
                    PolicyEnforcerCallbacks.setAutoTimezoneEnabled(value, context),
            new BooleanPolicySerializer());

    // This is saved in the static map sPolicyDefinitions so that we're able to reconstruct the
    // actual permission grant policy with the correct arguments (packageName and permission name)
    // when reading the policies from xml.
    private static final PolicyDefinition<Integer> PERMISSION_GRANT_NO_ARGS =
            new PolicyDefinition<>(DevicePolicyManager.PERMISSION_GRANT_POLICY_KEY,
                    // TODO: is this really the best mechanism, what makes denied more
                    //  restrictive than
                    //  granted?
                    new MostRestrictive<>(
                            List.of(DevicePolicyManager.PERMISSION_GRANT_STATE_DENIED,
                                    DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED,
                                    DevicePolicyManager.PERMISSION_GRANT_STATE_DEFAULT)),
                    POLICY_FLAG_LOCAL_ONLY_POLICY,
                    PolicyEnforcerCallbacks::setPermissionGrantState,
                    new IntegerPolicySerializer());

    static PolicyDefinition<Integer> PERMISSION_GRANT(
            @NonNull String packageName, @NonNull String permission) {
        Bundle callbackArgs = new Bundle();
        callbackArgs.putString(PolicyUpdatesReceiver.EXTRA_PACKAGE_NAME, packageName);
        callbackArgs.putString(PolicyUpdatesReceiver.EXTRA_PERMISSION_NAME, permission);
        return PERMISSION_GRANT_NO_ARGS.setArgs(
                DevicePolicyManager.PERMISSION_GRANT_POLICY(packageName, permission), callbackArgs);
    }

    static PolicyDefinition<LockTaskPolicy> LOCK_TASK = new PolicyDefinition<>(
            DevicePolicyManager.LOCK_TASK_POLICY,
            new TopPriority<>(List.of(
                    // TODO(b/258166155): add correct device lock role name
                    EnforcingAdmin.getRoleAuthorityOf("DeviceLock"),
                    EnforcingAdmin.DPC_AUTHORITY)),
            POLICY_FLAG_LOCAL_ONLY_POLICY,
            (LockTaskPolicy value, Context context, Integer userId, Bundle args) ->
                    PolicyEnforcerCallbacks.setLockTask(value, context, userId),
            new LockTaskPolicy.LockTaskPolicySerializer());

    static PolicyDefinition<Set<String>> USER_CONTROLLED_DISABLED_PACKAGES = new PolicyDefinition<>(
            DevicePolicyManager.USER_CONTROL_DISABLED_PACKAGES,
            new SetUnion<>(),
            (Set<String> value, Context context, Integer userId, Bundle args) ->
                    PolicyEnforcerCallbacks.setUserControlDisabledPackages(value, userId),
            new SetPolicySerializer<>());

    private static final Map<String, PolicyDefinition<?>> sPolicyDefinitions = Map.of(
            DevicePolicyManager.AUTO_TIMEZONE_POLICY, AUTO_TIMEZONE,
            DevicePolicyManager.PERMISSION_GRANT_POLICY_KEY, PERMISSION_GRANT_NO_ARGS,
            DevicePolicyManager.LOCK_TASK_POLICY, LOCK_TASK,
            DevicePolicyManager.USER_CONTROL_DISABLED_PACKAGES, USER_CONTROLLED_DISABLED_PACKAGES
    );


    private final String mPolicyKey;
    private final String mPolicyDefinitionKey;
    private final ResolutionMechanism<V> mResolutionMechanism;
    private final int mPolicyFlags;
    // A function that accepts  policy to apple, context, userId, callback arguments, and returns
    // true if the policy has been enforced successfully.
    private final QuadFunction<V, Context, Integer, Bundle, Boolean> mPolicyEnforcerCallback;
    private final Bundle mCallbackArgs;
    private final PolicySerializer<V> mPolicySerializer;

    private PolicyDefinition<V> setArgs(String key, Bundle callbackArgs) {
        return new PolicyDefinition<>(key, mPolicyDefinitionKey, mResolutionMechanism,
                mPolicyFlags, mPolicyEnforcerCallback, mPolicySerializer, callbackArgs);
    }

    @NonNull
    String getPolicyKey() {
        return mPolicyKey;
    }

    @NonNull
    String getPolicyDefinitionKey() {
        return mPolicyDefinitionKey;
    }

    @Nullable
    Bundle getCallbackArgs() {
        return mCallbackArgs;
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
     * {@link Object#equals} implementation.
     */
    private PolicyDefinition(
            String key,
            ResolutionMechanism<V> resolutionMechanism,
            QuadFunction<V, Context, Integer, Bundle, Boolean> policyEnforcerCallback,
            PolicySerializer<V> policySerializer) {
        this(key, resolutionMechanism, POLICY_FLAG_NONE, policyEnforcerCallback, policySerializer);
    }

    /**
     * Callers must ensure that {@code policyType} have implemented an appropriate
     * {@link Object#equals} implementation.
     */
    private PolicyDefinition(
            String key,
            ResolutionMechanism<V> resolutionMechanism,
            int policyFlags,
            QuadFunction<V, Context, Integer, Bundle, Boolean> policyEnforcerCallback,
            PolicySerializer<V> policySerializer) {
        this(key, key, resolutionMechanism, policyFlags, policyEnforcerCallback,
                policySerializer, /* callbackArs= */ null);
    }

    /**
     * Callers must ensure that {@code policyType} have implemented an appropriate
     * {@link Object#equals} implementation.
     */
    private PolicyDefinition(
            String policyKey,
            String policyDefinitionKey,
            ResolutionMechanism<V> resolutionMechanism,
            int policyFlags,
            QuadFunction<V, Context, Integer, Bundle, Boolean> policyEnforcerCallback,
            PolicySerializer<V> policySerializer,
            Bundle callbackArgs) {
        mPolicyKey = policyKey;
        mPolicyDefinitionKey = policyDefinitionKey;
        mResolutionMechanism = resolutionMechanism;
        mPolicyFlags = policyFlags;
        mPolicyEnforcerCallback = policyEnforcerCallback;
        mPolicySerializer = policySerializer;
        mCallbackArgs = callbackArgs;

        // TODO: maybe use this instead of manually adding to the map
//        sPolicyDefinitions.put(policyDefinitionKey, this);
    }

    void saveToXml(TypedXmlSerializer serializer) throws IOException {
        serializer.attribute(/* namespace= */ null, ATTR_POLICY_KEY, mPolicyKey);
        serializer.attribute(
                /* namespace= */ null, ATTR_POLICY_DEFINITION_KEY, mPolicyDefinitionKey);
        serializer.attributeInt(
                /* namespace= */ null, ATTR_CALLBACK_ARGS_SIZE,
                mCallbackArgs == null ? 0 : mCallbackArgs.size());
        if (mCallbackArgs != null) {
            int i = 0;
            for (String key : mCallbackArgs.keySet()) {
                serializer.attribute(/* namespace= */ null,
                        ATTR_CALLBACK_ARGS_KEY + i, key);
                serializer.attribute(/* namespace= */ null,
                        ATTR_CALLBACK_ARGS_VALUE + i, mCallbackArgs.getString(key));
                i++;
            }
        }
    }

    static <V> PolicyDefinition<V> readFromXml(TypedXmlPullParser parser)
            throws XmlPullParserException, IOException {
        String policyKey = parser.getAttributeValue(/* namespace= */ null, ATTR_POLICY_KEY);
        String policyDefinitionKey = parser.getAttributeValue(
                /* namespace= */ null, ATTR_POLICY_DEFINITION_KEY);
        int size = parser.getAttributeInt(/* namespace= */ null, ATTR_CALLBACK_ARGS_SIZE);
        Bundle callbackArgs = new Bundle();

        for (int i = 0; i < size; i++) {
            String key = parser.getAttributeValue(
                    /* namespace= */ null, ATTR_CALLBACK_ARGS_KEY + i);
            String value = parser.getAttributeValue(
                    /* namespace= */ null, ATTR_CALLBACK_ARGS_VALUE + i);
            callbackArgs.putString(key, value);
        }

        // TODO: can we avoid casting?
        if (callbackArgs.isEmpty()) {
            return (PolicyDefinition<V>) sPolicyDefinitions.get(policyDefinitionKey);
        } else {
            return (PolicyDefinition<V>) sPolicyDefinitions.get(policyDefinitionKey).setArgs(
                    policyKey, callbackArgs);
        }
    }

    void savePolicyValueToXml(TypedXmlSerializer serializer, String attributeName, V value)
            throws IOException {
        mPolicySerializer.saveToXml(serializer, attributeName, value);
    }

    @Nullable
    V readPolicyValueFromXml(TypedXmlPullParser parser, String attributeName) {
        return mPolicySerializer.readFromXml(parser, attributeName);
    }

    @Override
    public String toString() {
        return "PolicyDefinition { mPolicyKey= " + mPolicyKey + ", mPolicyDefinitionKey= "
                + mPolicyDefinitionKey + ", mResolutionMechanism= " + mResolutionMechanism
                + ", mCallbackArgs= " + mCallbackArgs + ", mPolicyFlags= " + mPolicyFlags + " }";
    }
}
