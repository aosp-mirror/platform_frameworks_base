/*
 * Copyright (C) 2019 The Android Open Source Project
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

package android.app.appsearch;

import static com.google.common.truth.Truth.assertThat;

import static org.testng.Assert.assertThrows;
import static org.testng.Assert.expectThrows;

import android.app.appsearch.AppSearchSchema.IndexingConfig;
import android.app.appsearch.AppSearchSchema.PropertyConfig;

import androidx.test.filters.SmallTest;

import com.google.android.icing.proto.IndexingConfig.TokenizerType;
import com.google.android.icing.proto.PropertyConfigProto;
import com.google.android.icing.proto.SchemaProto;
import com.google.android.icing.proto.SchemaTypeConfigProto;
import com.google.android.icing.proto.TermMatchType;

import org.junit.Test;

@SmallTest
public class AppSearchSchemaTest {
    @Test
    public void testSuccess() {
        AppSearchSchema schema = AppSearchSchema.newBuilder()
                .addType(AppSearchSchema.newSchemaTypeBuilder("Email")
                        .addProperty(AppSearchSchema.newPropertyBuilder("subject")
                                .setDataType(PropertyConfig.DATA_TYPE_STRING)
                                .setCardinality(PropertyConfig.CARDINALITY_OPTIONAL)
                                .setIndexingConfig(AppSearchSchema.newIndexingConfigBuilder()
                                        .setTokenizerType(IndexingConfig.TOKENIZER_TYPE_PLAIN)
                                        .setTermMatchType(IndexingConfig.TERM_MATCH_TYPE_PREFIX)
                                        .build()
                                ).build()
                        ).addProperty(AppSearchSchema.newPropertyBuilder("body")
                                .setDataType(PropertyConfig.DATA_TYPE_STRING)
                                .setCardinality(PropertyConfig.CARDINALITY_OPTIONAL)
                                .setIndexingConfig(AppSearchSchema.newIndexingConfigBuilder()
                                        .setTokenizerType(IndexingConfig.TOKENIZER_TYPE_PLAIN)
                                        .setTermMatchType(IndexingConfig.TERM_MATCH_TYPE_PREFIX)
                                        .build()
                                ).build()
                        ).build()

                ).addType(AppSearchSchema.newSchemaTypeBuilder("MusicRecording")
                        .addProperty(AppSearchSchema.newPropertyBuilder("artist")
                                .setDataType(PropertyConfig.DATA_TYPE_STRING)
                                .setCardinality(PropertyConfig.CARDINALITY_REPEATED)
                                .setIndexingConfig(AppSearchSchema.newIndexingConfigBuilder()
                                        .setTokenizerType(IndexingConfig.TOKENIZER_TYPE_PLAIN)
                                        .setTermMatchType(IndexingConfig.TERM_MATCH_TYPE_PREFIX)
                                        .build()
                                ).build()
                        ).addProperty(AppSearchSchema.newPropertyBuilder("pubDate")
                                .setDataType(PropertyConfig.DATA_TYPE_INT64)
                                .setCardinality(PropertyConfig.CARDINALITY_OPTIONAL)
                                .setIndexingConfig(AppSearchSchema.newIndexingConfigBuilder()
                                        .setTokenizerType(IndexingConfig.TOKENIZER_TYPE_NONE)
                                        .setTermMatchType(IndexingConfig.TERM_MATCH_TYPE_UNKNOWN)
                                        .build()
                                ).build()
                        ).build()
                ).build();

        SchemaProto expectedProto = SchemaProto.newBuilder()
                .addTypes(SchemaTypeConfigProto.newBuilder()
                        .setSchemaType("Email")
                        .addProperties(PropertyConfigProto.newBuilder()
                                .setPropertyName("subject")
                                .setDataType(PropertyConfigProto.DataType.Code.STRING)
                                .setCardinality(PropertyConfigProto.Cardinality.Code.OPTIONAL)
                                .setIndexingConfig(
                                        com.google.android.icing.proto.IndexingConfig.newBuilder()
                                                .setTokenizerType(TokenizerType.Code.PLAIN)
                                                .setTermMatchType(TermMatchType.Code.PREFIX)
                                )
                        ).addProperties(PropertyConfigProto.newBuilder()
                                .setPropertyName("body")
                                .setDataType(PropertyConfigProto.DataType.Code.STRING)
                                .setCardinality(PropertyConfigProto.Cardinality.Code.OPTIONAL)
                                .setIndexingConfig(
                                        com.google.android.icing.proto.IndexingConfig.newBuilder()
                                                .setTokenizerType(TokenizerType.Code.PLAIN)
                                                .setTermMatchType(TermMatchType.Code.PREFIX)
                                )
                        )

                ).addTypes(SchemaTypeConfigProto.newBuilder()
                        .setSchemaType("MusicRecording")
                        .addProperties(PropertyConfigProto.newBuilder()
                                .setPropertyName("artist")
                                .setDataType(PropertyConfigProto.DataType.Code.STRING)
                                .setCardinality(PropertyConfigProto.Cardinality.Code.REPEATED)
                                .setIndexingConfig(
                                        com.google.android.icing.proto.IndexingConfig.newBuilder()
                                                .setTokenizerType(TokenizerType.Code.PLAIN)
                                                .setTermMatchType(TermMatchType.Code.PREFIX)
                                )
                        ).addProperties(PropertyConfigProto.newBuilder()
                                .setPropertyName("pubDate")
                                .setDataType(PropertyConfigProto.DataType.Code.INT64)
                                .setCardinality(PropertyConfigProto.Cardinality.Code.OPTIONAL)
                                .setIndexingConfig(
                                        com.google.android.icing.proto.IndexingConfig.newBuilder()
                                                .setTokenizerType(TokenizerType.Code.NONE)
                                                .setTermMatchType(TermMatchType.Code.UNKNOWN)
                                )
                        )
                ).build();

        assertThat(schema.getProto()).isEqualTo(expectedProto);
    }

    @Test
    public void testInvalidEnums() {
        PropertyConfig.Builder builder = AppSearchSchema.newPropertyBuilder("test");
        assertThrows(IllegalArgumentException.class, () -> builder.setDataType(99));
        assertThrows(IllegalArgumentException.class, () -> builder.setCardinality(99));
    }

    @Test
    public void testMissingFields() {
        PropertyConfig.Builder builder = AppSearchSchema.newPropertyBuilder("test");
        Exception e = expectThrows(IllegalSchemaException.class, builder::build);
        assertThat(e).hasMessageThat().contains("Missing field: dataType");

        builder.setDataType(PropertyConfig.DATA_TYPE_DOCUMENT);
        e = expectThrows(IllegalSchemaException.class, builder::build);
        assertThat(e).hasMessageThat().contains("Missing field: schemaType");

        builder.setSchemaType("TestType");
        e = expectThrows(IllegalSchemaException.class, builder::build);
        assertThat(e).hasMessageThat().contains("Missing field: cardinality");

        builder.setCardinality(PropertyConfig.CARDINALITY_REPEATED);
        builder.build();
    }
}
