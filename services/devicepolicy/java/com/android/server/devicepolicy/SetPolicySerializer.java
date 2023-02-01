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

import com.android.modules.utils.TypedXmlPullParser;
import com.android.modules.utils.TypedXmlSerializer;

import java.io.IOException;
import java.util.Objects;
import java.util.Set;

// TODO(scottjonathan): Replace with actual implementation
final class SetPolicySerializer<V> extends PolicySerializer<Set<V>> {

    @Override
    void saveToXml(TypedXmlSerializer serializer, String attributeName, @NonNull Set<V> value)
            throws IOException {
        Objects.requireNonNull(value);
    }

    @Nullable
    @Override
    Set<V> readFromXml(TypedXmlPullParser parser, String attributeName) {
        return null;
    }
}
