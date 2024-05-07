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
import android.app.admin.StringSetPolicyValue;
import android.util.Log;

import com.android.modules.utils.TypedXmlPullParser;
import com.android.modules.utils.TypedXmlSerializer;

import java.io.IOException;
import java.util.Objects;
import java.util.Set;

// TODO(scottjonathan): Replace with generic set implementation
final class StringSetPolicySerializer extends PolicySerializer<Set<String>> {
    private static final String ATTR_VALUES = "strings";
    private static final String ATTR_VALUES_SEPARATOR = ";";
    @Override
    void saveToXml(TypedXmlSerializer serializer, @NonNull Set<String> value) throws IOException {
        Objects.requireNonNull(value);
        serializer.attribute(
                /* namespace= */ null, ATTR_VALUES, String.join(ATTR_VALUES_SEPARATOR, value));
    }

    @Nullable
    @Override
    PolicyValue<Set<String>> readFromXml(TypedXmlPullParser parser) {
        String valuesStr = parser.getAttributeValue(/* namespace= */ null, ATTR_VALUES);
        if (valuesStr == null) {
            Log.e(DevicePolicyEngine.TAG, "Error parsing StringSet policy value.");
            return null;
        }
        Set<String> values = Set.of(valuesStr.split(ATTR_VALUES_SEPARATOR));
        return new StringSetPolicyValue(values);
    }
}
