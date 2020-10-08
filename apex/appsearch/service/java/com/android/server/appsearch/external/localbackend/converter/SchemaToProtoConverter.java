/*
 * Copyright 2020 The Android Open Source Project
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

package com.android.server.appsearch.external.localbackend.converter;

import android.os.Bundle;

import android.annotation.NonNull;

import android.app.appsearch.AppSearchSchema;
import com.android.internal.util.Preconditions;

import com.google.android.icing.proto.IndexingConfig;
import com.google.android.icing.proto.PropertyConfigProto;
import com.google.android.icing.proto.SchemaTypeConfigProto;
import com.google.android.icing.proto.TermMatchType;

import java.util.ArrayList;

/**
 * Translates an {@link AppSearchSchema} into a {@link SchemaTypeConfigProto}.
 * @hide
 */

public final class SchemaToProtoConverter {
    private SchemaToProtoConverter() {}

    /**
     * Converts an {@link android.app.appsearch.AppSearchSchema} into a
     * {@link SchemaTypeConfigProto}.
     */
    @NonNull
    public static SchemaTypeConfigProto convert(@NonNull AppSearchSchema schema) {
        Preconditions.checkNotNull(schema);
        Bundle bundle = schema.getBundle();
        SchemaTypeConfigProto.Builder protoBuilder =
                SchemaTypeConfigProto.newBuilder()
                        .setSchemaType(bundle.getString(AppSearchSchema.SCHEMA_TYPE_FIELD, ""));
        ArrayList<Bundle> properties =
                bundle.getParcelableArrayList(AppSearchSchema.PROPERTIES_FIELD);
        if (properties != null) {
            for (int i = 0; i < properties.size(); i++) {
                PropertyConfigProto propertyProto = convertProperty(properties.get(i));
                protoBuilder.addProperties(propertyProto);
            }
        }
        return protoBuilder.build();
    }

    @NonNull
    private static PropertyConfigProto convertProperty(@NonNull Bundle bundle) {
        Preconditions.checkNotNull(bundle);
        PropertyConfigProto.Builder propertyConfigProto = PropertyConfigProto.newBuilder()
                .setPropertyName(bundle.getString(AppSearchSchema.PropertyConfig.NAME_FIELD, ""));
        IndexingConfig.Builder indexingConfig = IndexingConfig.newBuilder();

        // Set dataType
        @AppSearchSchema.PropertyConfig.DataType int dataType =
                bundle.getInt(AppSearchSchema.PropertyConfig.DATA_TYPE_FIELD);
        PropertyConfigProto.DataType.Code dataTypeProto =
                PropertyConfigProto.DataType.Code.forNumber(dataType);
        if (dataTypeProto == null) {
            throw new IllegalArgumentException("Invalid dataType: " + dataType);
        }
        propertyConfigProto.setDataType(dataTypeProto);

        // Set schemaType
        propertyConfigProto.setSchemaType(
                bundle.getString(AppSearchSchema.PropertyConfig.SCHEMA_TYPE_FIELD, ""));

        // Set cardinality
        @AppSearchSchema.PropertyConfig.Cardinality int cardinality =
                bundle.getInt(AppSearchSchema.PropertyConfig.CARDINALITY_FIELD);
        PropertyConfigProto.Cardinality.Code cardinalityProto =
                PropertyConfigProto.Cardinality.Code.forNumber(cardinality);
        if (cardinalityProto == null) {
            throw new IllegalArgumentException("Invalid cardinality: " + dataType);
        }
        propertyConfigProto.setCardinality(cardinalityProto);

        // Set indexingType
        @AppSearchSchema.PropertyConfig.IndexingType int indexingType =
                bundle.getInt(AppSearchSchema.PropertyConfig.INDEXING_TYPE_FIELD);
        TermMatchType.Code termMatchTypeProto;
        switch (indexingType) {
            case AppSearchSchema.PropertyConfig.INDEXING_TYPE_NONE:
                termMatchTypeProto = TermMatchType.Code.UNKNOWN;
                break;
            case AppSearchSchema.PropertyConfig.INDEXING_TYPE_EXACT_TERMS:
                termMatchTypeProto = TermMatchType.Code.EXACT_ONLY;
                break;
            case AppSearchSchema.PropertyConfig.INDEXING_TYPE_PREFIXES:
                termMatchTypeProto = TermMatchType.Code.PREFIX;
                break;
            default:
                throw new IllegalArgumentException("Invalid indexingType: " + indexingType);
        }
        indexingConfig.setTermMatchType(termMatchTypeProto);

        // Set tokenizerType
        @AppSearchSchema.PropertyConfig.TokenizerType int tokenizerType =
                bundle.getInt(AppSearchSchema.PropertyConfig.TOKENIZER_TYPE_FIELD);
        IndexingConfig.TokenizerType.Code tokenizerTypeProto =
                IndexingConfig.TokenizerType.Code.forNumber(tokenizerType);
        if (tokenizerTypeProto == null) {
            throw new IllegalArgumentException("Invalid tokenizerType: " + tokenizerType);
        }
        indexingConfig.setTokenizerType(tokenizerTypeProto);

        // Build!
        propertyConfigProto.setIndexingConfig(indexingConfig);
        return propertyConfigProto.build();
    }
}
