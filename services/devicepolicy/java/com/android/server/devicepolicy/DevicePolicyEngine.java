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
import java.util.Map;
import java.util.Objects;

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
            }
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

        if (!mUserPolicies.contains(userId)) {
            mUserPolicies.put(userId, new HashMap<>());
        }
        if (!mUserPolicies.get(userId).containsKey(policyDefinition.getPolicyKey())) {
            mUserPolicies.get(userId).put(
                    policyDefinition.getPolicyKey(), new PolicyState<>(policyDefinition));
        }
        return getPolicyState(mUserPolicies.get(userId), policyDefinition);
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
        // TODO: send broadcast or call callback to notify admins of policy change
        // TODO: notify calling admin of result (e.g. success, runtime failure, policy set by
        //  a different admin)
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
            mUserPolicies.clear();
        }
    }

    private class DevicePoliciesReaderWriter {
        private static final String DEVICE_POLICIES_XML = "device_policies.xml";
        private static final String TAG_USER_POLICY_ENTRY = "user-policy-entry";
        private static final String TAG_DEVICE_POLICY_ENTRY = "device-policy-entry";
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
            writeUserPoliciesInner(serializer);
            writeDevicePoliciesInner(serializer);
        }

        private void writeUserPoliciesInner(TypedXmlSerializer serializer) throws IOException {
            if (mUserPolicies != null) {
                for (int i = 0; i < mUserPolicies.size(); i++) {
                    int userId = mUserPolicies.keyAt(i);
                    for (Map.Entry<String, PolicyState<?>> policy : mUserPolicies.get(
                            userId).entrySet()) {
                        serializer.startTag(/* namespace= */ null, TAG_USER_POLICY_ENTRY);

                        serializer.attributeInt(/* namespace= */ null, ATTR_USER_ID, userId);
                        serializer.attribute(
                                /* namespace= */ null, ATTR_POLICY_ID, policy.getKey());

                        serializer.startTag(/* namespace= */ null, TAG_ADMINS_POLICY_ENTRY);
                        policy.getValue().saveToXml(serializer);
                        serializer.endTag(/* namespace= */ null, TAG_ADMINS_POLICY_ENTRY);

                        serializer.endTag(/* namespace= */ null, TAG_USER_POLICY_ENTRY);
                    }
                }
            }
        }

        private void writeDevicePoliciesInner(TypedXmlSerializer serializer) throws IOException {
            if (mGlobalPolicies != null) {
                for (Map.Entry<String, PolicyState<?>> policy : mGlobalPolicies.entrySet()) {
                    serializer.startTag(/* namespace= */ null, TAG_DEVICE_POLICY_ENTRY);

                    serializer.attribute(/* namespace= */ null, ATTR_POLICY_ID, policy.getKey());

                    serializer.startTag(/* namespace= */ null, TAG_ADMINS_POLICY_ENTRY);
                    policy.getValue().saveToXml(serializer);
                    serializer.endTag(/* namespace= */ null, TAG_ADMINS_POLICY_ENTRY);

                    serializer.endTag(/* namespace= */ null, TAG_DEVICE_POLICY_ENTRY);
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
                    case TAG_USER_POLICY_ENTRY:
                        readUserPoliciesInner(parser);
                        break;
                    case TAG_DEVICE_POLICY_ENTRY:
                        readDevicePoliciesInner(parser);
                        break;
                    default:
                        Log.e(TAG, "Unknown tag " + tag);
                }
            }
        }

        private void readUserPoliciesInner(TypedXmlPullParser parser)
                throws XmlPullParserException, IOException {
            int userId = parser.getAttributeInt(/* namespace= */ null, ATTR_USER_ID);
            String policyKey = parser.getAttributeValue(
                    /* namespace= */ null, ATTR_POLICY_ID);
            if (!mUserPolicies.contains(userId)) {
                mUserPolicies.put(userId, new HashMap<>());
            }
            PolicyState<?> adminsPolicy = parseAdminsPolicy(parser);
            if (adminsPolicy != null) {
                mUserPolicies.get(userId).put(policyKey, adminsPolicy);
            } else {
                Log.e(TAG, "Error parsing file, " + policyKey + "doesn't have an "
                        + "AdminsPolicy.");
            }
        }

        private void readDevicePoliciesInner(TypedXmlPullParser parser)
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
