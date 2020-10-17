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

import android.app.appsearch.GenericDocument;
import com.android.internal.util.Preconditions;

import com.google.android.icing.proto.DocumentProto;
import com.google.android.icing.proto.PropertyProto;
import com.google.protobuf.ByteString;

import java.util.ArrayList;
import java.util.Collections;

/**
 * Translates a {@link GenericDocument} into a {@link DocumentProto}.
 * @hide
 */

public final class GenericDocumentToProtoConverter {
    private GenericDocumentToProtoConverter() {}

    /** Converts a {@link GenericDocument} into a {@link DocumentProto}. */
    @NonNull
    @SuppressWarnings("unchecked")
    public static DocumentProto convert(@NonNull GenericDocument document) {
        Preconditions.checkNotNull(document);
        Bundle properties = document.getBundle().getBundle(GenericDocument.PROPERTIES_FIELD);
        DocumentProto.Builder mProtoBuilder = DocumentProto.newBuilder();
        mProtoBuilder.setUri(document.getUri())
                .setSchema(document.getSchemaType())
                .setNamespace(document.getNamespace())
                .setScore(document.getScore())
                .setTtlMs(document.getTtlMillis())
                .setCreationTimestampMs(document.getCreationTimestampMillis());
        ArrayList<String> keys = new ArrayList<>(properties.keySet());
        Collections.sort(keys);
        for (int i = 0; i < keys.size(); i++) {
            String name = keys.get(i);
            Object values = properties.get(name);
            PropertyProto.Builder propertyProto = PropertyProto.newBuilder().setName(name);
            if (values instanceof boolean[]) {
                for (boolean value : (boolean[]) values) {
                    propertyProto.addBooleanValues(value);
                }
            } else if (values instanceof long[]) {
                for (long value : (long[]) values) {
                    propertyProto.addInt64Values(value);
                }
            } else if (values instanceof double[]) {
                for (double value : (double[]) values) {
                    propertyProto.addDoubleValues(value);
                }
            } else if (values instanceof String[]) {
                for (String value : (String[]) values) {
                    propertyProto.addStringValues(value);
                }
            } else if (values instanceof ArrayList) {
                for (Bundle bundle : (ArrayList<Bundle>) values) {
                    byte[] value = bundle.getByteArray(GenericDocument.BYTE_ARRAY_FIELD);
                    propertyProto.addBytesValues(ByteString.copyFrom(value));
                }
            } else if (values instanceof Bundle[]) {
                for (Bundle bundle : (Bundle[]) values) {
                    GenericDocument value = new GenericDocument(bundle);
                    propertyProto.addDocumentValues(convert(value));
                }
            } else {
                throw new IllegalStateException(
                        "Property \"" + name + "\" has unsupported value type \""
                                + values.getClass().getSimpleName() + "\"");
            }
            mProtoBuilder.addProperties(propertyProto);
        }
        return mProtoBuilder.build();
    }

    /** Converts a {@link DocumentProto} into a {@link GenericDocument}. */
    @NonNull
    public static GenericDocument convert(@NonNull DocumentProto proto) {
        Preconditions.checkNotNull(proto);
        GenericDocument.Builder<?> documentBuilder =
                new GenericDocument.Builder<>(proto.getUri(), proto.getSchema())
                        .setNamespace(proto.getNamespace())
                        .setScore(proto.getScore())
                        .setTtlMillis(proto.getTtlMs())
                        .setCreationTimestampMillis(proto.getCreationTimestampMs());

        for (int i = 0; i < proto.getPropertiesCount(); i++) {
            PropertyProto property = proto.getProperties(i);
            String name = property.getName();
            if (property.getBooleanValuesCount() > 0) {
                boolean[] values = new boolean[property.getBooleanValuesCount()];
                for (int j = 0; j < values.length; j++) {
                    values[j] = property.getBooleanValues(j);
                }
                documentBuilder.setProperty(name, values);
            } else if (property.getInt64ValuesCount() > 0) {
                long[] values = new long[property.getInt64ValuesCount()];
                for (int j = 0; j < values.length; j++) {
                    values[j] = property.getInt64Values(j);
                }
                documentBuilder.setProperty(name, values);
            } else if (property.getDoubleValuesCount() > 0) {
                double[] values = new double[property.getDoubleValuesCount()];
                for (int j = 0; j < values.length; j++) {
                    values[j] = property.getDoubleValues(j);
                }
                documentBuilder.setProperty(name, values);
            } else if (property.getStringValuesCount() > 0) {
                String[] values = new String[property.getStringValuesCount()];
                for (int j = 0; j < values.length; j++) {
                    values[j] = property.getStringValues(j);
                }
                documentBuilder.setProperty(name, values);
            } else if (property.getBytesValuesCount() > 0) {
                byte[][] values = new byte[property.getBytesValuesCount()][];
                for (int j = 0; j < values.length; j++) {
                    values[j] = property.getBytesValues(j).toByteArray();
                }
                documentBuilder.setProperty(name, values);
            } else if (property.getDocumentValuesCount() > 0) {
                GenericDocument[] values = new GenericDocument[property.getDocumentValuesCount()];
                for (int j = 0; j < values.length; j++) {
                    values[j] = convert(property.getDocumentValues(j));
                }
                documentBuilder.setProperty(name, values);
            } else {
                throw new IllegalStateException("Unknown type of value: " + name);
            }
        }
        return documentBuilder.build();
    }
}
