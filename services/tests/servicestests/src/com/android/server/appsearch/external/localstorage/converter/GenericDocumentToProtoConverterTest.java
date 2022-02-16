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

import static com.google.common.truth.Truth.assertThat;

import android.app.appsearch.GenericDocument;

import com.android.server.appsearch.icing.proto.DocumentProto;
import com.android.server.appsearch.icing.proto.PropertyConfigProto;
import com.android.server.appsearch.icing.proto.PropertyProto;
import com.android.server.appsearch.icing.proto.SchemaTypeConfigProto;
import com.android.server.appsearch.protobuf.ByteString;

import com.google.common.collect.ImmutableMap;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class GenericDocumentToProtoConverterTest {
    private static final byte[] BYTE_ARRAY_1 = new byte[] {(byte) 1, (byte) 2, (byte) 3};
    private static final byte[] BYTE_ARRAY_2 = new byte[] {(byte) 4, (byte) 5, (byte) 6, (byte) 7};
    private static final String SCHEMA_TYPE_1 = "sDocumentPropertiesSchemaType1";
    private static final String SCHEMA_TYPE_2 = "sDocumentPropertiesSchemaType2";
    private static final GenericDocument DOCUMENT_PROPERTIES_1 =
            new GenericDocument.Builder<GenericDocument.Builder<?>>(
                            "namespace", "sDocumentProperties1", SCHEMA_TYPE_1)
                    .setCreationTimestampMillis(12345L)
                    .build();
    private static final GenericDocument DOCUMENT_PROPERTIES_2 =
            new GenericDocument.Builder<GenericDocument.Builder<?>>(
                            "namespace", "sDocumentProperties2", SCHEMA_TYPE_2)
                    .setCreationTimestampMillis(6789L)
                    .build();
    private static final SchemaTypeConfigProto SCHEMA_PROTO_1 =
            SchemaTypeConfigProto.newBuilder().setSchemaType(SCHEMA_TYPE_1).build();
    private static final SchemaTypeConfigProto SCHEMA_PROTO_2 =
            SchemaTypeConfigProto.newBuilder().setSchemaType(SCHEMA_TYPE_2).build();
    private static final String PREFIX = "package$databaseName/";
    private static final Map<String, SchemaTypeConfigProto> SCHEMA_MAP =
            ImmutableMap.of(
                    PREFIX + SCHEMA_TYPE_1, SCHEMA_PROTO_1, PREFIX + SCHEMA_TYPE_2, SCHEMA_PROTO_2);

    @Test
    public void testDocumentProtoConvert() {
        GenericDocument document =
                new GenericDocument.Builder<GenericDocument.Builder<?>>(
                                "namespace", "id1", SCHEMA_TYPE_1)
                        .setCreationTimestampMillis(5L)
                        .setScore(1)
                        .setTtlMillis(1L)
                        .setPropertyLong("longKey1", 1L)
                        .setPropertyDouble("doubleKey1", 1.0)
                        .setPropertyBoolean("booleanKey1", true)
                        .setPropertyString("stringKey1", "test-value1")
                        .setPropertyBytes("byteKey1", BYTE_ARRAY_1, BYTE_ARRAY_2)
                        .setPropertyDocument("documentKey1", DOCUMENT_PROPERTIES_1)
                        .setPropertyDocument("documentKey2", DOCUMENT_PROPERTIES_2)
                        .build();

        // Create the Document proto. Need to sort the property order by key.
        DocumentProto.Builder documentProtoBuilder =
                DocumentProto.newBuilder()
                        .setUri("id1")
                        .setSchema(SCHEMA_TYPE_1)
                        .setCreationTimestampMs(5L)
                        .setScore(1)
                        .setTtlMs(1L)
                        .setNamespace("namespace");
        HashMap<String, PropertyProto.Builder> propertyProtoMap = new HashMap<>();
        propertyProtoMap.put(
                "longKey1", PropertyProto.newBuilder().setName("longKey1").addInt64Values(1L));
        propertyProtoMap.put(
                "doubleKey1",
                PropertyProto.newBuilder().setName("doubleKey1").addDoubleValues(1.0));
        propertyProtoMap.put(
                "booleanKey1",
                PropertyProto.newBuilder().setName("booleanKey1").addBooleanValues(true));
        propertyProtoMap.put(
                "stringKey1",
                PropertyProto.newBuilder().setName("stringKey1").addStringValues("test-value1"));
        propertyProtoMap.put(
                "byteKey1",
                PropertyProto.newBuilder()
                        .setName("byteKey1")
                        .addBytesValues(ByteString.copyFrom(BYTE_ARRAY_1))
                        .addBytesValues(ByteString.copyFrom(BYTE_ARRAY_2)));
        propertyProtoMap.put(
                "documentKey1",
                PropertyProto.newBuilder()
                        .setName("documentKey1")
                        .addDocumentValues(
                                GenericDocumentToProtoConverter.toDocumentProto(
                                        DOCUMENT_PROPERTIES_1)));
        propertyProtoMap.put(
                "documentKey2",
                PropertyProto.newBuilder()
                        .setName("documentKey2")
                        .addDocumentValues(
                                GenericDocumentToProtoConverter.toDocumentProto(
                                        DOCUMENT_PROPERTIES_2)));
        List<String> sortedKey = new ArrayList<>(propertyProtoMap.keySet());
        Collections.sort(sortedKey);
        for (String key : sortedKey) {
            documentProtoBuilder.addProperties(propertyProtoMap.get(key));
        }
        DocumentProto documentProto = documentProtoBuilder.build();

        GenericDocument convertedGenericDocument =
                GenericDocumentToProtoConverter.toGenericDocument(
                        documentProto, PREFIX, SCHEMA_MAP);
        DocumentProto convertedDocumentProto =
                GenericDocumentToProtoConverter.toDocumentProto(document);

        assertThat(convertedDocumentProto).isEqualTo(documentProto);
        assertThat(convertedGenericDocument).isEqualTo(document);
    }

    @Test
    public void testConvertDocument_whenPropertyHasEmptyList() {
        String emptyStringPropertyName = "emptyStringProperty";
        DocumentProto documentProto =
                DocumentProto.newBuilder()
                        .setUri("id1")
                        .setSchema(SCHEMA_TYPE_1)
                        .setCreationTimestampMs(5L)
                        .setNamespace("namespace")
                        .addProperties(
                                PropertyProto.newBuilder().setName(emptyStringPropertyName).build())
                        .build();

        PropertyConfigProto emptyStringListProperty =
                PropertyConfigProto.newBuilder()
                        .setCardinality(PropertyConfigProto.Cardinality.Code.REPEATED)
                        .setDataType(PropertyConfigProto.DataType.Code.STRING)
                        .setPropertyName(emptyStringPropertyName)
                        .build();
        SchemaTypeConfigProto schemaTypeConfigProto =
                SchemaTypeConfigProto.newBuilder()
                        .addProperties(emptyStringListProperty)
                        .setSchemaType(SCHEMA_TYPE_1)
                        .build();
        Map<String, SchemaTypeConfigProto> schemaMap =
                ImmutableMap.of(PREFIX + SCHEMA_TYPE_1, schemaTypeConfigProto);

        GenericDocument convertedDocument =
                GenericDocumentToProtoConverter.toGenericDocument(documentProto, PREFIX, schemaMap);

        GenericDocument expectedDocument =
                new GenericDocument.Builder<GenericDocument.Builder<?>>(
                                "namespace", "id1", SCHEMA_TYPE_1)
                        .setCreationTimestampMillis(5L)
                        .setPropertyString(emptyStringPropertyName)
                        .build();
        assertThat(convertedDocument).isEqualTo(expectedDocument);
        assertThat(expectedDocument.getPropertyStringArray(emptyStringPropertyName)).isEmpty();
    }

    @Test
    public void testConvertDocument_whenNestedDocumentPropertyHasEmptyList() {
        String emptyStringPropertyName = "emptyStringProperty";
        String documentPropertyName = "documentProperty";
        DocumentProto nestedDocumentProto =
                DocumentProto.newBuilder()
                        .setUri("id2")
                        .setSchema(SCHEMA_TYPE_2)
                        .setCreationTimestampMs(5L)
                        .setNamespace("namespace")
                        .addProperties(
                                PropertyProto.newBuilder().setName(emptyStringPropertyName).build())
                        .build();
        DocumentProto documentProto =
                DocumentProto.newBuilder()
                        .setUri("id1")
                        .setSchema(SCHEMA_TYPE_1)
                        .setCreationTimestampMs(5L)
                        .setNamespace("namespace")
                        .addProperties(
                                PropertyProto.newBuilder()
                                        .addDocumentValues(nestedDocumentProto)
                                        .setName(documentPropertyName)
                                        .build())
                        .build();

        PropertyConfigProto documentProperty =
                PropertyConfigProto.newBuilder()
                        .setCardinality(PropertyConfigProto.Cardinality.Code.REPEATED)
                        .setDataType(PropertyConfigProto.DataType.Code.DOCUMENT)
                        .setPropertyName(documentPropertyName)
                        .setSchemaType(SCHEMA_TYPE_2)
                        .build();
        SchemaTypeConfigProto schemaTypeConfigProto =
                SchemaTypeConfigProto.newBuilder()
                        .addProperties(documentProperty)
                        .setSchemaType(SCHEMA_TYPE_1)
                        .build();
        PropertyConfigProto emptyStringListProperty =
                PropertyConfigProto.newBuilder()
                        .setCardinality(PropertyConfigProto.Cardinality.Code.REPEATED)
                        .setDataType(PropertyConfigProto.DataType.Code.STRING)
                        .setPropertyName(emptyStringPropertyName)
                        .build();
        SchemaTypeConfigProto nestedSchemaTypeConfigProto =
                SchemaTypeConfigProto.newBuilder()
                        .addProperties(emptyStringListProperty)
                        .setSchemaType(SCHEMA_TYPE_2)
                        .build();
        Map<String, SchemaTypeConfigProto> schemaMap =
                ImmutableMap.of(
                        PREFIX + SCHEMA_TYPE_1,
                        schemaTypeConfigProto,
                        PREFIX + SCHEMA_TYPE_2,
                        nestedSchemaTypeConfigProto);

        GenericDocument convertedDocument =
                GenericDocumentToProtoConverter.toGenericDocument(documentProto, PREFIX, schemaMap);

        GenericDocument expectedDocument =
                new GenericDocument.Builder<GenericDocument.Builder<?>>(
                                "namespace", "id1", SCHEMA_TYPE_1)
                        .setCreationTimestampMillis(5L)
                        .setPropertyDocument(
                                documentPropertyName,
                                new GenericDocument.Builder<GenericDocument.Builder<?>>(
                                                "namespace", "id2", SCHEMA_TYPE_2)
                                        .setCreationTimestampMillis(5L)
                                        .setPropertyString(emptyStringPropertyName)
                                        .build())
                        .build();
        assertThat(convertedDocument).isEqualTo(expectedDocument);
        assertThat(
                        expectedDocument
                                .getPropertyDocument(documentPropertyName)
                                .getPropertyStringArray(emptyStringPropertyName))
                .isEmpty();
    }
}
