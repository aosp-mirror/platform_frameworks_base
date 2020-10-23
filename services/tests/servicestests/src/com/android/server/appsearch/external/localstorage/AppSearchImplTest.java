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

package com.android.server.appsearch.external.localstorage;

import static com.google.common.truth.Truth.assertThat;

import static org.testng.Assert.expectThrows;

import android.app.appsearch.AppSearchSchema;
import android.app.appsearch.GenericDocument;
import android.app.appsearch.SearchResultPage;
import android.app.appsearch.SearchSpec;
import android.app.appsearch.exceptions.AppSearchException;

import com.android.server.appsearch.proto.DocumentProto;
import com.android.server.appsearch.proto.GetOptimizeInfoResultProto;
import com.android.server.appsearch.proto.IndexingConfig;
import com.android.server.appsearch.proto.PropertyConfigProto;
import com.android.server.appsearch.proto.PropertyProto;
import com.android.server.appsearch.proto.SchemaProto;
import com.android.server.appsearch.proto.SchemaTypeConfigProto;
import com.android.server.appsearch.proto.SearchSpecProto;
import com.android.server.appsearch.proto.TermMatchType;
import com.google.common.collect.ImmutableSet;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.util.Collections;
import java.util.HashSet;
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
    public void testAddDocumentTypePrefix() {
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
        mAppSearchImpl.addPrefixToDocument(actualDocument, "databaseName/");
        assertThat(actualDocument.build()).isEqualTo(expectedDocumentProto);
    }

    @Test
    public void testRemoveDocumentTypePrefixes() {
        DocumentProto insideDocument = DocumentProto.newBuilder()
                .setUri("inside-uri")
                .setSchema("databaseName1/type")
                .setNamespace("databaseName2/namespace")
                .build();
        DocumentProto documentProto = DocumentProto.newBuilder()
                .setUri("uri")
                .setSchema("databaseName2/type")
                .setNamespace("databaseName3/namespace")
                .addProperties(PropertyProto.newBuilder().addDocumentValues(insideDocument))
                .build();

        DocumentProto expectedInsideDocument = DocumentProto.newBuilder()
                .setUri("inside-uri")
                .setSchema("type")
                .setNamespace("namespace")
                .build();
        // Since we don't pass in "databaseName3/" as a prefix to remove, it stays on the Document.
        DocumentProto expectedDocumentProto = DocumentProto.newBuilder()
                .setUri("uri")
                .setSchema("type")
                .setNamespace("namespace")
                .addProperties(PropertyProto.newBuilder().addDocumentValues(expectedInsideDocument))
                .build();

        DocumentProto.Builder actualDocument = documentProto.toBuilder();
        mAppSearchImpl.removeDatabasesFromDocument(actualDocument);
        assertThat(actualDocument.build()).isEqualTo(expectedDocumentProto);
    }

    @Test
    public void testOptimize() throws Exception {
        // Insert schema
        Set<AppSearchSchema> schemas =
                Collections.singleton(new AppSearchSchema.Builder("type").build());
        mAppSearchImpl.setSchema("database", schemas, /*forceOverride=*/false);

        // Insert enough documents.
        for (int i = 0; i < AppSearchImpl.OPTIMIZE_THRESHOLD_DOC_COUNT
                + AppSearchImpl.CHECK_OPTIMIZE_INTERVAL; i++) {
            GenericDocument document =
                    new GenericDocument.Builder("uri" + i, "type").setNamespace(
                            "namespace").build();
            mAppSearchImpl.putDocument("database", document);
        }

        // Check optimize() will release 0 docs since there is no deletion.
        GetOptimizeInfoResultProto optimizeInfo = mAppSearchImpl.getOptimizeInfoResult();
        assertThat(optimizeInfo.getOptimizableDocs()).isEqualTo(0);

        // delete 999 documents , we will reach the threshold to trigger optimize() in next
        // deletion.
        for (int i = 0; i < AppSearchImpl.OPTIMIZE_THRESHOLD_DOC_COUNT - 1; i++) {
            mAppSearchImpl.remove("database", "namespace", "uri" + i);
        }

        // optimize() still not be triggered since we are in the interval to call getOptimizeInfo()
        optimizeInfo = mAppSearchImpl.getOptimizeInfoResult();
        assertThat(optimizeInfo.getOptimizableDocs())
                .isEqualTo(AppSearchImpl.OPTIMIZE_THRESHOLD_DOC_COUNT - 1);

        // Keep delete docs, will reach the interval this time and trigger optimize().
        for (int i = AppSearchImpl.OPTIMIZE_THRESHOLD_DOC_COUNT;
                i < AppSearchImpl.OPTIMIZE_THRESHOLD_DOC_COUNT
                        + AppSearchImpl.CHECK_OPTIMIZE_INTERVAL; i++) {
            mAppSearchImpl.remove("database", "namespace", "uri" + i);
        }

        // Verify optimize() is triggered
        optimizeInfo = mAppSearchImpl.getOptimizeInfoResult();
        assertThat(optimizeInfo.getOptimizableDocs())
                .isLessThan(AppSearchImpl.CHECK_OPTIMIZE_INTERVAL);
    }

    @Test
    public void testRewriteSearchSpec_OneInstance() throws Exception {
        SearchSpecProto.Builder searchSpecProto =
                SearchSpecProto.newBuilder().setQuery("");

        // Insert schema
        Set<AppSearchSchema> schemas =
                Collections.singleton(new AppSearchSchema.Builder("type").build());
        mAppSearchImpl.setSchema("database", schemas, /*forceOverride=*/false);

        // Insert document
        GenericDocument document = new GenericDocument.Builder("uri", "type").setNamespace(
                "namespace").build();
        mAppSearchImpl.putDocument("database", document);

        // Rewrite SearchSpec
        mAppSearchImpl.rewriteSearchSpecForDatabases(searchSpecProto, Collections.singleton(
                "database"));
        assertThat(searchSpecProto.getSchemaTypeFiltersList()).containsExactly("database/type");
        assertThat(searchSpecProto.getNamespaceFiltersList()).containsExactly("database/namespace");
    }

    @Test
    public void testRewriteSearchSpec_TwoInstances() throws Exception {
        SearchSpecProto.Builder searchSpecProto =
                SearchSpecProto.newBuilder().setQuery("");

        // Insert schema
        Set<AppSearchSchema> schemas = Set.of(
                new AppSearchSchema.Builder("typeA").build(),
                new AppSearchSchema.Builder("typeB").build());
        mAppSearchImpl.setSchema("database1", schemas, /*forceOverride=*/false);
        mAppSearchImpl.setSchema("database2", schemas, /*forceOverride=*/false);

        // Insert documents
        GenericDocument document1 = new GenericDocument.Builder("uri", "typeA").setNamespace(
                "namespace").build();
        mAppSearchImpl.putDocument("database1", document1);

        GenericDocument document2 = new GenericDocument.Builder("uri", "typeB").setNamespace(
                "namespace").build();
        mAppSearchImpl.putDocument("database2", document2);

        // Rewrite SearchSpec
        mAppSearchImpl.rewriteSearchSpecForDatabases(searchSpecProto,
                ImmutableSet.of("database1", "database2"));
        assertThat(searchSpecProto.getSchemaTypeFiltersList()).containsExactly(
                "database1/typeA", "database1/typeB", "database2/typeA", "database2/typeB");
        assertThat(searchSpecProto.getNamespaceFiltersList()).containsExactly(
                "database1/namespace", "database2/namespace");
    }

    @Test
    public void testQueryEmptyDatabase() throws Exception {
        SearchSpec searchSpec =
                new SearchSpec.Builder().setTermMatch(TermMatchType.Code.PREFIX_VALUE).build();
        SearchResultPage searchResultPage = mAppSearchImpl.query(
                "EmptyDatabase",
                "", searchSpec);
        assertThat(searchResultPage.getResults()).isEmpty();
    }

    @Test
    public void testGlobalQueryEmptyDatabase() throws Exception {
        SearchSpec searchSpec =
                new SearchSpec.Builder().setTermMatch(TermMatchType.Code.PREFIX_VALUE).build();
        SearchResultPage searchResultPage = mAppSearchImpl.query(
                "EmptyDatabase",
                "", searchSpec);
        assertThat(searchResultPage.getResults()).isEmpty();
    }

    @Test
    public void testRemoveEmptyDatabase_NoExceptionThrown() throws Exception {
        SearchSpec searchSpec =
                new SearchSpec.Builder().addSchema("FakeType").setTermMatch(
                        TermMatchType.Code.PREFIX_VALUE).build();
        mAppSearchImpl.removeByQuery("EmptyDatabase",
                "", searchSpec);

        searchSpec =
                new SearchSpec.Builder().addNamespace("FakeNamespace").setTermMatch(
                        TermMatchType.Code.PREFIX_VALUE).build();
        mAppSearchImpl.removeByQuery("EmptyDatabase",
                "", searchSpec);

        searchSpec = new SearchSpec.Builder().setTermMatch(TermMatchType.Code.PREFIX_VALUE).build();
        mAppSearchImpl.removeByQuery("EmptyDatabase", "", searchSpec);
    }

    @Test
    public void testSetSchema() throws Exception {
        Set<AppSearchSchema> schemas =
                Collections.singleton(new AppSearchSchema.Builder("Email").build());
        // Set schema Email to AppSearch database1
        mAppSearchImpl.setSchema("database1", schemas, /*forceOverride=*/false);

        // Create excepted schemaType proto.
        SchemaProto exceptedProto = SchemaProto.newBuilder()
                .addTypes(SchemaTypeConfigProto.newBuilder().setSchemaType("database1/Email"))
                .build();
        assertThat(mAppSearchImpl.getSchemaProto().getTypesList())
                .containsExactlyElementsIn(exceptedProto.getTypesList());
    }

    @Test
    public void testRemoveSchema() throws Exception {
        Set<AppSearchSchema> schemas = new HashSet<>();
        schemas.add(new AppSearchSchema.Builder("Email").build());
        schemas.add(new AppSearchSchema.Builder("Document").build());
        // Set schema Email and Document to AppSearch database1
        mAppSearchImpl.setSchema("database1", schemas, /*forceOverride=*/false);

        // Create excepted schemaType proto.
        SchemaProto exceptedProto = SchemaProto.newBuilder()
                .addTypes(SchemaTypeConfigProto.newBuilder().setSchemaType("database1/Email"))
                .addTypes(SchemaTypeConfigProto.newBuilder().setSchemaType("database1/Document"))
                .build();

        // Check both schema Email and Document saved correctly.
        assertThat(mAppSearchImpl.getSchemaProto().getTypesList())
                .containsExactlyElementsIn(exceptedProto.getTypesList());

        final Set<AppSearchSchema> finalSchemas = Collections.singleton(new AppSearchSchema.Builder(
                "Email").build());
        // Check the incompatible error has been thrown.
        AppSearchException e = expectThrows(AppSearchException.class, () ->
                mAppSearchImpl.setSchema("database1", finalSchemas, /*forceOverride=*/false));
        assertThat(e).hasMessageThat().contains("Schema is incompatible");
        assertThat(e).hasMessageThat().contains("Deleted types: [database1/Document]");

        // ForceOverride to delete.
        mAppSearchImpl.setSchema("database1", finalSchemas, /*forceOverride=*/true);

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
        Set<AppSearchSchema> schemas = new HashSet<>();
        schemas.add(new AppSearchSchema.Builder("Email").build());
        schemas.add(new AppSearchSchema.Builder("Document").build());

        // Set schema Email and Document to AppSearch database1 and 2
        mAppSearchImpl.setSchema("database1", schemas, /*forceOverride=*/false);
        mAppSearchImpl.setSchema("database2", schemas, /*forceOverride=*/false);

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
        schemas = Collections.singleton(new AppSearchSchema.Builder("Email").build());
        mAppSearchImpl.setSchema("database1", schemas, /*forceOverride=*/true);

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
