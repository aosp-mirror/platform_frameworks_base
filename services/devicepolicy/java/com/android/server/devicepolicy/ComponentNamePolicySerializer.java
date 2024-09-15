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
import android.app.admin.ComponentNamePolicyValue;
import android.content.ComponentName;
import android.util.Log;

import com.android.modules.utils.TypedXmlPullParser;
import com.android.modules.utils.TypedXmlSerializer;

import java.io.IOException;
import java.util.Objects;

final class ComponentNamePolicySerializer extends PolicySerializer<ComponentName> {

    private static final String TAG = "ComponentNamePolicySerializer";

    private static final String ATTR_PACKAGE_NAME = "package-name";
    private static final String ATTR_CLASS_NAME = "class-name";

    @Override
    void saveToXml(TypedXmlSerializer serializer, @NonNull ComponentName value) throws IOException {
        Objects.requireNonNull(value);
        serializer.attribute(
                /* namespace= */ null, ATTR_PACKAGE_NAME, value.getPackageName());
        serializer.attribute(
                /* namespace= */ null, ATTR_CLASS_NAME, value.getClassName());
    }

    @Nullable
    @Override
    ComponentNamePolicyValue readFromXml(TypedXmlPullParser parser) {
        String packageName = parser.getAttributeValue(
                /* namespace= */ null, ATTR_PACKAGE_NAME);
        String className = parser.getAttributeValue(
                /* namespace= */ null, ATTR_CLASS_NAME);
        if (packageName == null || className == null) {
            Log.e(TAG, "Error parsing ComponentName policy.");
            return null;
        }
        return new ComponentNamePolicyValue(new ComponentName(packageName, className));
    }
}
