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
import android.app.appsearch.SetSchemaResponse;

import com.google.android.icing.proto.SetSchemaResultProto;

import java.util.Objects;

/**
 * Translates a {@link SetSchemaResultProto} into {@link SetSchemaResponse}.
 *
 * @hide
 */
public class SetSchemaResponseToProtoConverter {

    private SetSchemaResponseToProtoConverter() {}

    /**
     * Translate a {@link SetSchemaResultProto} into {@link SetSchemaResponse}.
     *
     * @param proto The {@link SetSchemaResultProto} containing results.
     * @param prefix The prefix need to removed from schemaTypes
     * @return The {@link SetSchemaResponse} object.
     */
    @NonNull
    public static SetSchemaResponse toSetSchemaResponse(
            @NonNull SetSchemaResultProto proto, @NonNull String prefix) {
        Objects.requireNonNull(proto);
        Objects.requireNonNull(prefix);
        SetSchemaResponse.Builder builder = new SetSchemaResponse.Builder();

        for (int i = 0; i < proto.getDeletedSchemaTypesCount(); i++) {
            builder.addDeletedType(proto.getDeletedSchemaTypes(i).substring(prefix.length()));
        }

        for (int i = 0; i < proto.getIncompatibleSchemaTypesCount(); i++) {
            builder.addIncompatibleType(
                    proto.getIncompatibleSchemaTypes(i).substring(prefix.length()));
        }

        return builder.build();
    }
}
