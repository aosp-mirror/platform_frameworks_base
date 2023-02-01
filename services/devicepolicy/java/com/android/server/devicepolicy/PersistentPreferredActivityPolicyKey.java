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

import static android.app.admin.PolicyUpdatesReceiver.EXTRA_INTENT_FILTER;
import static android.app.admin.PolicyUpdatesReceiver.EXTRA_POLICY_BUNDLE_KEY;

import android.annotation.Nullable;
import android.content.IntentFilter;
import android.os.Bundle;

import com.android.modules.utils.TypedXmlPullParser;
import com.android.modules.utils.TypedXmlSerializer;
import com.android.server.IntentResolver;

import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.util.Objects;

/**
 * Class used to identify a PersistentPreferredActivity policy in the policy engine's data
 * structure.
 */
final class PersistentPreferredActivityPolicyKey extends PolicyKey {
    private static final String ATTR_POLICY_KEY = "policy-key";
    private IntentFilter mFilter;

    PersistentPreferredActivityPolicyKey(String policyKey, IntentFilter filter) {
        super(policyKey);
        mFilter = Objects.requireNonNull((filter));
    }

    PersistentPreferredActivityPolicyKey(String policyKey) {
        super(policyKey);
        mFilter = null;
    }

    @Nullable
    IntentFilter getFilter() {
        return mFilter;
    }

    @Override
    void saveToXml(TypedXmlSerializer serializer) throws IOException {
        serializer.attribute(/* namespace= */ null, ATTR_POLICY_KEY, mKey);
        mFilter.writeToXml(serializer);
    }

    @Override
    PersistentPreferredActivityPolicyKey readFromXml(TypedXmlPullParser parser)
            throws XmlPullParserException, IOException {
        String policyKey = parser.getAttributeValue(/* namespace= */ null, ATTR_POLICY_KEY);
        IntentFilter filter = new IntentFilter();
        filter.readFromXml(parser);
        return new PersistentPreferredActivityPolicyKey(policyKey, filter);
    }

    @Override
    void writeToBundle(Bundle bundle) {
        super.writeToBundle(bundle);
        Bundle extraPolicyParams = new Bundle();
        extraPolicyParams.putParcelable(EXTRA_INTENT_FILTER, mFilter);
        bundle.putBundle(EXTRA_POLICY_BUNDLE_KEY, extraPolicyParams);
    }

    @Override
    public boolean equals(@Nullable Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        PersistentPreferredActivityPolicyKey other = (PersistentPreferredActivityPolicyKey) o;
        return Objects.equals(mKey, other.mKey)
                && IntentResolver.filterEquals(mFilter, other.mFilter);
    }

    @Override
    public int hashCode() {
        return Objects.hash(mKey, mFilter);
    }

    @Override
    public String toString() {
        return "mKey= " + mKey + "; mFilter= " + mFilter;
    }
}
