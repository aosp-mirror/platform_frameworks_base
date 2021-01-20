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
import android.util.Log;

import com.android.internal.util.Preconditions;

import com.google.android.icing.proto.PropertyConfigProto;
import com.google.android.icing.proto.SchemaTypeConfigProto;
import com.google.android.icing.proto.SchemaTypeConfigProtoOrBuilder;
import com.google.android.icing.proto.StringIndexingConfig;
import com.google.android.icing.proto.TermMatchType;

import java.util.List;

/**
 * Translates an {@link AppSearchSchema} into a {@link SchemaTypeConfigProto}.
 *
 * @hide
 */
public final class SchemaToProtoConverter {
    private static final String TAG = "AppSearchSchemaToProtoC";

    private SchemaToProtoConverter() {}

    /**
     * Converts an {@link android.app.appsearch.AppSearchSchema} into a {@link
     * SchemaTypeConfigProto}.
     */
    @NonNull
    public static SchemaTypeConfigProto toSchemaTypeConfigProto(@NonNull AppSearchSchema schema) {
        Preconditions.checkNotNull(schema);
        SchemaTypeConfigProto.Builder protoBuilder =
                SchemaTypeConfigProto.newBuilder()
                        .setSchemaType(schema.getSchemaType())
                        .setVersion(schema.getVersion());
        List<AppSearchSchema.PropertyConfig> properties = schema.getProperties();
        for (int i = 0; i < properties.size(); i++) {
            PropertyConfigProto propertyProto = toPropertyConfigProto(properties.get(i));
            protoBuilder.addProperties(propertyProto);
        }
        return protoBuilder.build();
    }

    @NonNull
    private static PropertyConfigProto toPropertyConfigProto(
            @NonNull AppSearchSchema.PropertyConfig property) {
        Preconditions.checkNotNull(property);
        PropertyConfigProto.Builder builder =
                PropertyConfigProto.newBuilder().setPropertyName(property.getName());
        StringIndexingConfig.Builder indexingConfig = StringIndexingConfig.newBuilder();

        // Set dataType
        @AppSearchSchema.PropertyConfig.DataType int dataType = property.getDataType();
        PropertyConfigProto.DataType.Code dataTypeProto =
                PropertyConfigProto.DataType.Code.forNumber(dataType);
        if (dataTypeProto == null) {
            throw new IllegalArgumentException("Invalid dataType: " + dataType);
        }
        builder.setDataType(dataTypeProto);

        // Set schemaType
        String schemaType = property.getSchemaType();
        if (schemaType != null) {
            builder.setSchemaType(schemaType);
        }

        // Set cardinality
        @AppSearchSchema.PropertyConfig.Cardinality int cardinality = property.getCardinality();
        PropertyConfigProto.Cardinality.Code cardinalityProto =
                PropertyConfigProto.Cardinality.Code.forNumber(cardinality);
        if (cardinalityProto == null) {
            throw new IllegalArgumentException("Invalid cardinality: " + dataType);
        }
        builder.setCardinality(cardinalityProto);

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
        builder.setStringIndexingConfig(indexingConfig);
        return builder.build();
    }

    /**
     * Converts a {@link SchemaTypeConfigProto} into an {@link
     * android.app.appsearch.AppSearchSchema}.
     */
    @NonNull
    public static AppSearchSchema toAppSearchSchema(@NonNull SchemaTypeConfigProtoOrBuilder proto) {
        Preconditions.checkNotNull(proto);
        AppSearchSchema.Builder builder =
                new AppSearchSchema.Builder(proto.getSchemaType()).setVersion(proto.getVersion());
        List<PropertyConfigProto> properties = proto.getPropertiesList();
        for (int i = 0; i < properties.size(); i++) {
            AppSearchSchema.PropertyConfig propertyConfig = toPropertyConfig(properties.get(i));
            builder.addProperty(propertyConfig);
        }
        return builder.build();
    }

    @NonNull
    private static AppSearchSchema.PropertyConfig toPropertyConfig(
            @NonNull PropertyConfigProto proto) {
        Preconditions.checkNotNull(proto);
        AppSearchSchema.PropertyConfig.Builder builder =
                new AppSearchSchema.PropertyConfig.Builder(proto.getPropertyName())
                        .setDataType(proto.getDataType().getNumber())
                        .setCardinality(proto.getCardinality().getNumber())
                        .setTokenizerType(
                                proto.getStringIndexingConfig().getTokenizerType().getNumber());

        // Set schema
        if (!proto.getSchemaType().isEmpty()) {
            builder.setSchemaType(proto.getSchemaType());
        }

        // Set indexingType
        @AppSearchSchema.PropertyConfig.IndexingType int indexingType;
        TermMatchType.Code termMatchTypeProto = proto.getStringIndexingConfig().getTermMatchType();
        switch (termMatchTypeProto) {
            case UNKNOWN:
                indexingType = AppSearchSchema.PropertyConfig.INDEXING_TYPE_NONE;
                break;
            case EXACT_ONLY:
                indexingType = AppSearchSchema.PropertyConfig.INDEXING_TYPE_EXACT_TERMS;
                break;
            case PREFIX:
                indexingType = AppSearchSchema.PropertyConfig.INDEXING_TYPE_PREFIXES;
                break;
            default:
                // Avoid crashing in the 'read' path; we should try to interpret the document to the
                // extent possible.
                Log.w(TAG, "Invalid indexingType: " + termMatchTypeProto.getNumber());
                indexingType = AppSearchSchema.PropertyConfig.INDEXING_TYPE_NONE;
        }
        builder.setIndexingType(indexingType);

        return builder.build();
    }
}
