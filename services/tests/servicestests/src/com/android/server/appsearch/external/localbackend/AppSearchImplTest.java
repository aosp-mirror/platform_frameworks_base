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

package com.android.server.appsearch.external.localbackend;

import static com.google.common.truth.Truth.assertThat;

import static org.testng.Assert.expectThrows;

import android.app.appsearch.exceptions.AppSearchException;

import com.android.server.appsearch.proto.DocumentProto;
import com.android.server.appsearch.proto.GetOptimizeInfoResultProto;
import com.android.server.appsearch.proto.IndexingConfig;
import com.android.server.appsearch.proto.PropertyConfigProto;
import com.android.server.appsearch.proto.PropertyProto;
import com.android.server.appsearch.proto.ResultSpecProto;
import com.android.server.appsearch.proto.SchemaProto;
import com.android.server.appsearch.proto.SchemaTypeConfigProto;
import com.android.server.appsearch.proto.ScoringSpecProto;
import com.android.server.appsearch.proto.SearchResultProto;
import com.android.server.appsearch.proto.SearchSpecProto;
import com.android.server.appsearch.proto.StatusProto;
import com.android.server.appsearch.proto.TermMatchType;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.util.Set;

public class AppSearchImplTest {
    @Rule
    public TemporaryFolder mTemporaryFolder = new TemporaryFolder();
    private AppSearchImpl mAppSearchImpl;

    @Before
    public void setUp() throws Exception {
        mAppSearchImpl = AppSearchImpl.create(mTemporaryFolder.newFolder());
    }

    /**
     * Ensure that we can rewrite an incoming schema type by adding the database as a prefix. While
     * also keeping any other existing schema types that may already be part of Icing's persisted
     * schema.
     */
    @Test
    public void testRewriteSchema() throws Exception {
        SchemaProto.Builder existingSchemaBuilder = mAppSearchImpl.getSchemaProto().toBuilder();

        SchemaProto newSchema = SchemaProto.newBuilder()
                .addTypes(SchemaTypeConfigProto.newBuilder()
                        .setSchemaType("Foo").build())
                .addTypes(SchemaTypeConfigProto.newBuilder()
                        .setSchemaType("TestType")
                        .addProperties(PropertyConfigProto.newBuilder()
                                .setPropertyName("subject")
                                .setDataType(PropertyConfigProto.DataType.Code.STRING)
                                .setCardinality(PropertyConfigProto.Cardinality.Code.OPTIONAL)
                                .setIndexingConfig(
                                        IndexingConfig.newBuilder()
                                                .setTokenizerType(
                                                        IndexingConfig.TokenizerType.Code.PLAIN)
                                                .setTermMatchType(TermMatchType.Code.PREFIX)
                                                .build()
                                ).build()
                        ).addProperties(PropertyConfigProto.newBuilder()
                                .setPropertyName("link")
                                .setDataType(PropertyConfigProto.DataType.Code.DOCUMENT)
                                .setCardinality(PropertyConfigProto.Cardinality.Code.OPTIONAL)
                                .setSchemaType("RefType")
                                .build()
                        ).build()
                ).build();

        Set<String> newTypes = mAppSearchImpl.rewriteSchema("databaseName", existingSchemaBuilder,
                newSchema);
        assertThat(newTypes).containsExactly("databaseName/Foo", "databaseName/TestType");

        SchemaProto expectedSchema = SchemaProto.newBuilder()
                .addTypes(SchemaTypeConfigProto.newBuilder()
                    .setSchemaType("databaseName/Foo").build())
                .addTypes(SchemaTypeConfigProto.newBuilder()
                        .setSchemaType("databaseName/TestType")
                        .addProperties(PropertyConfigProto.newBuilder()
                                .setPropertyName("subject")
                                .setDataType(PropertyConfigProto.DataType.Code.STRING)
                                .setCardinality(PropertyConfigProto.Cardinality.Code.OPTIONAL)
                                .setIndexingConfig(
                                        IndexingConfig.newBuilder()
                                                .setTokenizerType(
                                                        IndexingConfig.TokenizerType.Code.PLAIN)
                                                .setTermMatchType(TermMatchType.Code.PREFIX)
                                                .build()
                                ).build()
                        ).addProperties(PropertyConfigProto.newBuilder()
                                .setPropertyName("link")
                                .setDataType(PropertyConfigProto.DataType.Code.DOCUMENT)
                                .setCardinality(PropertyConfigProto.Cardinality.Code.OPTIONAL)
                                .setSchemaType("databaseName/RefType")
                                .build()
                        ).build())
                .build();
        assertThat(existingSchemaBuilder.getTypesList())
                .containsExactlyElementsIn(expectedSchema.getTypesList());
    }

    @Test
    public void testRewriteDocumentProto() {
        DocumentProto insideDocument = DocumentProto.newBuilder()
                .setUri("inside-uri")
                .setSchema("type")
                .setNamespace("namespace")
                .build();
        DocumentProto documentProto = DocumentProto.newBuilder()
                .setUri("uri")
                .setSchema("type")
                .setNamespace("namespace")
                .addProperties(PropertyProto.newBuilder().addDocumentValues(insideDocument))
                .build();

        DocumentProto expectedInsideDocument = DocumentProto.newBuilder()
                .setUri("inside-uri")
                .setSchema("databaseName/type")
                .setNamespace("databaseName/namespace")
                .build();
        DocumentProto expectedDocumentProto = DocumentProto.newBuilder()
                .setUri("uri")
                .setSchema("databaseName/type")
                .setNamespace("databaseName/namespace")
                .addProperties(PropertyProto.newBuilder().addDocumentValues(expectedInsideDocument))
                .build();

        DocumentProto.Builder actualDocument = documentProto.toBuilder();
        mAppSearchImpl.rewriteDocumentTypes("databaseName/", actualDocument, /*add=*/true);
        assertThat(actualDocument.build()).isEqualTo(expectedDocumentProto);
        mAppSearchImpl.rewriteDocumentTypes("databaseName/", actualDocument, /*add=*/false);
        assertThat(actualDocument.build()).isEqualTo(documentProto);
    }

    @Test
    public void testOptimize() throws Exception {
        // Insert schema
        SchemaProto schema = SchemaProto.newBuilder()
                .addTypes(SchemaTypeConfigProto.newBuilder()
                        .setSchemaType("type").build())
                .build();
        mAppSearchImpl.setSchema("database", schema, /*forceOverride=*/false);

        // Insert enough documents.
        for (int i = 0; i < AppSearchImpl.OPTIMIZE_THRESHOLD_DOC_COUNT
                + AppSearchImpl.CHECK_OPTIMIZE_INTERVAL; i++) {
            DocumentProto insideDocument = DocumentProto.newBuilder()
                    .setUri("inside-uri" + i)
                    .setSchema("type")
                    .setNamespace("namespace")
                    .build();
            mAppSearchImpl.putDocument("database", insideDocument);
        }

        // Check optimize() will release 0 docs since there is no deletion.
        GetOptimizeInfoResultProto optimizeInfo = mAppSearchImpl.getOptimizeInfoResult();
        assertThat(optimizeInfo.getOptimizableDocs()).isEqualTo(0);

        // delete 999 documents , we will reach the threshold to trigger optimize() in next
        // deletion.
        for (int i = 0; i < AppSearchImpl.OPTIMIZE_THRESHOLD_DOC_COUNT - 1; i++) {
            mAppSearchImpl.remove("database", "namespace", "inside-uri" + i);
        }

        // optimize() still not be triggered since we are in the interval to call getOptimizeInfo()
        optimizeInfo = mAppSearchImpl.getOptimizeInfoResult();
        assertThat(optimizeInfo.getOptimizableDocs())
                .isEqualTo(AppSearchImpl.OPTIMIZE_THRESHOLD_DOC_COUNT - 1);

        // Keep delete docs, will reach the interval this time and trigger optimize().
        for (int i = AppSearchImpl.OPTIMIZE_THRESHOLD_DOC_COUNT;
                i < AppSearchImpl.OPTIMIZE_THRESHOLD_DOC_COUNT
                        + AppSearchImpl.CHECK_OPTIMIZE_INTERVAL; i++) {
            mAppSearchImpl.remove("database", "namespace", "inside-uri" + i);
        }

        // Verify optimize() is triggered
        optimizeInfo = mAppSearchImpl.getOptimizeInfoResult();
        assertThat(optimizeInfo.getOptimizableDocs())
                .isLessThan((long) AppSearchImpl.CHECK_OPTIMIZE_INTERVAL);
    }

    @Test
    public void testRewriteSearchSpec() throws Exception {
        SearchSpecProto.Builder searchSpecProto =
                SearchSpecProto.newBuilder().setQuery("");

        // Insert schema
        SchemaProto schema = SchemaProto.newBuilder()
                .addTypes(SchemaTypeConfigProto.newBuilder()
                        .setSchemaType("type").build())
                .build();
        mAppSearchImpl.setSchema("database", schema, /*forceOverride=*/false);
        // Insert document
        DocumentProto insideDocument = DocumentProto.newBuilder()
                .setUri("inside-uri")
                .setSchema("type")
                .setNamespace("namespace")
                .build();
        mAppSearchImpl.putDocument("database", insideDocument);

        // Rewrite SearchSpec
        mAppSearchImpl.rewriteSearchSpecForNonEmptyDatabase(
                "database", searchSpecProto);
        assertThat(searchSpecProto.getSchemaTypeFiltersList()).containsExactly("database/type");
        assertThat(searchSpecProto.getNamespaceFiltersList()).containsExactly("database/namespace");
    }

    @Test
    public void testQueryEmptyDatabase() throws Exception {
        SearchResultProto searchResultProto = mAppSearchImpl.query("EmptyDatabase",
                SearchSpecProto.getDefaultInstance(),
                ResultSpecProto.getDefaultInstance(), ScoringSpecProto.getDefaultInstance());
        assertThat(searchResultProto.getResultsCount()).isEqualTo(0);
        assertThat(searchResultProto.getStatus().getCode()).isEqualTo(StatusProto.Code.OK);
    }

    @Test
    public void testRemoveEmptyDatabase_NoExceptionThrown() throws Exception {
        mAppSearchImpl.removeByType("EmptyDatabase", "FakeType");
        mAppSearchImpl.removeByNamespace("EmptyDatabase", "FakeNamespace");
        mAppSearchImpl.removeAll("EmptyDatabase");
    }

    @Test
    public void testSetSchema() throws Exception {
        // Create schemas
        SchemaProto schemaProto = SchemaProto.newBuilder()
                .addTypes(SchemaTypeConfigProto.newBuilder().setSchemaType("Email")).build();

        // Set schema Email to AppSearch database1
        mAppSearchImpl.setSchema("database1", schemaProto, /*forceOverride=*/false);

        // Create excepted schemaType proto.
        SchemaProto exceptedProto = SchemaProto.newBuilder()
                .addTypes(SchemaTypeConfigProto.newBuilder().setSchemaType("database1/Email"))
                .build();
        assertThat(mAppSearchImpl.getSchemaProto().getTypesList())
                .containsExactlyElementsIn(exceptedProto.getTypesList());
    }

    @Test
    public void testRemoveSchema() throws Exception {
        // Create schemas
        SchemaProto schemaProto = SchemaProto.newBuilder()
                .addTypes(SchemaTypeConfigProto.newBuilder().setSchemaType("Email"))
                .addTypes(SchemaTypeConfigProto.newBuilder().setSchemaType("Document")).build();

        // Set schema Email and Document to AppSearch database1
        mAppSearchImpl.setSchema("database1", schemaProto, /*forceOverride=*/false);

        // Create excepted schemaType proto.
        SchemaProto exceptedProto = SchemaProto.newBuilder()
                .addTypes(SchemaTypeConfigProto.newBuilder().setSchemaType("database1/Email"))
                .addTypes(SchemaTypeConfigProto.newBuilder().setSchemaType("database1/Document"))
                .build();

        // Check both schema Email and Document saved correctly.
        assertThat(mAppSearchImpl.getSchemaProto().getTypesList())
                .containsExactlyElementsIn(exceptedProto.getTypesList());

        // Save only Email this time.
        schemaProto = SchemaProto.newBuilder()
                .addTypes(SchemaTypeConfigProto.newBuilder().setSchemaType("Email")).build();

        // Check the incompatible error has been thrown.
        SchemaProto finalSchemaProto = schemaProto;
        AppSearchException e = expectThrows(AppSearchException.class, () ->
                mAppSearchImpl.setSchema("database1", finalSchemaProto, /*forceOverride=*/false));
        assertThat(e).hasMessageThat().contains("Schema is incompatible");
        assertThat(e).hasMessageThat().contains("Deleted types: [database1/Document]");

        // ForceOverride to delete.
        mAppSearchImpl.setSchema("database1", finalSchemaProto, /*forceOverride=*/true);

        // Check Document schema is removed.
        exceptedProto = SchemaProto.newBuilder()
                .addTypes(SchemaTypeConfigProto.newBuilder().setSchemaType("database1/Email"))
                .build();
        assertThat(mAppSearchImpl.getSchemaProto().getTypesList())
                .containsExactlyElementsIn(exceptedProto.getTypesList());
    }

    @Test
    public void testRemoveSchema_differentDataBase() throws Exception {
        // Create schemas
        SchemaProto emailAndDocSchemaProto = SchemaProto.newBuilder()
                .addTypes(SchemaTypeConfigProto.newBuilder().setSchemaType("Email"))
                .addTypes(SchemaTypeConfigProto.newBuilder().setSchemaType("Document")).build();

        // Set schema Email and Document to AppSearch database1 and 2
        mAppSearchImpl.setSchema("database1", emailAndDocSchemaProto, /*forceOverride=*/false);
        mAppSearchImpl.setSchema("database2", emailAndDocSchemaProto, /*forceOverride=*/false);

        // Create excepted schemaType proto.
        SchemaProto exceptedProto = SchemaProto.newBuilder()
                .addTypes(SchemaTypeConfigProto.newBuilder().setSchemaType("database1/Email"))
                .addTypes(SchemaTypeConfigProto.newBuilder().setSchemaType("database1/Document"))
                .addTypes(SchemaTypeConfigProto.newBuilder().setSchemaType("database2/Email"))
                .addTypes(SchemaTypeConfigProto.newBuilder().setSchemaType("database2/Document"))
                .build();

        // Check Email and Document is saved in database 1 and 2 correctly.
        assertThat(mAppSearchImpl.getSchemaProto().getTypesList())
                .containsExactlyElementsIn(exceptedProto.getTypesList());

        // Save only Email to database1 this time.
        SchemaProto emailSchemaProto = SchemaProto.newBuilder()
                        .addTypes(SchemaTypeConfigProto.newBuilder().setSchemaType("Email"))
                .build();
        mAppSearchImpl.setSchema("database1", emailSchemaProto, /*forceOverride=*/true);

        // Create excepted schemaType list, database 1 should only contain Email but database 2
        // remains in same.
        exceptedProto = SchemaProto.newBuilder()
                .addTypes(SchemaTypeConfigProto.newBuilder().setSchemaType("database1/Email"))
                .addTypes(SchemaTypeConfigProto.newBuilder().setSchemaType("database2/Email"))
                .addTypes(SchemaTypeConfigProto.newBuilder().setSchemaType("database2/Document"))
                .build();

        // Check nothing changed in database2.
        assertThat(mAppSearchImpl.getSchemaProto().getTypesList())
                .containsExactlyElementsIn(exceptedProto.getTypesList());
    }
}
