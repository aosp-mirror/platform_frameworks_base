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

import android.app.appsearch.AppSearchSchema;

import com.android.server.appsearch.proto.PropertyConfigProto;
import com.android.server.appsearch.proto.SchemaTypeConfigProto;
import com.android.server.appsearch.proto.StringIndexingConfig;
import com.android.server.appsearch.proto.TermMatchType;

import org.junit.Test;

public class SchemaToProtoConverterTest {
    @Test
    public void testGetProto_Email() {
        AppSearchSchema emailSchema = new AppSearchSchema.Builder("Email")
                .addProperty(new AppSearchSchema.PropertyConfig.Builder("subject")
                        .setDataType(AppSearchSchema.PropertyConfig.DATA_TYPE_STRING)
                        .setCardinality(AppSearchSchema.PropertyConfig.CARDINALITY_OPTIONAL)
                        .setIndexingType(AppSearchSchema.PropertyConfig.INDEXING_TYPE_PREFIXES)
                        .setTokenizerType(AppSearchSchema.PropertyConfig.TOKENIZER_TYPE_PLAIN)
                        .build()
                ).addProperty(new AppSearchSchema.PropertyConfig.Builder("body")
                        .setDataType(AppSearchSchema.PropertyConfig.DATA_TYPE_STRING)
                        .setCardinality(AppSearchSchema.PropertyConfig.CARDINALITY_OPTIONAL)
                        .setIndexingType(AppSearchSchema.PropertyConfig.INDEXING_TYPE_PREFIXES)
                        .setTokenizerType(AppSearchSchema.PropertyConfig.TOKENIZER_TYPE_PLAIN)
                        .build()
                ).build();

        SchemaTypeConfigProto expectedEmailProto = SchemaTypeConfigProto.newBuilder()
                .setSchemaType("Email")
                .addProperties(PropertyConfigProto.newBuilder()
                        .setPropertyName("subject")
                        .setDataType(PropertyConfigProto.DataType.Code.STRING)
                        .setCardinality(PropertyConfigProto.Cardinality.Code.OPTIONAL)
                        .setStringIndexingConfig(
                                StringIndexingConfig.newBuilder()
                                        .setTokenizerType(
                                                StringIndexingConfig.TokenizerType.Code.PLAIN)
                                        .setTermMatchType(TermMatchType.Code.PREFIX)
                        )
                ).addProperties(PropertyConfigProto.newBuilder()
                        .setPropertyName("body")
                        .setDataType(PropertyConfigProto.DataType.Code.STRING)
                        .setCardinality(PropertyConfigProto.Cardinality.Code.OPTIONAL)
                        .setStringIndexingConfig(
                                StringIndexingConfig.newBuilder()
                                        .setTokenizerType(
                                                StringIndexingConfig.TokenizerType.Code.PLAIN)
                                        .setTermMatchType(TermMatchType.Code.PREFIX)
                        )
                ).build();

        assertThat(SchemaToProtoConverter.convert(emailSchema)).isEqualTo(expectedEmailProto);
    }

    @Test
    public void testGetProto_MusicRecording() {
        AppSearchSchema musicRecordingSchema = new AppSearchSchema.Builder("MusicRecording")
                .addProperty(new AppSearchSchema.PropertyConfig.Builder("artist")
                        .setDataType(AppSearchSchema.PropertyConfig.DATA_TYPE_STRING)
                        .setCardinality(AppSearchSchema.PropertyConfig.CARDINALITY_REPEATED)
                        .setIndexingType(AppSearchSchema.PropertyConfig.INDEXING_TYPE_PREFIXES)
                        .setTokenizerType(AppSearchSchema.PropertyConfig.TOKENIZER_TYPE_PLAIN)
                        .build()
                ).addProperty(new AppSearchSchema.PropertyConfig.Builder("pubDate")
                        .setDataType(AppSearchSchema.PropertyConfig.DATA_TYPE_INT64)
                        .setCardinality(AppSearchSchema.PropertyConfig.CARDINALITY_OPTIONAL)
                        .setIndexingType(AppSearchSchema.PropertyConfig.INDEXING_TYPE_NONE)
                        .setTokenizerType(AppSearchSchema.PropertyConfig.TOKENIZER_TYPE_NONE)
                        .build()
                ).build();

        SchemaTypeConfigProto expectedMusicRecordingProto = SchemaTypeConfigProto.newBuilder()
                .setSchemaType("MusicRecording")
                .addProperties(PropertyConfigProto.newBuilder()
                        .setPropertyName("artist")
                        .setDataType(PropertyConfigProto.DataType.Code.STRING)
                        .setCardinality(PropertyConfigProto.Cardinality.Code.REPEATED)
                        .setStringIndexingConfig(
                                StringIndexingConfig.newBuilder()
                                        .setTokenizerType(
                                                StringIndexingConfig.TokenizerType.Code.PLAIN)
                                        .setTermMatchType(TermMatchType.Code.PREFIX)
                        )
                ).addProperties(PropertyConfigProto.newBuilder()
                        .setPropertyName("pubDate")
                        .setDataType(PropertyConfigProto.DataType.Code.INT64)
                        .setCardinality(PropertyConfigProto.Cardinality.Code.OPTIONAL)
                        .setStringIndexingConfig(
                                StringIndexingConfig.newBuilder()
                                        .setTokenizerType(
                                                StringIndexingConfig.TokenizerType.Code.NONE)
                                        .setTermMatchType(TermMatchType.Code.UNKNOWN)
                        )
                ).build();

        assertThat(SchemaToProtoConverter.convert(musicRecordingSchema))
                .isEqualTo(expectedMusicRecordingProto);
    }
}
