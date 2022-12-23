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

import static android.app.admin.PolicyUpdateReason.REASON_CONFLICTING_ADMIN_POLICY;
import static android.app.admin.PolicyUpdatesReceiver.EXTRA_POLICY_SET_RESULT_KEY;
import static android.app.admin.PolicyUpdatesReceiver.EXTRA_POLICY_TARGET_USER_ID;
import static android.app.admin.PolicyUpdatesReceiver.EXTRA_POLICY_UPDATE_REASON_KEY;
import static android.app.admin.PolicyUpdatesReceiver.POLICY_SET_RESULT_FAILURE;
import static android.app.admin.PolicyUpdatesReceiver.POLICY_SET_RESULT_SUCCESS;

import android.Manifest;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.admin.DevicePolicyManager;
import android.app.admin.PolicyUpdatesReceiver;
import android.app.admin.TargetUser;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.UserInfo;
import android.os.Binder;
import android.os.Bundle;
import android.os.Environment;
import android.os.UserHandle;
import android.os.UserManager;
import android.util.AtomicFile;
import android.util.Log;
import android.util.SparseArray;
import android.util.Xml;

import com.android.internal.util.XmlUtils;
import com.android.modules.utils.TypedXmlPullParser;
import com.android.modules.utils.TypedXmlSerializer;

import libcore.io.IoUtils;

import org.xmlpull.v1.XmlPullParserException;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Class responsible for setting, resolving, and enforcing policies set by multiple management
 * admins on the device.
 */
final class DevicePolicyEngine {
    static final String TAG = "DevicePolicyEngine";

    private final Context mContext;
    private final UserManager mUserManager;

    // TODO(b/256849338): add more granular locks
    private final Object mLock = new Object();

    /**
     * Map of <userId, Map<policyKey, policyState>>
     */
    private final SparseArray<Map<PolicyKey, PolicyState<?>>> mLocalPolicies;

    /**
     * Map of <policyKey, policyState>
     */
    private final Map<PolicyKey, PolicyState<?>> mGlobalPolicies;

    /**
     * Map containing the current set of admins in each user with active policies.
     */
    private final SparseArray<Set<EnforcingAdmin>> mEnforcingAdmins;

    private final DeviceAdminServiceController mDeviceAdminServiceController;

    DevicePolicyEngine(
            @NonNull Context context,
            @NonNull DeviceAdminServiceController deviceAdminServiceController) {
        mContext = Objects.requireNonNull(context);
        mDeviceAdminServiceController = Objects.requireNonNull(deviceAdminServiceController);
        mUserManager = mContext.getSystemService(UserManager.class);
        mLocalPolicies = new SparseArray<>();
        mGlobalPolicies = new HashMap<>();
        mEnforcingAdmins = new SparseArray<>();
    }

    // TODO: add more documentation on broadcasts/callbacks to use to get current enforced values
    /**
     * Set the policy for the provided {@code policyDefinition}
     * (see {@link PolicyDefinition}) and {@code enforcingAdmin} to the provided {@code value}.
     */
    <V> void setLocalPolicy(
            @NonNull PolicyDefinition<V> policyDefinition,
            @NonNull EnforcingAdmin enforcingAdmin,
            @NonNull V value,
            int userId) {

        Objects.requireNonNull(policyDefinition);
        Objects.requireNonNull(enforcingAdmin);
        Objects.requireNonNull(value);

        synchronized (mLock) {
            PolicyState<V> localPolicyState = getLocalPolicyStateLocked(policyDefinition, userId);

            boolean hasGlobalPolicies = hasGlobalPolicyLocked(policyDefinition);
            boolean policyChanged;
            if (hasGlobalPolicies) {
                PolicyState<V> globalPolicyState = getGlobalPolicyStateLocked(policyDefinition);
                policyChanged = localPolicyState.addPolicy(
                        enforcingAdmin,
                        value,
                        globalPolicyState.getPoliciesSetByAdmins());
            } else {
                policyChanged = localPolicyState.addPolicy(enforcingAdmin, value);
            }

            if (policyChanged) {
                onLocalPolicyChanged(policyDefinition, enforcingAdmin, userId);
            }

            boolean policyEnforced = Objects.equals(
                    localPolicyState.getCurrentResolvedPolicy(), value);
            sendPolicyResultToAdmin(
                    enforcingAdmin,
                    policyDefinition,
                    policyEnforced,
                    // TODO: we're always sending this for now, should properly handle errors.
                    REASON_CONFLICTING_ADMIN_POLICY,
                    userId);

            updateDeviceAdminServiceOnPolicyAddLocked(enforcingAdmin);

            write();
        }
    }

    // TODO: add more documentation on broadcasts/callbacks to use to get current enforced values
    /**
     * Removes any previously set policy for the provided {@code policyDefinition}
     * (see {@link PolicyDefinition}) and {@code enforcingAdmin}.
     */
    <V> void removeLocalPolicy(
            @NonNull PolicyDefinition<V> policyDefinition,
            @NonNull EnforcingAdmin enforcingAdmin,
            int userId) {
        Objects.requireNonNull(policyDefinition);
        Objects.requireNonNull(enforcingAdmin);

        synchronized (mLock) {
            if (!hasLocalPolicyLocked(policyDefinition, userId)) {
                return;
            }
            PolicyState<V> localPolicyState = getLocalPolicyStateLocked(policyDefinition, userId);

            boolean policyChanged;
            if (hasGlobalPolicyLocked(policyDefinition)) {
                PolicyState<V> globalPolicyState = getGlobalPolicyStateLocked(policyDefinition);
                policyChanged = localPolicyState.removePolicy(
                        enforcingAdmin,
                        globalPolicyState.getPoliciesSetByAdmins());
            } else {
                policyChanged = localPolicyState.removePolicy(enforcingAdmin);
            }

            if (policyChanged) {
                onLocalPolicyChanged(policyDefinition, enforcingAdmin, userId);
            }

            // For a removePolicy to be enforced, it means no current policy exists
            boolean policyEnforced = localPolicyState.getCurrentResolvedPolicy() == null;
            sendPolicyResultToAdmin(
                    enforcingAdmin,
                    policyDefinition,
                    policyEnforced,
                    // TODO: we're always sending this for now, should properly handle errors.
                    REASON_CONFLICTING_ADMIN_POLICY,
                    userId);

            if (localPolicyState.getPoliciesSetByAdmins().isEmpty()) {
                removeLocalPolicyStateLocked(policyDefinition, userId);
            }

            updateDeviceAdminServiceOnPolicyRemoveLocked(enforcingAdmin);

            write();
        }
    }

    /**
     * Enforces the new policy and notifies relevant admins.
     */
    private <V> void onLocalPolicyChanged(
            @NonNull PolicyDefinition<V> policyDefinition,
            @NonNull EnforcingAdmin enforcingAdmin,
            int userId) {

        PolicyState<V> localPolicyState = getLocalPolicyStateLocked(policyDefinition, userId);
        enforcePolicy(
                policyDefinition, localPolicyState.getCurrentResolvedPolicy(), userId);

        // Send policy updates to admins who've set it locally
        sendPolicyChangedToAdmins(
                localPolicyState.getPoliciesSetByAdmins().keySet(),
                enforcingAdmin,
                policyDefinition,
                // This policy change is only relevant to a single user, not the global
                // policy value,
                userId);

        // Send policy updates to admins who've set it globally
        if (hasGlobalPolicyLocked(policyDefinition)) {
            PolicyState<V> globalPolicyState = getGlobalPolicyStateLocked(policyDefinition);
            sendPolicyChangedToAdmins(
                    globalPolicyState.getPoliciesSetByAdmins().keySet(),
                    enforcingAdmin,
                    policyDefinition,
                    userId);
        }
    }
    // TODO: add more documentation on broadcasts/callbacks to use to get current enforced values
    /**
     * Set the policy for the provided {@code policyDefinition}
     * (see {@link PolicyDefinition}) and {@code enforcingAdmin} to the provided {@code value}.
     */
    <V> void setGlobalPolicy(
            @NonNull PolicyDefinition<V> policyDefinition,
            @NonNull EnforcingAdmin enforcingAdmin,
            @NonNull V value) {

        Objects.requireNonNull(policyDefinition);
        Objects.requireNonNull(enforcingAdmin);
        Objects.requireNonNull(value);

        synchronized (mLock) {
            PolicyState<V> globalPolicyState = getGlobalPolicyStateLocked(policyDefinition);

            boolean policyChanged = globalPolicyState.addPolicy(enforcingAdmin, value);
            if (policyChanged) {
                onGlobalPolicyChanged(policyDefinition, enforcingAdmin);
            }

            boolean policyEnforcedOnAllUsers = enforceGlobalPolicyOnUsersWithLocalPoliciesLocked(
                    policyDefinition, enforcingAdmin, value);
            boolean policyEnforcedGlobally = Objects.equals(
                    globalPolicyState.getCurrentResolvedPolicy(), value);

            sendPolicyResultToAdmin(
                    enforcingAdmin,
                    policyDefinition,
                    policyEnforcedGlobally && policyEnforcedOnAllUsers,
                    // TODO: we're always sending this for now, should properly handle errors.
                    REASON_CONFLICTING_ADMIN_POLICY,
                    UserHandle.USER_ALL);

            updateDeviceAdminServiceOnPolicyAddLocked(enforcingAdmin);

            write();
        }
    }

    // TODO: add more documentation on broadcasts/callbacks to use to get current enforced values
    /**
     * Removes any previously set policy for the provided {@code policyDefinition}
     * (see {@link PolicyDefinition}) and {@code enforcingAdmin}.
     */
    <V> void removeGlobalPolicy(
            @NonNull PolicyDefinition<V> policyDefinition,
            @NonNull EnforcingAdmin enforcingAdmin) {

        Objects.requireNonNull(policyDefinition);
        Objects.requireNonNull(enforcingAdmin);

        synchronized (mLock) {
            PolicyState<V> policyState = getGlobalPolicyStateLocked(policyDefinition);
            boolean policyChanged = policyState.removePolicy(enforcingAdmin);

            if (policyChanged) {
                onGlobalPolicyChanged(policyDefinition, enforcingAdmin);
            }

            boolean policyEnforcedOnAllUsers = enforceGlobalPolicyOnUsersWithLocalPoliciesLocked(
                    policyDefinition, enforcingAdmin, /* value= */ null);
            // For a removePolicy to be enforced, it means no current policy exists
            boolean policyEnforcedGlobally = policyState.getCurrentResolvedPolicy() == null;

            sendPolicyResultToAdmin(
                    enforcingAdmin,
                    policyDefinition,
                    policyEnforcedGlobally && policyEnforcedOnAllUsers,
                    // TODO: we're always sending this for now, should properly handle errors.
                    REASON_CONFLICTING_ADMIN_POLICY,
                    UserHandle.USER_ALL);

            if (policyState.getPoliciesSetByAdmins().isEmpty()) {
                removeGlobalPolicyStateLocked(policyDefinition);
            }

            updateDeviceAdminServiceOnPolicyRemoveLocked(enforcingAdmin);

            write();
        }
    }

    /**
     * Enforces the new policy globally and notifies relevant admins.
     */
    private <V> void onGlobalPolicyChanged(
            @NonNull PolicyDefinition<V> policyDefinition,
            @NonNull EnforcingAdmin enforcingAdmin) {
        PolicyState<V> policyState = getGlobalPolicyStateLocked(policyDefinition);

        enforcePolicy(policyDefinition, policyState.getCurrentResolvedPolicy(),
                UserHandle.USER_ALL);

        sendPolicyChangedToAdmins(
                policyState.getPoliciesSetByAdmins().keySet(),
                enforcingAdmin,
                policyDefinition,
                UserHandle.USER_ALL);
    }

    /**
     * Tries to enforce the global policy locally on all users that have the same policy set
     * locally, this is only applicable to policies that can be set locally or globally
     * (e.g. setCameraDisabled, setScreenCaptureDisabled) rather than
     * policies that are global by nature (e.g. setting Wifi enabled/disabled).
     *
     * <p> A {@code null} policy value means the policy was removed
     *
     * <p>Returns {@code true} if the policy is enforced successfully on all users.
     */
    private <V> boolean enforceGlobalPolicyOnUsersWithLocalPoliciesLocked(
            @NonNull PolicyDefinition<V> policyDefinition,
            @NonNull EnforcingAdmin enforcingAdmin,
            @Nullable V value) {
        // Global only policies can't be applied locally, return early.
        if (policyDefinition.isGlobalOnlyPolicy()) {
            return true;
        }
        boolean isAdminPolicyEnforced = true;
        for (int i = 0; i < mLocalPolicies.size(); i++) {
            int userId = mLocalPolicies.keyAt(i);
            if (!hasLocalPolicyLocked(policyDefinition, userId)) {
                continue;
            }

            PolicyState<V> localPolicyState = getLocalPolicyStateLocked(policyDefinition, userId);
            PolicyState<V> globalPolicyState = getGlobalPolicyStateLocked(policyDefinition);

            boolean policyChanged = localPolicyState.resolvePolicy(
                    globalPolicyState.getPoliciesSetByAdmins());
            if (policyChanged) {
                enforcePolicy(
                        policyDefinition, localPolicyState.getCurrentResolvedPolicy(), userId);
                sendPolicyChangedToAdmins(
                        localPolicyState.getPoliciesSetByAdmins().keySet(),
                        enforcingAdmin,
                        policyDefinition,
                        // Even though this is caused by a global policy change, admins who've set
                        // it locally should only care about the local user state.
                        userId);

            }
            isAdminPolicyEnforced &= Objects.equals(
                    value, localPolicyState.getCurrentResolvedPolicy());
        }
        return isAdminPolicyEnforced;
    }

    /**
     * Retrieves the resolved policy for the provided {@code policyDefinition} and {@code userId}.
     */
    @Nullable
    <V> V getResolvedPolicy(@NonNull PolicyDefinition<V> policyDefinition, int userId) {
        Objects.requireNonNull(policyDefinition);

        synchronized (mLock) {
            if (hasLocalPolicyLocked(policyDefinition, userId)) {
                return getLocalPolicyStateLocked(
                        policyDefinition, userId).getCurrentResolvedPolicy();
            }
            if (hasGlobalPolicyLocked(policyDefinition)) {
                return getGlobalPolicyStateLocked(policyDefinition).getCurrentResolvedPolicy();
            }
            return null;
        }
    }

    /**
     * Retrieves the policy set by the admin for the provided {@code policyDefinition} and
     * {@code userId} if one was set, otherwise returns {@code null}.
     */
    @Nullable
    <V> V getLocalPolicySetByAdmin(
            @NonNull PolicyDefinition<V> policyDefinition,
            @NonNull EnforcingAdmin enforcingAdmin,
            int userId) {
        Objects.requireNonNull(policyDefinition);
        Objects.requireNonNull(enforcingAdmin);

        synchronized (mLock) {
            if (!hasLocalPolicyLocked(policyDefinition, userId)) {
                return null;
            }
            return getLocalPolicyStateLocked(policyDefinition, userId)
                    .getPoliciesSetByAdmins().get(enforcingAdmin);
        }
    }

    /**
     * Returns the policies set by the given admin that share the same {@link PolicyKey#getKey()} as
     * the provided {@code policyDefinition}.
     *
     * <p>For example, getLocalPolicyKeysSetByAdmin(PERMISSION_GRANT, admin) returns all permission
     * grants set by the given admin.
     *
     * <p>Note that this will always return at most one item for policies that do not require
     * additional params (e.g. {@link PolicyDefinition#LOCK_TASK} vs
     * {@link PolicyDefinition#PERMISSION_GRANT(String, String)}).
     *
     */
    @NonNull
    <V> Set<PolicyKey> getLocalPolicyKeysSetByAdmin(
            @NonNull PolicyDefinition<V> policyDefinition,
            @NonNull EnforcingAdmin enforcingAdmin,
            int userId) {
        Objects.requireNonNull(policyDefinition);
        Objects.requireNonNull(enforcingAdmin);

        synchronized (mLock) {
            if (policyDefinition.isGlobalOnlyPolicy() || !mLocalPolicies.contains(userId)) {
                return Set.of();
            }
            Set<PolicyKey> keys = new HashSet<>();
            for (PolicyKey key : mLocalPolicies.get(userId).keySet()) {
                if (key.hasSameKeyAs(policyDefinition.getPolicyKey())
                        && mLocalPolicies.get(userId).get(key).getPoliciesSetByAdmins()
                        .containsKey(enforcingAdmin)) {
                    keys.add(key);
                }
            }
            return keys;
        }
    }

    private <V> boolean hasLocalPolicyLocked(PolicyDefinition<V> policyDefinition, int userId) {
        if (policyDefinition.isGlobalOnlyPolicy()) {
            return false;
        }
        if (!mLocalPolicies.contains(userId)) {
            return false;
        }
        if (!mLocalPolicies.get(userId).containsKey(policyDefinition.getPolicyKey())) {
            return false;
        }
        return !mLocalPolicies.get(userId).get(policyDefinition.getPolicyKey())
                .getPoliciesSetByAdmins().isEmpty();
    }

    private <V> boolean hasGlobalPolicyLocked(PolicyDefinition<V> policyDefinition) {
        if (policyDefinition.isLocalOnlyPolicy()) {
            return false;
        }
        if (!mGlobalPolicies.containsKey(policyDefinition.getPolicyKey())) {
            return false;
        }
        return !mGlobalPolicies.get(policyDefinition.getPolicyKey()).getPoliciesSetByAdmins()
                .isEmpty();
    }

    @NonNull
    private <V> PolicyState<V> getLocalPolicyStateLocked(
            PolicyDefinition<V> policyDefinition, int userId) {

        if (policyDefinition.isGlobalOnlyPolicy()) {
            throw new IllegalArgumentException(policyDefinition.getPolicyKey() + " is a global only"
                    + "policy.");
        }

        if (!mLocalPolicies.contains(userId)) {
            mLocalPolicies.put(userId, new HashMap<>());
        }
        if (!mLocalPolicies.get(userId).containsKey(policyDefinition.getPolicyKey())) {
            mLocalPolicies.get(userId).put(
                    policyDefinition.getPolicyKey(), new PolicyState<>(policyDefinition));
        }
        return getPolicyState(mLocalPolicies.get(userId), policyDefinition);
    }

    private <V> void removeLocalPolicyStateLocked(
            PolicyDefinition<V> policyDefinition, int userId) {
        if (!mLocalPolicies.contains(userId)) {
            return;
        }
        mLocalPolicies.get(userId).remove(policyDefinition.getPolicyKey());
    }

    @NonNull
    private <V> PolicyState<V> getGlobalPolicyStateLocked(PolicyDefinition<V> policyDefinition) {
        if (policyDefinition.isLocalOnlyPolicy()) {
            throw new IllegalArgumentException(policyDefinition.getPolicyKey() + " is a local only"
                    + "policy.");
        }

        if (!mGlobalPolicies.containsKey(policyDefinition.getPolicyKey())) {
            mGlobalPolicies.put(
                    policyDefinition.getPolicyKey(), new PolicyState<>(policyDefinition));
        }
        return getPolicyState(mGlobalPolicies, policyDefinition);
    }

    private <V> void removeGlobalPolicyStateLocked(PolicyDefinition<V> policyDefinition) {
        mGlobalPolicies.remove(policyDefinition.getPolicyKey());
    }

    private static <V> PolicyState<V> getPolicyState(
            Map<PolicyKey, PolicyState<?>> policies, PolicyDefinition<V> policyDefinition) {
        try {
            // This will not throw an exception because policyDefinition is of type V, so unless
            // we've created two policies with the same key but different types - we can only have
            // stored a PolicyState of the right type.
            PolicyState<V> policyState = (PolicyState<V>) policies.get(
                    policyDefinition.getPolicyKey());
            return policyState;
        } catch (ClassCastException exception) {
            // TODO: handle exception properly
            throw new IllegalArgumentException();
        }
    }

    private <V> void enforcePolicy(
            PolicyDefinition<V> policyDefinition, @Nullable V policyValue, int userId) {
        // null policyValue means remove any enforced policies, ensure callbacks handle this
        // properly
        policyDefinition.enforcePolicy(policyValue, mContext, userId);
    }

    private <V> void sendPolicyResultToAdmin(
            EnforcingAdmin admin, PolicyDefinition<V> policyDefinition, boolean success,
            int reason, int userId) {
        Intent intent = new Intent(PolicyUpdatesReceiver.ACTION_DEVICE_POLICY_SET_RESULT);
        intent.setPackage(admin.getPackageName());

        List<ResolveInfo> receivers = mContext.getPackageManager().queryBroadcastReceiversAsUser(
                intent,
                PackageManager.ResolveInfoFlags.of(PackageManager.GET_RECEIVERS),
                admin.getUserId());
        if (receivers.isEmpty()) {
            Log.i(TAG, "Couldn't find any receivers that handle ACTION_DEVICE_POLICY_SET_RESULT"
                    + "in package " + admin.getPackageName());
            return;
        }

        Bundle extras = new Bundle();
        policyDefinition.getPolicyKey().writeToBundle(extras);
        extras.putInt(
                EXTRA_POLICY_TARGET_USER_ID,
                getTargetUser(admin.getUserId(), userId));
        extras.putInt(
                EXTRA_POLICY_SET_RESULT_KEY,
                success ? POLICY_SET_RESULT_SUCCESS : POLICY_SET_RESULT_FAILURE);

        if (!success) {
            extras.putInt(EXTRA_POLICY_UPDATE_REASON_KEY, reason);
        }
        intent.putExtras(extras);

        maybeSendIntentToAdminReceivers(intent, UserHandle.of(admin.getUserId()), receivers);
    }

    // TODO(b/261430877): Finalise the decision on which admins to send the updates to.
    private <V> void sendPolicyChangedToAdmins(
            Set<EnforcingAdmin> admins,
            EnforcingAdmin callingAdmin,
            PolicyDefinition<V> policyDefinition,
            int userId) {
        for (EnforcingAdmin admin: admins) {
            // We're sending a separate broadcast for the calling admin with the result.
            if (admin.equals(callingAdmin)) {
                continue;
            }
            maybeSendOnPolicyChanged(
                    admin, policyDefinition, REASON_CONFLICTING_ADMIN_POLICY, userId);
        }
    }

    private <V> void maybeSendOnPolicyChanged(
            EnforcingAdmin admin, PolicyDefinition<V> policyDefinition, int reason,
            int userId) {
        Intent intent = new Intent(PolicyUpdatesReceiver.ACTION_DEVICE_POLICY_CHANGED);
        intent.setPackage(admin.getPackageName());

        List<ResolveInfo> receivers = mContext.getPackageManager().queryBroadcastReceiversAsUser(
                intent,
                PackageManager.ResolveInfoFlags.of(PackageManager.GET_RECEIVERS),
                admin.getUserId());
        if (receivers.isEmpty()) {
            Log.i(TAG, "Couldn't find any receivers that handle ACTION_DEVICE_POLICY_CHANGED"
                    + "in package " + admin.getPackageName());
            return;
        }

        Bundle extras = new Bundle();
        policyDefinition.getPolicyKey().writeToBundle(extras);
        extras.putInt(
                EXTRA_POLICY_TARGET_USER_ID,
                getTargetUser(admin.getUserId(), userId));
        extras.putInt(EXTRA_POLICY_UPDATE_REASON_KEY, reason);
        intent.putExtras(extras);
        intent.addFlags(Intent.FLAG_RECEIVER_FOREGROUND);

        maybeSendIntentToAdminReceivers(
                intent, UserHandle.of(admin.getUserId()), receivers);
    }

    private void maybeSendIntentToAdminReceivers(
            Intent intent, UserHandle userHandle, List<ResolveInfo> receivers) {
        for (ResolveInfo resolveInfo : receivers) {
            if (!Manifest.permission.BIND_DEVICE_ADMIN.equals(
                    resolveInfo.activityInfo.permission)) {
                Log.w(TAG, "Receiver " + resolveInfo.activityInfo + " is not protected by"
                        + "BIND_DEVICE_ADMIN permission!");
                continue;
            }
            // TODO: If admins are always bound to, do I still need to set
            //  "BroadcastOptions.setBackgroundActivityStartsAllowed"?
            // TODO: maybe protect it with a permission that is granted to the role so that we
            //  don't accidentally send a broadcast to an admin that no longer holds the role.
            mContext.sendBroadcastAsUser(intent, userHandle);
        }
    }

    private int getTargetUser(int adminUserId, int targetUserId) {
        if (targetUserId == UserHandle.USER_ALL) {
            return TargetUser.GLOBAL_USER_ID;
        }
        if (adminUserId == targetUserId) {
            return TargetUser.LOCAL_USER_ID;
        }
        if (getProfileParentId(adminUserId) == targetUserId) {
            return TargetUser.PARENT_USER_ID;
        }
        return TargetUser.UNKNOWN_USER_ID;
    }

    private int getProfileParentId(int userId) {
        return Binder.withCleanCallingIdentity(() -> {
            UserInfo parentUser = mUserManager.getProfileParent(userId);
            return parentUser != null ? parentUser.id : userId;
        });
    }

    /**
     * Starts/Stops the services that handle {@link DevicePolicyManager#ACTION_DEVICE_ADMIN_SERVICE}
     * in the enforcing admins for the given {@code userId}.
     */
    private void updateDeviceAdminsServicesForUser(
            int userId, boolean enable, @NonNull String actionForLog) {
        if (!enable) {
            mDeviceAdminServiceController.stopServicesForUser(
                    userId, actionForLog);
        } else {
            for (EnforcingAdmin admin : getEnforcingAdminsForUser(userId)) {
                // DPCs are handled separately in DPMS, no need to reestablish the connection here.
                if (admin.hasAuthority(EnforcingAdmin.DPC_AUTHORITY)) {
                    continue;
                }
                mDeviceAdminServiceController.startServiceForAdmin(
                        admin.getPackageName(), userId, actionForLog);
            }
        }
    }

    /**
     * Handles internal state related to a user getting started.
     */
    void handleStartUser(int userId) {
        updateDeviceAdminsServicesForUser(
                userId, /* enable= */ true, /* actionForLog= */ "start-user");
    }

    /**
     * Handles internal state related to a user getting started.
     */
    void handleUnlockUser(int userId) {
        updateDeviceAdminsServicesForUser(
                userId, /* enable= */ true, /* actionForLog= */ "unlock-user");
    }

    /**
     * Handles internal state related to a user getting stopped.
     */
    void handleStopUser(int userId) {
        updateDeviceAdminsServicesForUser(
                userId, /* enable= */ false, /* actionForLog= */ "stop-user");
    }

    /**
     * Handles internal state related to packages getting updated.
     */
    void handlePackageChanged(@Nullable String updatedPackage, int userId) {
        if (updatedPackage == null) {
            return;
        }
        updateDeviceAdminServiceOnPackageChanged(updatedPackage, userId);
    }

    /**
     * Reestablishes the service that handles
     * {@link DevicePolicyManager#ACTION_DEVICE_ADMIN_SERVICE} in the enforcing admin if the package
     * was updated, as a package update results in the persistent connection getting reset.
     */
    private void updateDeviceAdminServiceOnPackageChanged(
            @NonNull String updatedPackage, int userId) {
        for (EnforcingAdmin admin : getEnforcingAdminsForUser(userId)) {
            // DPCs are handled separately in DPMS, no need to reestablish the connection here.
            if (admin.hasAuthority(EnforcingAdmin.DPC_AUTHORITY)) {
                continue;
            }
            if (updatedPackage.equals(admin.getPackageName())) {
                mDeviceAdminServiceController.startServiceForAdmin(
                        updatedPackage, userId, /* actionForLog= */ "package-broadcast");
            }
        }
    }

    /**
     * Called after an admin policy has been added to start binding to the admin if a connection
     * was not already established.
     */
    private void updateDeviceAdminServiceOnPolicyAddLocked(@NonNull EnforcingAdmin enforcingAdmin) {
        int userId = enforcingAdmin.getUserId();

        // A connection is established with DPCs as soon as they are provisioned, so no need to
        // connect when a policy is set.
        if (enforcingAdmin.hasAuthority(EnforcingAdmin.DPC_AUTHORITY)) {
            return;
        }
        if (mEnforcingAdmins.contains(userId)
                && mEnforcingAdmins.get(userId).contains(enforcingAdmin)) {
            return;
        }

        if (!mEnforcingAdmins.contains(enforcingAdmin.getUserId())) {
            mEnforcingAdmins.put(enforcingAdmin.getUserId(), new HashSet<>());
        }
        mEnforcingAdmins.get(enforcingAdmin.getUserId()).add(enforcingAdmin);

        mDeviceAdminServiceController.startServiceForAdmin(
                enforcingAdmin.getPackageName(),
                userId,
                /* actionForLog= */ "policy-added");
    }

    /**
     * Called after an admin policy has been removed to stop binding to the admin if they no longer
     * have any policies set.
     */
    private void updateDeviceAdminServiceOnPolicyRemoveLocked(
            @NonNull EnforcingAdmin enforcingAdmin) {
        // TODO(b/263364434): centralise handling in one place.
        // DPCs rely on a constant connection being established as soon as they are provisioned,
        // so we shouldn't disconnect it even if they no longer have policies set.
        if (enforcingAdmin.hasAuthority(EnforcingAdmin.DPC_AUTHORITY)) {
            return;
        }
        if (doesAdminHavePolicies(enforcingAdmin)) {
            return;
        }

        int userId = enforcingAdmin.getUserId();

        if (mEnforcingAdmins.contains(userId)) {
            mEnforcingAdmins.get(userId).remove(enforcingAdmin);
            if (mEnforcingAdmins.get(userId).isEmpty()) {
                mEnforcingAdmins.remove(enforcingAdmin.getUserId());
            }
        }

        mDeviceAdminServiceController.stopServiceForAdmin(
                enforcingAdmin.getPackageName(),
                userId,
                /* actionForLog= */ "policy-removed");
    }

    private boolean doesAdminHavePolicies(@NonNull EnforcingAdmin enforcingAdmin) {
        for (PolicyKey policy : mGlobalPolicies.keySet()) {
            PolicyState<?> policyState = mGlobalPolicies.get(policy);
            if (policyState.getPoliciesSetByAdmins().containsKey(enforcingAdmin)) {
                return true;
            }
        }
        for (int i = 0; i < mLocalPolicies.size(); i++) {
            for (PolicyKey policy : mLocalPolicies.get(mLocalPolicies.keyAt(i)).keySet()) {
                PolicyState<?> policyState = mLocalPolicies.get(
                        mLocalPolicies.keyAt(i)).get(policy);
                if (policyState.getPoliciesSetByAdmins().containsKey(enforcingAdmin)) {
                    return true;
                }
            }
        }
        return false;
    }

    @NonNull
    private Set<EnforcingAdmin> getEnforcingAdminsForUser(int userId) {
        return mEnforcingAdmins.contains(userId)
                ? mEnforcingAdmins.get(userId) : Collections.emptySet();
    }

    private void write() {
        Log.d(TAG, "Writing device policies to file.");
        new DevicePoliciesReaderWriter().writeToFileLocked();
    }

    // TODO(b/256852787): trigger resolving logic after loading policies as roles are recalculated
    //  and could result in a different enforced policy
    void load() {
        Log.d(TAG, "Reading device policies from file.");
        synchronized (mLock) {
            clear();
            new DevicePoliciesReaderWriter().readFromFileLocked();
        }
    }

    private void clear() {
        synchronized (mLock) {
            mGlobalPolicies.clear();
            mLocalPolicies.clear();
        }
    }

    private class DevicePoliciesReaderWriter {
        private static final String DEVICE_POLICIES_XML = "device_policies.xml";
        private static final String TAG_LOCAL_POLICY_ENTRY = "local-policy-entry";
        private static final String TAG_GLOBAL_POLICY_ENTRY = "global-policy-entry";
        private static final String TAG_ADMINS_POLICY_ENTRY = "admins-policy-entry";
        private static final String TAG_ENFORCING_ADMINS_ENTRY = "enforcing-admins-entry";
        private static final String ATTR_USER_ID = "user-id";
        private static final String ATTR_POLICY_ID = "policy-id";

        private final File mFile;

        private DevicePoliciesReaderWriter() {
            mFile = new File(Environment.getDataSystemDirectory(), DEVICE_POLICIES_XML);
        }

        void writeToFileLocked() {
            Log.d(TAG, "Writing to " + mFile);

            AtomicFile f = new AtomicFile(mFile);
            FileOutputStream outputStream = null;
            try {
                outputStream = f.startWrite();
                TypedXmlSerializer out = Xml.resolveSerializer(outputStream);

                out.startDocument(null, true);

                // Actual content
                writeInner(out);

                out.endDocument();
                out.flush();

                // Commit the content.
                f.finishWrite(outputStream);
                outputStream = null;

            } catch (IOException e) {
                Log.e(TAG, "Exception when writing", e);
                if (outputStream != null) {
                    f.failWrite(outputStream);
                }
            }
        }

        // TODO(b/256846294): Add versioning to read/write
        void writeInner(TypedXmlSerializer serializer) throws IOException {
            writeLocalPoliciesInner(serializer);
            writeGlobalPoliciesInner(serializer);
            writeEnforcingAdminsInner(serializer);
        }

        private void writeLocalPoliciesInner(TypedXmlSerializer serializer) throws IOException {
            if (mLocalPolicies != null) {
                for (int i = 0; i < mLocalPolicies.size(); i++) {
                    int userId = mLocalPolicies.keyAt(i);
                    for (Map.Entry<PolicyKey, PolicyState<?>> policy : mLocalPolicies.get(
                            userId).entrySet()) {
                        serializer.startTag(/* namespace= */ null, TAG_LOCAL_POLICY_ENTRY);

                        serializer.attributeInt(/* namespace= */ null, ATTR_USER_ID, userId);
                        policy.getKey().saveToXml(serializer);

                        serializer.startTag(/* namespace= */ null, TAG_ADMINS_POLICY_ENTRY);
                        policy.getValue().saveToXml(serializer);
                        serializer.endTag(/* namespace= */ null, TAG_ADMINS_POLICY_ENTRY);

                        serializer.endTag(/* namespace= */ null, TAG_LOCAL_POLICY_ENTRY);
                    }
                }
            }
        }

        private void writeGlobalPoliciesInner(TypedXmlSerializer serializer) throws IOException {
            if (mGlobalPolicies != null) {
                for (Map.Entry<PolicyKey, PolicyState<?>> policy : mGlobalPolicies.entrySet()) {
                    serializer.startTag(/* namespace= */ null, TAG_GLOBAL_POLICY_ENTRY);

                    policy.getKey().saveToXml(serializer);

                    serializer.startTag(/* namespace= */ null, TAG_ADMINS_POLICY_ENTRY);
                    policy.getValue().saveToXml(serializer);
                    serializer.endTag(/* namespace= */ null, TAG_ADMINS_POLICY_ENTRY);

                    serializer.endTag(/* namespace= */ null, TAG_GLOBAL_POLICY_ENTRY);
                }
            }
        }

        private void writeEnforcingAdminsInner(TypedXmlSerializer serializer) throws IOException {
            if (mEnforcingAdmins != null) {
                for (int i = 0; i < mEnforcingAdmins.size(); i++) {
                    int userId = mEnforcingAdmins.keyAt(i);
                    for (EnforcingAdmin admin : mEnforcingAdmins.get(userId)) {
                        serializer.startTag(/* namespace= */ null, TAG_ENFORCING_ADMINS_ENTRY);
                        admin.saveToXml(serializer);
                        serializer.endTag(/* namespace= */ null, TAG_ENFORCING_ADMINS_ENTRY);
                    }
                }
            }
        }

        void readFromFileLocked() {
            if (!mFile.exists()) {
                Log.d(TAG, "" + mFile + " doesn't exist");
                return;
            }

            Log.d(TAG, "Reading from " + mFile);
            AtomicFile f = new AtomicFile(mFile);
            InputStream input = null;
            try {
                input = f.openRead();
                TypedXmlPullParser parser = Xml.resolvePullParser(input);

                readInner(parser);

            } catch (XmlPullParserException | IOException | ClassNotFoundException e) {
                Log.e(TAG, "Error parsing resources file", e);
            } finally {
                IoUtils.closeQuietly(input);
            }
        }

        private void readInner(TypedXmlPullParser parser)
                throws IOException, XmlPullParserException, ClassNotFoundException {
            int outerDepth = parser.getDepth();
            while (XmlUtils.nextElementWithin(parser, outerDepth)) {
                String tag = parser.getName();
                switch (tag) {
                    case TAG_LOCAL_POLICY_ENTRY:
                        readLocalPoliciesInner(parser);
                        break;
                    case TAG_GLOBAL_POLICY_ENTRY:
                        readGlobalPoliciesInner(parser);
                        break;
                    case TAG_ENFORCING_ADMINS_ENTRY:
                        readEnforcingAdminsInner(parser);
                        break;
                    default:
                        Log.e(TAG, "Unknown tag " + tag);
                }
            }
        }

        private void readLocalPoliciesInner(TypedXmlPullParser parser)
                throws XmlPullParserException, IOException {
            int userId = parser.getAttributeInt(/* namespace= */ null, ATTR_USER_ID);
            PolicyKey policyKey = PolicyDefinition.readPolicyKeyFromXml(parser);
            if (!mLocalPolicies.contains(userId)) {
                mLocalPolicies.put(userId, new HashMap<>());
            }
            PolicyState<?> adminsPolicy = parseAdminsPolicy(parser);
            if (adminsPolicy != null) {
                mLocalPolicies.get(userId).put(policyKey, adminsPolicy);
            } else {
                Log.e(TAG, "Error parsing file, " + policyKey + "doesn't have an "
                        + "AdminsPolicy.");
            }
        }

        private void readGlobalPoliciesInner(TypedXmlPullParser parser)
                throws IOException, XmlPullParserException {
            PolicyKey policyKey = PolicyDefinition.readPolicyKeyFromXml(parser);
            PolicyState<?> adminsPolicy = parseAdminsPolicy(parser);
            if (adminsPolicy != null) {
                mGlobalPolicies.put(policyKey, adminsPolicy);
            } else {
                Log.e(TAG, "Error parsing file, " + policyKey + "doesn't have an "
                        + "AdminsPolicy.");
            }
        }

        private void readEnforcingAdminsInner(TypedXmlPullParser parser)
                throws XmlPullParserException {
            EnforcingAdmin admin = EnforcingAdmin.readFromXml(parser);
            if (!mEnforcingAdmins.contains(admin.getUserId())) {
                mEnforcingAdmins.put(admin.getUserId(), new HashSet<>());
            }
            mEnforcingAdmins.get(admin.getUserId()).add(admin);
        }

        @Nullable
        private PolicyState<?> parseAdminsPolicy(TypedXmlPullParser parser)
                throws XmlPullParserException, IOException {
            int outerDepth = parser.getDepth();
            while (XmlUtils.nextElementWithin(parser, outerDepth)) {
                String tag = parser.getName();
                if (tag.equals(TAG_ADMINS_POLICY_ENTRY)) {
                    return PolicyState.readFromXml(parser);
                }
                Log.e(TAG, "Unknown tag " + tag);
            }
            Log.e(TAG, "Error parsing file, AdminsPolicy not found");
            return null;
        }
    }
}
