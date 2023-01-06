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
import static android.app.admin.PolicyUpdatesReceiver.EXTRA_POLICY_BUNDLE_KEY;
import static android.app.admin.PolicyUpdatesReceiver.EXTRA_POLICY_KEY;
import static android.app.admin.PolicyUpdatesReceiver.EXTRA_POLICY_SET_RESULT_KEY;
import static android.app.admin.PolicyUpdatesReceiver.EXTRA_POLICY_TARGET_USER_ID;
import static android.app.admin.PolicyUpdatesReceiver.EXTRA_POLICY_UPDATE_REASON_KEY;
import static android.app.admin.PolicyUpdatesReceiver.POLICY_SET_RESULT_FAILURE;
import static android.app.admin.PolicyUpdatesReceiver.POLICY_SET_RESULT_SUCCESS;

import android.Manifest;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.admin.PolicyUpdatesReceiver;
import android.app.admin.TargetUser;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Bundle;
import android.os.Environment;
import android.os.UserHandle;
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
import java.util.HashMap;
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
    // TODO(b/256849338): add more granular locks
    private final Object mLock = new Object();

    /**
     * Map of <userId, Map<policyKey, policyState>>
     */
    private final SparseArray<Map<String, PolicyState<?>>> mLocalPolicies;

    /**
     * Map of <policyKey, policyState>
     */
    private final Map<String, PolicyState<?>> mGlobalPolicies;

    DevicePolicyEngine(@NonNull Context context) {
        mContext = Objects.requireNonNull(context);
        mLocalPolicies = new SparseArray<>();
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
                enforcePolicy(
                        policyDefinition, policyState.getCurrentResolvedPolicy(), userId);
                sendPolicyChangedToAdmins(
                        policyState.getPoliciesSetByAdmins().keySet(),
                        enforcingAdmin,
                        policyDefinition,
                        userId == enforcingAdmin.getUserId()
                                ? TargetUser.LOCAL_USER_ID : TargetUser.PARENT_USER_ID);

            }
            boolean wasAdminPolicyEnforced = Objects.equals(
                    policyState.getCurrentResolvedPolicy(), value);
            sendPolicyResultToAdmin(
                    enforcingAdmin,
                    policyDefinition,
                    wasAdminPolicyEnforced,
                    // TODO: we're always sending this for now, should properly handle errors.
                    REASON_CONFLICTING_ADMIN_POLICY,
                    userId == enforcingAdmin.getUserId()
                            ? TargetUser.LOCAL_USER_ID : TargetUser.PARENT_USER_ID);

            write();
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
                sendPolicyChangedToAdmins(
                        policyState.getPoliciesSetByAdmins().keySet(),
                        enforcingAdmin,
                        policyDefinition,
                        TargetUser.GLOBAL_USER_ID);
            }
            boolean wasAdminPolicyEnforced = Objects.equals(
                    policyState.getCurrentResolvedPolicy(), value);
            sendPolicyResultToAdmin(
                    enforcingAdmin,
                    policyDefinition,
                    wasAdminPolicyEnforced,
                    // TODO: we're always sending this for now, should properly handle errors.
                    REASON_CONFLICTING_ADMIN_POLICY,
                    TargetUser.GLOBAL_USER_ID);

            write();
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
                enforcePolicy(
                        policyDefinition, policyState.getCurrentResolvedPolicy(), userId);
                sendPolicyChangedToAdmins(
                        policyState.getPoliciesSetByAdmins().keySet(),
                        enforcingAdmin,
                        policyDefinition,
                        userId == enforcingAdmin.getUserId()
                                ? TargetUser.LOCAL_USER_ID : TargetUser.PARENT_USER_ID);
            }
            // for a remove policy to be enforced, it means no current policy exists
            boolean wasAdminPolicyEnforced = policyState.getCurrentResolvedPolicy() == null;
            sendPolicyResultToAdmin(
                    enforcingAdmin,
                    policyDefinition,
                    wasAdminPolicyEnforced,
                    // TODO: we're always sending this for now, should properly handle errors.
                    REASON_CONFLICTING_ADMIN_POLICY,
                    userId == enforcingAdmin.getUserId()
                            ? TargetUser.LOCAL_USER_ID : TargetUser.PARENT_USER_ID);

            write();
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

                sendPolicyChangedToAdmins(
                        policyState.getPoliciesSetByAdmins().keySet(),
                        enforcingAdmin,
                        policyDefinition,
                        TargetUser.GLOBAL_USER_ID);
            }
            // for a remove policy to be enforced, it means no current policy exists
            boolean wasAdminPolicyEnforced = policyState.getCurrentResolvedPolicy() == null;
            sendPolicyResultToAdmin(
                    enforcingAdmin,
                    policyDefinition,
                    wasAdminPolicyEnforced,
                    // TODO: we're always sending this for now, should properly handle errors.
                    REASON_CONFLICTING_ADMIN_POLICY,
                    TargetUser.GLOBAL_USER_ID);

            write();
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
                    + policyDefinition.getPolicyKey() + " locally.");
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

    @NonNull
    private <V> PolicyState<V> getGlobalPolicyStateLocked(PolicyDefinition<V> policyDefinition) {
        if (policyDefinition.isLocalOnlyPolicy()) {
            throw new IllegalArgumentException("Can't set local policy "
                    + policyDefinition.getPolicyKey() + " globally.");
        }

        if (!mGlobalPolicies.containsKey(policyDefinition.getPolicyKey())) {
            mGlobalPolicies.put(
                    policyDefinition.getPolicyKey(), new PolicyState<>(policyDefinition));
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
                    policyDefinition.getPolicyKey());
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
    }

    private <V> void sendPolicyResultToAdmin(
            EnforcingAdmin admin, PolicyDefinition<V> policyDefinition, boolean success,
            int reason, int targetUserId) {
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
        extras.putString(EXTRA_POLICY_KEY, policyDefinition.getPolicyDefinitionKey());
        extras.putInt(EXTRA_POLICY_TARGET_USER_ID, targetUserId);

        if (policyDefinition.getCallbackArgs() != null
                && !policyDefinition.getCallbackArgs().isEmpty()) {
            extras.putBundle(EXTRA_POLICY_BUNDLE_KEY, policyDefinition.getCallbackArgs());
        }
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
            Set<EnforcingAdmin> admins, EnforcingAdmin callingAdmin,
            PolicyDefinition<V> policyDefinition,
            int targetUserId) {
        for (EnforcingAdmin admin: admins) {
            // We're sending a separate broadcast for the calling admin with the result.
            if (admin.equals(callingAdmin)) {
                continue;
            }
            maybeSendOnPolicyChanged(
                    admin, policyDefinition, REASON_CONFLICTING_ADMIN_POLICY, targetUserId);
        }
    }

    private <V> void maybeSendOnPolicyChanged(
            EnforcingAdmin admin, PolicyDefinition<V> policyDefinition, int reason,
            int targetUserId) {
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
        extras.putString(EXTRA_POLICY_KEY, policyDefinition.getPolicyDefinitionKey());
        extras.putInt(EXTRA_POLICY_TARGET_USER_ID, targetUserId);

        if (policyDefinition.getCallbackArgs() != null
                && !policyDefinition.getCallbackArgs().isEmpty()) {
            extras.putBundle(EXTRA_POLICY_BUNDLE_KEY, policyDefinition.getCallbackArgs());
        }
        extras.putInt(EXTRA_POLICY_UPDATE_REASON_KEY, reason);
        intent.putExtras(extras);
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
        }

        private void writeLocalPoliciesInner(TypedXmlSerializer serializer) throws IOException {
            if (mLocalPolicies != null) {
                for (int i = 0; i < mLocalPolicies.size(); i++) {
                    int userId = mLocalPolicies.keyAt(i);
                    for (Map.Entry<String, PolicyState<?>> policy : mLocalPolicies.get(
                            userId).entrySet()) {
                        serializer.startTag(/* namespace= */ null, TAG_LOCAL_POLICY_ENTRY);

                        serializer.attributeInt(/* namespace= */ null, ATTR_USER_ID, userId);
                        serializer.attribute(
                                /* namespace= */ null, ATTR_POLICY_ID, policy.getKey());

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
                for (Map.Entry<String, PolicyState<?>> policy : mGlobalPolicies.entrySet()) {
                    serializer.startTag(/* namespace= */ null, TAG_GLOBAL_POLICY_ENTRY);

                    serializer.attribute(/* namespace= */ null, ATTR_POLICY_ID, policy.getKey());

                    serializer.startTag(/* namespace= */ null, TAG_ADMINS_POLICY_ENTRY);
                    policy.getValue().saveToXml(serializer);
                    serializer.endTag(/* namespace= */ null, TAG_ADMINS_POLICY_ENTRY);

                    serializer.endTag(/* namespace= */ null, TAG_GLOBAL_POLICY_ENTRY);
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
                    default:
                        Log.e(TAG, "Unknown tag " + tag);
                }
            }
        }

        private void readLocalPoliciesInner(TypedXmlPullParser parser)
                throws XmlPullParserException, IOException {
            int userId = parser.getAttributeInt(/* namespace= */ null, ATTR_USER_ID);
            String policyKey = parser.getAttributeValue(
                    /* namespace= */ null, ATTR_POLICY_ID);
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
            String policyKey = parser.getAttributeValue(/* namespace= */ null, ATTR_POLICY_ID);
            PolicyState<?> adminsPolicy = parseAdminsPolicy(parser);
            if (adminsPolicy != null) {
                mGlobalPolicies.put(policyKey, adminsPolicy);
            } else {
                Log.e(TAG, "Error parsing file, " + policyKey + "doesn't have an "
                        + "AdminsPolicy.");
            }
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
