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

import com.android.modules.utils.TypedXmlPullParser;

/**
 * Default implementation for {@link PolicyKey} used to identify a policy that doesn't require any
 * additional arguments to be represented in the policy engine's data structure.
 */
final class DefaultPolicyKey extends PolicyKey {
    private static final String ATTR_GENERIC_POLICY_KEY = "generic-policy-key";

    DefaultPolicyKey(String policyKey) {
        super(policyKey);
    }

    String getKey() {
        return mKey;
    }

    static DefaultPolicyKey readGenericPolicyKeyFromXml(TypedXmlPullParser parser) {
        String genericPolicyKey = parser.getAttributeValue(
                /* namespace= */ null, ATTR_GENERIC_POLICY_KEY);
        return new DefaultPolicyKey(genericPolicyKey);
    }

}
