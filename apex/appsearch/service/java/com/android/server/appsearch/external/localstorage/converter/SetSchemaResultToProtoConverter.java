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
import android.app.appsearch.SetSchemaResult;

import com.android.internal.util.Preconditions;

import com.google.android.icing.proto.SetSchemaResultProto;

/**
 * Translates a {@link SetSchemaResultProto} into {@link SetSchemaResult}.
 *
 * @hide
 */
public class SetSchemaResultToProtoConverter {

    private SetSchemaResultToProtoConverter() {}

    /**
     * Translate a {@link SetSchemaResultProto} into {@link SetSchemaResult}.
     *
     * @param proto The {@link SetSchemaResultProto} containing results.
     * @param prefix The prefix need to removed from schemaTypes
     * @return {@link SetSchemaResult} of results.
     */
    @NonNull
    public static SetSchemaResult toSetSchemaResult(
            @NonNull SetSchemaResultProto proto, @NonNull String prefix) {
        Preconditions.checkNotNull(proto);
        Preconditions.checkNotNull(prefix);
        SetSchemaResult.Builder builder =
                new SetSchemaResult.Builder()
                        .setResultCode(
                                ResultCodeToProtoConverter.toResultCode(
                                        proto.getStatus().getCode()));

        for (int i = 0; i < proto.getDeletedSchemaTypesCount(); i++) {
            builder.addDeletedSchemaType(proto.getDeletedSchemaTypes(i).substring(prefix.length()));
        }

        for (int i = 0; i < proto.getIncompatibleSchemaTypesCount(); i++) {
            builder.addIncompatibleSchemaType(
                    proto.getIncompatibleSchemaTypes(i).substring(prefix.length()));
        }

        return builder.build();
    }
}
