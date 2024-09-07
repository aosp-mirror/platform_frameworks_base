/*
 * Copyright (C) 2023 The Android Open Source Project
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
import android.app.admin.LockTaskPolicy;
import android.util.Log;

import com.android.modules.utils.TypedXmlPullParser;
import com.android.modules.utils.TypedXmlSerializer;

import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.util.Objects;
import java.util.Set;

final class LockTaskPolicySerializer extends PolicySerializer<LockTaskPolicy> {

    private static final String TAG = "LockTaskPolicySerializer";

    private static final String ATTR_PACKAGES = "packages";
    private static final String ATTR_PACKAGES_SEPARATOR = ";";
    private static final String ATTR_FLAGS = "flags";

    @Override
    void saveToXml(TypedXmlSerializer serializer, @NonNull LockTaskPolicy value)
            throws IOException {
        Objects.requireNonNull(value);
        serializer.attribute(
                /* namespace= */ null,
                ATTR_PACKAGES,
                String.join(ATTR_PACKAGES_SEPARATOR, value.getPackages()));
        serializer.attributeInt(
                /* namespace= */ null,
                ATTR_FLAGS,
                value.getFlags());
    }

    @Override
    LockTaskPolicy readFromXml(TypedXmlPullParser parser) {
        String packagesStr = parser.getAttributeValue(
                /* namespace= */ null,
                ATTR_PACKAGES);
        if (packagesStr == null) {
            Log.e(TAG, "Error parsing LockTask policy value.");
            return null;
        }
        Set<String> packages = Set.of(packagesStr.split(ATTR_PACKAGES_SEPARATOR));
        try {
            int flags = parser.getAttributeInt(
                    /* namespace= */ null,
                    ATTR_FLAGS);
            return new LockTaskPolicy(packages, flags);
        } catch (XmlPullParserException e) {
            Log.e(TAG, "Error parsing LockTask policy value", e);
            return null;
        }
    }
}
