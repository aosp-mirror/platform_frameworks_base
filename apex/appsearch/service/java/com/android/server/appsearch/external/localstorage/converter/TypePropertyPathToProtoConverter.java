/*
 * Copyright 2021 The Android Open Source Project
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

package com.android.server.appsearch.external.localstorage.converter;

import android.annotation.NonNull;

import com.google.android.icing.proto.TypePropertyMask;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Translates a <code>Map<String, List<String>></code> into <code>List<TypePropertyMask></code>.
 *
 * @hide
 */
public final class TypePropertyPathToProtoConverter {
    private TypePropertyPathToProtoConverter() {}

    /** Extracts {@link TypePropertyMask} information from a {@link Map}. */
    @NonNull
    public static List<TypePropertyMask> toTypePropertyMaskList(
            @NonNull Map<String, List<String>> typePropertyPaths) {
        Objects.requireNonNull(typePropertyPaths);
        List<TypePropertyMask> typePropertyMasks = new ArrayList<>(typePropertyPaths.size());
        for (Map.Entry<String, List<String>> e : typePropertyPaths.entrySet()) {
            typePropertyMasks.add(
                    TypePropertyMask.newBuilder()
                            .setSchemaType(e.getKey())
                            .addAllPaths(e.getValue())
                            .build());
        }
        return typePropertyMasks;
    }
}
