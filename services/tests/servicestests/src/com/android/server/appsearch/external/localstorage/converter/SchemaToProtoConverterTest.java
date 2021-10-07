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

import com.android.server.appsearch.icing.proto.PropertyConfigProto;
import com.android.server.appsearch.icing.proto.SchemaTypeConfigProto;
import com.android.server.appsearch.icing.proto.StringIndexingConfig;
import com.android.server.appsearch.icing.proto.TermMatchType;

import org.junit.Test;

public class SchemaToProtoConverterTest {
    @Test
    public void testGetProto_Email() {
        AppSearchSchema emailSchema =
                new AppSearchSchema.Builder("Email")
                        .addProperty(
                                new AppSearchSchema.StringPropertyConfig.Builder("subject")
                                        .setCardinality(
                                                AppSearchSchema.PropertyConfig.CARDINALITY_OPTIONAL)
                                        .setIndexingType(
                                                AppSearchSchema.StringPropertyConfig
                                                        .INDEXING_TYPE_PREFIXES)
                                        .setTokenizerType(
                                                AppSearchSchema.StringPropertyConfig
                                                        .TOKENIZER_TYPE_PLAIN)
                                        .build())
                        .addProperty(
                                new AppSearchSchema.StringPropertyConfig.Builder("body")
                                        .setCardinality(
                                                AppSearchSchema.PropertyConfig.CARDINALITY_OPTIONAL)
                                        .setIndexingType(
                                                AppSearchSchema.StringPropertyConfig
                                                        .INDEXING_TYPE_PREFIXES)
                                        .setTokenizerType(
                                                AppSearchSchema.StringPropertyConfig
                                                        .TOKENIZER_TYPE_PLAIN)
                                        .build())
                        .build();

        SchemaTypeConfigProto expectedEmailProto =
                SchemaTypeConfigProto.newBuilder()
                        .setSchemaType("Email")
                        .setVersion(12345)
                        .addProperties(
                                PropertyConfigProto.newBuilder()
                                        .setPropertyName("subject")
                                        .setDataType(PropertyConfigProto.DataType.Code.STRING)
                                        .setCardinality(
                                                PropertyConfigProto.Cardinality.Code.OPTIONAL)
                                        .setStringIndexingConfig(
                                                StringIndexingConfig.newBuilder()
                                                        .setTokenizerType(
                                                                StringIndexingConfig.TokenizerType
                                                                        .Code.PLAIN)
                                                        .setTermMatchType(
                                                                TermMatchType.Code.PREFIX)))
                        .addProperties(
                                PropertyConfigProto.newBuilder()
                                        .setPropertyName("body")
                                        .setDataType(PropertyConfigProto.DataType.Code.STRING)
                                        .setCardinality(
                                                PropertyConfigProto.Cardinality.Code.OPTIONAL)
                                        .setStringIndexingConfig(
                                                StringIndexingConfig.newBuilder()
                                                        .setTokenizerType(
                                                                StringIndexingConfig.TokenizerType
                                                                        .Code.PLAIN)
                                                        .setTermMatchType(
                                                                TermMatchType.Code.PREFIX)))
                        .build();

        assertThat(SchemaToProtoConverter.toSchemaTypeConfigProto(emailSchema, /*version=*/ 12345))
                .isEqualTo(expectedEmailProto);
        assertThat(SchemaToProtoConverter.toAppSearchSchema(expectedEmailProto))
                .isEqualTo(emailSchema);
    }

    @Test
    public void testGetProto_MusicRecording() {
        AppSearchSchema musicRecordingSchema =
                new AppSearchSchema.Builder("MusicRecording")
                        .addProperty(
                                new AppSearchSchema.StringPropertyConfig.Builder("artist")
                                        .setCardinality(
                                                AppSearchSchema.PropertyConfig.CARDINALITY_REPEATED)
                                        .setIndexingType(
                                                AppSearchSchema.StringPropertyConfig
                                                        .INDEXING_TYPE_PREFIXES)
                                        .setTokenizerType(
                                                AppSearchSchema.StringPropertyConfig
                                                        .TOKENIZER_TYPE_PLAIN)
                                        .build())
                        .addProperty(
                                new AppSearchSchema.LongPropertyConfig.Builder("pubDate")
                                        .setCardinality(
                                                AppSearchSchema.PropertyConfig.CARDINALITY_OPTIONAL)
                                        .build())
                        .build();

        SchemaTypeConfigProto expectedMusicRecordingProto =
                SchemaTypeConfigProto.newBuilder()
                        .setSchemaType("MusicRecording")
                        .setVersion(0)
                        .addProperties(
                                PropertyConfigProto.newBuilder()
                                        .setPropertyName("artist")
                                        .setDataType(PropertyConfigProto.DataType.Code.STRING)
                                        .setCardinality(
                                                PropertyConfigProto.Cardinality.Code.REPEATED)
                                        .setStringIndexingConfig(
                                                StringIndexingConfig.newBuilder()
                                                        .setTokenizerType(
                                                                StringIndexingConfig.TokenizerType
                                                                        .Code.PLAIN)
                                                        .setTermMatchType(
                                                                TermMatchType.Code.PREFIX)))
                        .addProperties(
                                PropertyConfigProto.newBuilder()
                                        .setPropertyName("pubDate")
                                        .setDataType(PropertyConfigProto.DataType.Code.INT64)
                                        .setCardinality(
                                                PropertyConfigProto.Cardinality.Code.OPTIONAL))
                        .build();

        assertThat(
                        SchemaToProtoConverter.toSchemaTypeConfigProto(
                                musicRecordingSchema, /*version=*/ 0))
                .isEqualTo(expectedMusicRecordingProto);
        assertThat(SchemaToProtoConverter.toAppSearchSchema(expectedMusicRecordingProto))
                .isEqualTo(musicRecordingSchema);
    }
}
