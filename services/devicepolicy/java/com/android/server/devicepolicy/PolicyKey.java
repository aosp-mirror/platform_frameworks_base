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

import static android.app.admin.PolicyUpdatesReceiver.EXTRA_POLICY_KEY;

import android.annotation.Nullable;
import android.os.Bundle;

import com.android.modules.utils.TypedXmlPullParser;
import com.android.modules.utils.TypedXmlSerializer;

import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.util.Objects;

/**
 * Abstract class used to identify a policy in the policy engine's data structure.
 */
abstract class PolicyKey {
    private static final String ATTR_GENERIC_POLICY_KEY = "generic-policy-key";

    protected final String mKey;

    PolicyKey(String policyKey) {
        mKey = Objects.requireNonNull(policyKey);
    }

    String getKey() {
        return mKey;
    }

    boolean hasSameKeyAs(PolicyKey other) {
        if (other == null) {
            return false;
        }
        return mKey.equals(other.mKey);
    }

    void saveToXml(TypedXmlSerializer serializer) throws IOException {
        serializer.attribute(/* namespace= */ null, ATTR_GENERIC_POLICY_KEY, mKey);
    }

    PolicyKey readFromXml(TypedXmlPullParser parser)
            throws XmlPullParserException, IOException {
        // No need to read anything
        return this;
    }

    void writeToBundle(Bundle bundle) {
        bundle.putString(EXTRA_POLICY_KEY, mKey);
    }

    @Override
    public boolean equals(@Nullable Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        PolicyKey other = (PolicyKey) o;
        return Objects.equals(mKey, other.mKey);
    }

    @Override
    public int hashCode() {
        return Objects.hash(mKey);
    }
}
