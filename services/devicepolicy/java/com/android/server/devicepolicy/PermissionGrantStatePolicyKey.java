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

import static android.app.admin.PolicyUpdatesReceiver.EXTRA_PACKAGE_NAME;
import static android.app.admin.PolicyUpdatesReceiver.EXTRA_PERMISSION_NAME;
import static android.app.admin.PolicyUpdatesReceiver.EXTRA_POLICY_BUNDLE_KEY;

import android.annotation.Nullable;
import android.os.Bundle;

import com.android.modules.utils.TypedXmlPullParser;
import com.android.modules.utils.TypedXmlSerializer;

import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.util.Objects;

/**
 * Class used to identify a PermissionGrantState policy in the policy engine's data structure.
 */
final class PermissionGrantStatePolicyKey extends PolicyKey {
    private static final String ATTR_POLICY_KEY = "policy-key";
    private static final String ATTR_PACKAGE_NAME = "package-name";
    private static final String ATTR_PERMISSION_NAME = "permission-name";

    private final String mPackageName;
    private final String mPermissionName;

    PermissionGrantStatePolicyKey(String key, String packageName, String permissionName) {
        super(key);
        mPackageName = Objects.requireNonNull((packageName));
        mPermissionName = Objects.requireNonNull((permissionName));
    }

    PermissionGrantStatePolicyKey(String key) {
        super(key);
        mPackageName = null;
        mPermissionName = null;
    }

    @Nullable
    String getPackageName() {
        return mPackageName;
    }

    @Nullable
    String getPermissionName() {
        return mPermissionName;
    }

    @Override
    void saveToXml(TypedXmlSerializer serializer) throws IOException {
        serializer.attribute(/* namespace= */ null, ATTR_POLICY_KEY, mKey);
        serializer.attribute(/* namespace= */ null, ATTR_PACKAGE_NAME, mPackageName);
        serializer.attribute(/* namespace= */ null, ATTR_PERMISSION_NAME, mPermissionName);
    }

    @Override
    PermissionGrantStatePolicyKey readFromXml(TypedXmlPullParser parser)
            throws XmlPullParserException, IOException {
        String policyKey = parser.getAttributeValue(/* namespace= */ null, ATTR_POLICY_KEY);
        String packageName = parser.getAttributeValue(/* namespace= */ null, ATTR_PACKAGE_NAME);
        String permissionName = parser.getAttributeValue(
                /* namespace= */ null, ATTR_PERMISSION_NAME);
        return new PermissionGrantStatePolicyKey(policyKey, packageName, permissionName);
    }

    @Override
    void writeToBundle(Bundle bundle) {
        super.writeToBundle(bundle);
        Bundle extraPolicyParams = new Bundle();
        extraPolicyParams.putString(EXTRA_PACKAGE_NAME, mPackageName);
        extraPolicyParams.putString(EXTRA_PERMISSION_NAME, mPermissionName);
        bundle.putBundle(EXTRA_POLICY_BUNDLE_KEY, extraPolicyParams);
    }

    @Override
    public boolean equals(@Nullable Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        PermissionGrantStatePolicyKey other = (PermissionGrantStatePolicyKey) o;
        return Objects.equals(mKey, other.mKey)
                && Objects.equals(mPackageName, other.mPackageName)
                && Objects.equals(mPermissionName, other.mPermissionName);
    }

    @Override
    public int hashCode() {
        return Objects.hash(mKey, mPackageName, mPermissionName);
    }

    @Override
    public String toString() {
        return "mPolicyKey= " + mKey + "; mPackageName= " + mPackageName + "; mPermissionName= "
                + mPermissionName;
    }
}
