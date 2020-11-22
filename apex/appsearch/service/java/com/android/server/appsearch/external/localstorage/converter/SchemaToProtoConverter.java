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

package com.android.server.appsearch.external.localstorage.converter;

import android.annotation.NonNull;
import android.app.appsearch.AppSearchSchema;

import com.android.internal.util.Preconditions;

import com.google.android.icing.proto.PropertyConfigProto;
import com.google.android.icing.proto.SchemaTypeConfigProto;
import com.google.android.icing.proto.StringIndexingConfig;
import com.google.android.icing.proto.TermMatchType;

import java.util.List;

/**
 * Translates an {@link AppSearchSchema} into a {@link SchemaTypeConfigProto}.
 *
 * @hide
 */
public final class SchemaToProtoConverter {
    private SchemaToProtoConverter() {}

    /**
     * Converts an {@link android.app.appsearch.AppSearchSchema} into a {@link
     * SchemaTypeConfigProto}.
     */
    @NonNull
    public static SchemaTypeConfigProto convert(@NonNull AppSearchSchema schema) {
        Preconditions.checkNotNull(schema);
        SchemaTypeConfigProto.Builder protoBuilder =
                SchemaTypeConfigProto.newBuilder().setSchemaType(schema.getSchemaType());
        List<AppSearchSchema.PropertyConfig> properties = schema.getProperties();
        for (int i = 0; i < properties.size(); i++) {
            PropertyConfigProto propertyProto = convertProperty(properties.get(i));
            protoBuilder.addProperties(propertyProto);
        }
        return protoBuilder.build();
    }

    @NonNull
    private static PropertyConfigProto convertProperty(
            @NonNull AppSearchSchema.PropertyConfig property) {
        Preconditions.checkNotNull(property);
        PropertyConfigProto.Builder propertyConfigProto =
                PropertyConfigProto.newBuilder().setPropertyName(property.getName());
        StringIndexingConfig.Builder indexingConfig = StringIndexingConfig.newBuilder();

        // Set dataType
        @AppSearchSchema.PropertyConfig.DataType int dataType = property.getDataType();
        PropertyConfigProto.DataType.Code dataTypeProto =
                PropertyConfigProto.DataType.Code.forNumber(dataType);
        if (dataTypeProto == null) {
            throw new IllegalArgumentException("Invalid dataType: " + dataType);
        }
        propertyConfigProto.setDataType(dataTypeProto);

        // Set schemaType
        String schemaType = property.getSchemaType();
        if (schemaType != null) {
            propertyConfigProto.setSchemaType(schemaType);
        }

        // Set cardinality
        @AppSearchSchema.PropertyConfig.Cardinality int cardinality = property.getCardinality();
        PropertyConfigProto.Cardinality.Code cardinalityProto =
                PropertyConfigProto.Cardinality.Code.forNumber(cardinality);
        if (cardinalityProto == null) {
            throw new IllegalArgumentException("Invalid cardinality: " + dataType);
        }
        propertyConfigProto.setCardinality(cardinalityProto);

        // Set indexingType
        @AppSearchSchema.PropertyConfig.IndexingType int indexingType = property.getIndexingType();
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
        @AppSearchSchema.PropertyConfig.TokenizerType
        int tokenizerType = property.getTokenizerType();
        StringIndexingConfig.TokenizerType.Code tokenizerTypeProto =
                StringIndexingConfig.TokenizerType.Code.forNumber(tokenizerType);
        if (tokenizerTypeProto == null) {
            throw new IllegalArgumentException("Invalid tokenizerType: " + tokenizerType);
        }
        indexingConfig.setTokenizerType(tokenizerTypeProto);

        // Build!
        propertyConfigProto.setStringIndexingConfig(indexingConfig);
        return propertyConfigProto.build();
    }
}
