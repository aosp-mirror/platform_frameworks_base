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

import static com.android.server.appsearch.external.localstorage.util.PrefixUtil.addPrefixToDocument;
import static com.android.server.appsearch.external.localstorage.util.PrefixUtil.createPrefix;
import static com.android.server.appsearch.external.localstorage.util.PrefixUtil.removePrefixesFromDocument;

import static com.google.common.truth.Truth.assertThat;

import static org.testng.Assert.expectThrows;

import android.app.appsearch.AppSearchResult;
import android.app.appsearch.AppSearchSchema;
import android.app.appsearch.GenericDocument;
import android.app.appsearch.SearchResult;
import android.app.appsearch.SearchResultPage;
import android.app.appsearch.SearchSpec;
import android.app.appsearch.SetSchemaResponse;
import android.app.appsearch.StorageInfo;
import android.app.appsearch.exceptions.AppSearchException;
import android.content.Context;
import android.util.ArrayMap;
import android.util.ArraySet;

import androidx.test.core.app.ApplicationProvider;

import com.android.server.appsearch.external.localstorage.converter.GenericDocumentToProtoConverter;
import com.android.server.appsearch.external.localstorage.stats.InitializeStats;
import com.android.server.appsearch.external.localstorage.util.PrefixUtil;
import com.android.server.appsearch.proto.DocumentProto;
import com.android.server.appsearch.proto.GetOptimizeInfoResultProto;
import com.android.server.appsearch.proto.PersistType;
import com.android.server.appsearch.proto.PropertyConfigProto;
import com.android.server.appsearch.proto.PropertyProto;
import com.android.server.appsearch.proto.PutResultProto;
import com.android.server.appsearch.proto.SchemaProto;
import com.android.server.appsearch.proto.SchemaTypeConfigProto;
import com.android.server.appsearch.proto.SearchResultProto;
import com.android.server.appsearch.proto.SearchSpecProto;
import com.android.server.appsearch.proto.StatusProto;
import com.android.server.appsearch.proto.StringIndexingConfig;
import com.android.server.appsearch.proto.TermMatchType;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class AppSearchImplTest {
    @Rule public TemporaryFolder mTemporaryFolder = new TemporaryFolder();
    private AppSearchImpl mAppSearchImpl;

    @Before
    public void setUp() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();

        // Give ourselves global query permissions
        mAppSearchImpl =
                AppSearchImpl.create(
                        mTemporaryFolder.newFolder(),
                        context,
                        VisibilityStore.NO_OP_USER_ID,
                        /*globalQuerierPackage=*/ context.getPackageName(),
                        /*logger=*/ null);
    }

    /**
     * Ensure that we can rewrite an incoming schema type by adding the database as a prefix. While
     * also keeping any other existing schema types that may already be part of Icing's persisted
     * schema.
     */
    @Test
    public void testRewriteSchema_addType() throws Exception {
        SchemaProto.Builder existingSchemaBuilder =
                SchemaProto.newBuilder()
                        .addTypes(
                                SchemaTypeConfigProto.newBuilder()
                                        .setSchemaType("package$existingDatabase/Foo")
                                        .build());

        // Create a copy so we can modify it.
        List<SchemaTypeConfigProto> existingTypes =
                new ArrayList<>(existingSchemaBuilder.getTypesList());
        SchemaTypeConfigProto schemaTypeConfigProto1 =
                SchemaTypeConfigProto.newBuilder().setSchemaType("Foo").build();
        SchemaTypeConfigProto schemaTypeConfigProto2 =
                SchemaTypeConfigProto.newBuilder()
                        .setSchemaType("TestType")
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
                                                        .setTermMatchType(TermMatchType.Code.PREFIX)
                                                        .build())
                                        .build())
                        .addProperties(
                                PropertyConfigProto.newBuilder()
                                        .setPropertyName("link")
                                        .setDataType(PropertyConfigProto.DataType.Code.DOCUMENT)
                                        .setCardinality(
                                                PropertyConfigProto.Cardinality.Code.OPTIONAL)
                                        .setSchemaType("RefType")
                                        .build())
                        .build();
        SchemaTypeConfigProto schemaTypeConfigProto3 =
                SchemaTypeConfigProto.newBuilder().setSchemaType("RefType").build();
        SchemaProto newSchema =
                SchemaProto.newBuilder()
                        .addTypes(schemaTypeConfigProto1)
                        .addTypes(schemaTypeConfigProto2)
                        .addTypes(schemaTypeConfigProto3)
                        .build();

        AppSearchImpl.RewrittenSchemaResults rewrittenSchemaResults =
                mAppSearchImpl.rewriteSchema(
                        createPrefix("package", "newDatabase"), existingSchemaBuilder, newSchema);

        // We rewrote all the new types that were added. And nothing was removed.
        assertThat(rewrittenSchemaResults.mRewrittenPrefixedTypes.keySet())
                .containsExactly(
                        "package$newDatabase/Foo",
                        "package$newDatabase/TestType",
                        "package$newDatabase/RefType");
        assertThat(
                        rewrittenSchemaResults
                                .mRewrittenPrefixedTypes
                                .get("package$newDatabase/Foo")
                                .getSchemaType())
                .isEqualTo("package$newDatabase/Foo");
        assertThat(
                        rewrittenSchemaResults
                                .mRewrittenPrefixedTypes
                                .get("package$newDatabase/TestType")
                                .getSchemaType())
                .isEqualTo("package$newDatabase/TestType");
        assertThat(
                        rewrittenSchemaResults
                                .mRewrittenPrefixedTypes
                                .get("package$newDatabase/RefType")
                                .getSchemaType())
                .isEqualTo("package$newDatabase/RefType");
        assertThat(rewrittenSchemaResults.mDeletedPrefixedTypes).isEmpty();

        SchemaProto expectedSchema =
                SchemaProto.newBuilder()
                        .addTypes(
                                SchemaTypeConfigProto.newBuilder()
                                        .setSchemaType("package$newDatabase/Foo")
                                        .build())
                        .addTypes(
                                SchemaTypeConfigProto.newBuilder()
                                        .setSchemaType("package$newDatabase/TestType")
                                        .addProperties(
                                                PropertyConfigProto.newBuilder()
                                                        .setPropertyName("subject")
                                                        .setDataType(
                                                                PropertyConfigProto.DataType.Code
                                                                        .STRING)
                                                        .setCardinality(
                                                                PropertyConfigProto.Cardinality.Code
                                                                        .OPTIONAL)
                                                        .setStringIndexingConfig(
                                                                StringIndexingConfig.newBuilder()
                                                                        .setTokenizerType(
                                                                                StringIndexingConfig
                                                                                        .TokenizerType
                                                                                        .Code.PLAIN)
                                                                        .setTermMatchType(
                                                                                TermMatchType.Code
                                                                                        .PREFIX)
                                                                        .build())
                                                        .build())
                                        .addProperties(
                                                PropertyConfigProto.newBuilder()
                                                        .setPropertyName("link")
                                                        .setDataType(
                                                                PropertyConfigProto.DataType.Code
                                                                        .DOCUMENT)
                                                        .setCardinality(
                                                                PropertyConfigProto.Cardinality.Code
                                                                        .OPTIONAL)
                                                        .setSchemaType(
                                                                "package$newDatabase/RefType")
                                                        .build())
                                        .build())
                        .addTypes(
                                SchemaTypeConfigProto.newBuilder()
                                        .setSchemaType("package$newDatabase/RefType")
                                        .build())
                        .build();

        existingTypes.addAll(expectedSchema.getTypesList());
        assertThat(existingSchemaBuilder.getTypesList()).containsExactlyElementsIn(existingTypes);
    }

    /**
     * Ensure that we track all types that were rewritten in the input schema. Even if they were not
     * technically "added" to the existing schema.
     */
    @Test
    public void testRewriteSchema_rewriteType() throws Exception {
        SchemaProto.Builder existingSchemaBuilder =
                SchemaProto.newBuilder()
                        .addTypes(
                                SchemaTypeConfigProto.newBuilder()
                                        .setSchemaType("package$existingDatabase/Foo")
                                        .build());

        SchemaProto newSchema =
                SchemaProto.newBuilder()
                        .addTypes(SchemaTypeConfigProto.newBuilder().setSchemaType("Foo").build())
                        .build();

        AppSearchImpl.RewrittenSchemaResults rewrittenSchemaResults =
                mAppSearchImpl.rewriteSchema(
                        createPrefix("package", "existingDatabase"),
                        existingSchemaBuilder,
                        newSchema);

        // Nothing was removed, but the method did rewrite the type name.
        assertThat(rewrittenSchemaResults.mRewrittenPrefixedTypes.keySet())
                .containsExactly("package$existingDatabase/Foo");
        assertThat(rewrittenSchemaResults.mDeletedPrefixedTypes).isEmpty();

        // Same schema since nothing was added.
        SchemaProto expectedSchema = existingSchemaBuilder.build();
        assertThat(existingSchemaBuilder.getTypesList())
                .containsExactlyElementsIn(expectedSchema.getTypesList());
    }

    /**
     * Ensure that we track which types from the existing schema are deleted when a new schema is
     * set.
     */
    @Test
    public void testRewriteSchema_deleteType() throws Exception {
        SchemaProto.Builder existingSchemaBuilder =
                SchemaProto.newBuilder()
                        .addTypes(
                                SchemaTypeConfigProto.newBuilder()
                                        .setSchemaType("package$existingDatabase/Foo")
                                        .build());

        SchemaProto newSchema =
                SchemaProto.newBuilder()
                        .addTypes(SchemaTypeConfigProto.newBuilder().setSchemaType("Bar").build())
                        .build();

        AppSearchImpl.RewrittenSchemaResults rewrittenSchemaResults =
                mAppSearchImpl.rewriteSchema(
                        createPrefix("package", "existingDatabase"),
                        existingSchemaBuilder,
                        newSchema);

        // Bar type was rewritten, but Foo ended up being deleted since it wasn't included in the
        // new schema.
        assertThat(rewrittenSchemaResults.mRewrittenPrefixedTypes)
                .containsKey("package$existingDatabase/Bar");
        assertThat(rewrittenSchemaResults.mRewrittenPrefixedTypes.keySet().size()).isEqualTo(1);
        assertThat(rewrittenSchemaResults.mDeletedPrefixedTypes)
                .containsExactly("package$existingDatabase/Foo");

        // Same schema since nothing was added.
        SchemaProto expectedSchema =
                SchemaProto.newBuilder()
                        .addTypes(
                                SchemaTypeConfigProto.newBuilder()
                                        .setSchemaType("package$existingDatabase/Bar")
                                        .build())
                        .build();

        assertThat(existingSchemaBuilder.getTypesList())
                .containsExactlyElementsIn(expectedSchema.getTypesList());
    }

    @Test
    public void testAddDocumentTypePrefix() {
        DocumentProto insideDocument =
                DocumentProto.newBuilder()
                        .setUri("inside-id")
                        .setSchema("type")
                        .setNamespace("namespace")
                        .build();
        DocumentProto documentProto =
                DocumentProto.newBuilder()
                        .setUri("id")
                        .setSchema("type")
                        .setNamespace("namespace")
                        .addProperties(PropertyProto.newBuilder().addDocumentValues(insideDocument))
                        .build();

        DocumentProto expectedInsideDocument =
                DocumentProto.newBuilder()
                        .setUri("inside-id")
                        .setSchema("package$databaseName/type")
                        .setNamespace("package$databaseName/namespace")
                        .build();
        DocumentProto expectedDocumentProto =
                DocumentProto.newBuilder()
                        .setUri("id")
                        .setSchema("package$databaseName/type")
                        .setNamespace("package$databaseName/namespace")
                        .addProperties(
                                PropertyProto.newBuilder()
                                        .addDocumentValues(expectedInsideDocument))
                        .build();

        DocumentProto.Builder actualDocument = documentProto.toBuilder();
        addPrefixToDocument(actualDocument, createPrefix("package", "databaseName"));
        assertThat(actualDocument.build()).isEqualTo(expectedDocumentProto);
    }

    @Test
    public void testRemoveDocumentTypePrefixes() throws Exception {
        DocumentProto insideDocument =
                DocumentProto.newBuilder()
                        .setUri("inside-id")
                        .setSchema("package$databaseName/type")
                        .setNamespace("package$databaseName/namespace")
                        .build();
        DocumentProto documentProto =
                DocumentProto.newBuilder()
                        .setUri("id")
                        .setSchema("package$databaseName/type")
                        .setNamespace("package$databaseName/namespace")
                        .addProperties(PropertyProto.newBuilder().addDocumentValues(insideDocument))
                        .build();

        DocumentProto expectedInsideDocument =
                DocumentProto.newBuilder()
                        .setUri("inside-id")
                        .setSchema("type")
                        .setNamespace("namespace")
                        .build();

        DocumentProto expectedDocumentProto =
                DocumentProto.newBuilder()
                        .setUri("id")
                        .setSchema("type")
                        .setNamespace("namespace")
                        .addProperties(
                                PropertyProto.newBuilder()
                                        .addDocumentValues(expectedInsideDocument))
                        .build();

        DocumentProto.Builder actualDocument = documentProto.toBuilder();
        assertThat(removePrefixesFromDocument(actualDocument)).isEqualTo("package$databaseName/");
        assertThat(actualDocument.build()).isEqualTo(expectedDocumentProto);
    }

    @Test
    public void testRemoveDatabasesFromDocumentThrowsException() throws Exception {
        // Set two different database names in the document, which should never happen
        DocumentProto documentProto =
                DocumentProto.newBuilder()
                        .setUri("id")
                        .setSchema("prefix1/type")
                        .setNamespace("prefix2/namespace")
                        .build();

        DocumentProto.Builder actualDocument = documentProto.toBuilder();
        AppSearchException e =
                expectThrows(
                        AppSearchException.class, () -> removePrefixesFromDocument(actualDocument));
        assertThat(e).hasMessageThat().contains("Found unexpected multiple prefix names");
    }

    @Test
    public void testNestedRemoveDatabasesFromDocumentThrowsException() throws Exception {
        // Set two different database names in the outer and inner document, which should never
        // happen.
        DocumentProto insideDocument =
                DocumentProto.newBuilder()
                        .setUri("inside-id")
                        .setSchema("prefix1/type")
                        .setNamespace("prefix1/namespace")
                        .build();
        DocumentProto documentProto =
                DocumentProto.newBuilder()
                        .setUri("id")
                        .setSchema("prefix2/type")
                        .setNamespace("prefix2/namespace")
                        .addProperties(PropertyProto.newBuilder().addDocumentValues(insideDocument))
                        .build();

        DocumentProto.Builder actualDocument = documentProto.toBuilder();
        AppSearchException e =
                expectThrows(
                        AppSearchException.class, () -> removePrefixesFromDocument(actualDocument));
        assertThat(e).hasMessageThat().contains("Found unexpected multiple prefix names");
    }

    @Test
    public void testOptimize() throws Exception {
        // Insert schema
        List<AppSearchSchema> schemas =
                Collections.singletonList(new AppSearchSchema.Builder("type").build());
        mAppSearchImpl.setSchema(
                "package",
                "database",
                schemas,
                /*schemasNotPlatformSurfaceable=*/ Collections.emptyList(),
                /*schemasPackageAccessible=*/ Collections.emptyMap(),
                /*forceOverride=*/ false,
                /*version=*/ 0);

        // Insert enough documents.
        for (int i = 0;
                i
                        < AppSearchImpl.OPTIMIZE_THRESHOLD_DOC_COUNT
                                + AppSearchImpl.CHECK_OPTIMIZE_INTERVAL;
                i++) {
            GenericDocument document =
                    new GenericDocument.Builder<>("namespace", "id" + i, "type").build();
            mAppSearchImpl.putDocument("package", "database", document, /*logger=*/ null);
        }

        // Check optimize() will release 0 docs since there is no deletion.
        GetOptimizeInfoResultProto optimizeInfo = mAppSearchImpl.getOptimizeInfoResultLocked();
        assertThat(optimizeInfo.getOptimizableDocs()).isEqualTo(0);

        // delete 999 documents, we will reach the threshold to trigger optimize() in next
        // deletion.
        for (int i = 0; i < AppSearchImpl.OPTIMIZE_THRESHOLD_DOC_COUNT - 1; i++) {
            mAppSearchImpl.remove(
                    "package", "database", "namespace", "id" + i, /*removeStatsBuilder=*/ null);
        }

        // Updates the check for optimize counter, checkForOptimize() will be triggered since
        // CHECK_OPTIMIZE_INTERVAL is reached but optimize() won't since
        // OPTIMIZE_THRESHOLD_DOC_COUNT is not.
        mAppSearchImpl.checkForOptimize(
                /*mutateBatchSize=*/ AppSearchImpl.OPTIMIZE_THRESHOLD_DOC_COUNT - 1);

        // Verify optimize() still not be triggered.
        optimizeInfo = mAppSearchImpl.getOptimizeInfoResultLocked();
        assertThat(optimizeInfo.getOptimizableDocs())
                .isEqualTo(AppSearchImpl.OPTIMIZE_THRESHOLD_DOC_COUNT - 1);

        // Keep delete docs
        for (int i = AppSearchImpl.OPTIMIZE_THRESHOLD_DOC_COUNT;
                i
                        < AppSearchImpl.OPTIMIZE_THRESHOLD_DOC_COUNT
                                + AppSearchImpl.CHECK_OPTIMIZE_INTERVAL;
                i++) {
            mAppSearchImpl.remove(
                    "package", "database", "namespace", "id" + i, /*removeStatsBuilder=*/ null);
        }
        // updates the check for optimize counter, will reach both CHECK_OPTIMIZE_INTERVAL and
        // OPTIMIZE_THRESHOLD_DOC_COUNT this time and trigger a optimize().
        mAppSearchImpl.checkForOptimize(/*mutateBatchSize*/ AppSearchImpl.CHECK_OPTIMIZE_INTERVAL);

        // Verify optimize() is triggered
        optimizeInfo = mAppSearchImpl.getOptimizeInfoResultLocked();
        assertThat(optimizeInfo.getOptimizableDocs())
                .isLessThan(AppSearchImpl.CHECK_OPTIMIZE_INTERVAL);
    }

    @Test
    public void testReset() throws Exception {
        // Setup the index
        Context context = ApplicationProvider.getApplicationContext();
        File appsearchDir = mTemporaryFolder.newFolder();
        AppSearchImpl appSearchImpl =
                AppSearchImpl.create(
                        appsearchDir,
                        context,
                        VisibilityStore.NO_OP_USER_ID,
                        /*globalQuerierPackage=*/ "",
                        /*logger=*/ null);

        // Insert schema
        List<AppSearchSchema> schemas =
                ImmutableList.of(
                        new AppSearchSchema.Builder("Type1").build(),
                        new AppSearchSchema.Builder("Type2").build());
        appSearchImpl.setSchema(
                context.getPackageName(),
                "database1",
                schemas,
                /*schemasNotPlatformSurfaceable=*/ Collections.emptyList(),
                /*schemasPackageAccessible=*/ Collections.emptyMap(),
                /*forceOverride=*/ false,
                /*version=*/ 0);

        // Insert a valid doc
        GenericDocument validDoc =
                new GenericDocument.Builder<>("namespace1", "id1", "Type1").build();
        appSearchImpl.putDocument(
                context.getPackageName(), "database1", validDoc, /*logger=*/ null);

        // Query it via global query. We use the same code again later so this is to make sure we
        // have our global query configured right.
        SearchResultPage results =
                appSearchImpl.globalQuery(
                        /*queryExpression=*/ "",
                        new SearchSpec.Builder().addFilterSchemas("Type1").build(),
                        context.getPackageName(),
                        VisibilityStore.NO_OP_USER_ID,
                        /*logger=*/ null);
        assertThat(results.getResults()).hasSize(1);
        assertThat(results.getResults().get(0).getGenericDocument()).isEqualTo(validDoc);

        // Create a doc with a malformed namespace
        DocumentProto invalidDoc =
                DocumentProto.newBuilder()
                        .setNamespace("invalidNamespace")
                        .setUri("id2")
                        .setSchema(context.getPackageName() + "$database1/Type1")
                        .build();
        AppSearchException e =
                expectThrows(
                        AppSearchException.class,
                        () -> PrefixUtil.getPrefix(invalidDoc.getNamespace()));
        assertThat(e)
                .hasMessageThat()
                .isEqualTo(
                        "The prefixed value \"invalidNamespace\" doesn't contain a valid database"
                            + " name");

        // Insert the invalid doc with an invalid namespace right into icing
        PutResultProto putResultProto = appSearchImpl.mIcingSearchEngineLocked.put(invalidDoc);
        assertThat(putResultProto.getStatus().getCode()).isEqualTo(StatusProto.Code.OK);

        // Create a logger for capturing initialization to make sure we are logging the recovery
        // process correctly.
        AppSearchLoggerTest.TestLogger testLogger = new AppSearchLoggerTest.TestLogger();

        // Initialize AppSearchImpl. This should cause a reset.
        appSearchImpl.close();
        appSearchImpl =
                AppSearchImpl.create(
                        appsearchDir,
                        context,
                        VisibilityStore.NO_OP_USER_ID,
                        /*globalQuerierPackage=*/ context.getPackageName(),
                        testLogger);

        // Check recovery state
        InitializeStats initStats = testLogger.mInitializeStats;
        assertThat(initStats).isNotNull();
        assertThat(initStats.getStatusCode()).isEqualTo(AppSearchResult.RESULT_INTERNAL_ERROR);
        assertThat(initStats.hasDeSync()).isFalse();
        assertThat(initStats.getDocumentStoreRecoveryCause())
                .isEqualTo(InitializeStats.RECOVERY_CAUSE_NONE);
        // TODO(b/187879464): There should not be a recovery here, but icing lib reports one if the
        //  doc had no tokens. Once the mentioned bug is fixed, uncomment this.
        // assertThat(initStats.getIndexRestorationCause())
        //         .isEqualTo(InitializeStats.RECOVERY_CAUSE_NONE);
        assertThat(initStats.getSchemaStoreRecoveryCause())
                .isEqualTo(InitializeStats.RECOVERY_CAUSE_NONE);
        assertThat(initStats.getDocumentStoreDataStatus())
                .isEqualTo(InitializeStats.DOCUMENT_STORE_DATA_STATUS_NO_DATA_LOSS);
        assertThat(initStats.hasReset()).isTrue();
        assertThat(initStats.getResetStatusCode()).isEqualTo(AppSearchResult.RESULT_OK);

        // Make sure all our data is gone
        assertThat(appSearchImpl.getSchema(context.getPackageName(), "database1").getSchemas())
                .isEmpty();
        results =
                appSearchImpl.globalQuery(
                        /*queryExpression=*/ "",
                        new SearchSpec.Builder().addFilterSchemas("Type1").build(),
                        context.getPackageName(),
                        VisibilityStore.NO_OP_USER_ID,
                        /*logger=*/ null);
        assertThat(results.getResults()).isEmpty();

        // Make sure the index can now be used successfully
        appSearchImpl.setSchema(
                context.getPackageName(),
                "database1",
                Collections.singletonList(new AppSearchSchema.Builder("Type1").build()),
                /*schemasNotPlatformSurfaceable=*/ Collections.emptyList(),
                /*schemasPackageAccessible=*/ Collections.emptyMap(),
                /*forceOverride=*/ false,
                /*version=*/ 0);

        // Insert a valid doc
        appSearchImpl.putDocument(
                context.getPackageName(), "database1", validDoc, /*logger=*/ null);

        // Query it via global query.
        results =
                appSearchImpl.globalQuery(
                        /*queryExpression=*/ "",
                        new SearchSpec.Builder().addFilterSchemas("Type1").build(),
                        context.getPackageName(),
                        VisibilityStore.NO_OP_USER_ID,
                        /*logger=*/ null);
        assertThat(results.getResults()).hasSize(1);
        assertThat(results.getResults().get(0).getGenericDocument()).isEqualTo(validDoc);
    }

    @Test
    public void testRewriteSearchSpec_oneInstance() throws Exception {
        SearchSpecProto.Builder searchSpecProto = SearchSpecProto.newBuilder().setQuery("");

        // Insert schema
        List<AppSearchSchema> schemas =
                Collections.singletonList(new AppSearchSchema.Builder("type").build());
        mAppSearchImpl.setSchema(
                "package",
                "database",
                schemas,
                /*schemasNotPlatformSurfaceable=*/ Collections.emptyList(),
                /*schemasPackageAccessible=*/ Collections.emptyMap(),
                /*forceOverride=*/ false,
                /*version=*/ 0);

        // Insert document
        GenericDocument document = new GenericDocument.Builder<>("namespace", "id", "type").build();
        mAppSearchImpl.putDocument("package", "database", document, /*logger=*/ null);

        // Rewrite SearchSpec
        mAppSearchImpl.rewriteSearchSpecForPrefixesLocked(
                searchSpecProto,
                Collections.singleton(createPrefix("package", "database")),
                ImmutableSet.of("package$database/type"));
        assertThat(searchSpecProto.getSchemaTypeFiltersList())
                .containsExactly("package$database/type");
        assertThat(searchSpecProto.getNamespaceFiltersList())
                .containsExactly("package$database/namespace");
    }

    @Test
    public void testRewriteSearchSpec_twoInstances() throws Exception {
        SearchSpecProto.Builder searchSpecProto = SearchSpecProto.newBuilder().setQuery("");

        // Insert schema
        List<AppSearchSchema> schemas =
                ImmutableList.of(
                        new AppSearchSchema.Builder("typeA").build(),
                        new AppSearchSchema.Builder("typeB").build());
        mAppSearchImpl.setSchema(
                "package",
                "database1",
                schemas,
                /*schemasNotPlatformSurfaceable=*/ Collections.emptyList(),
                /*schemasPackageAccessible=*/ Collections.emptyMap(),
                /*forceOverride=*/ false,
                /*version=*/ 0);
        mAppSearchImpl.setSchema(
                "package",
                "database2",
                schemas,
                /*schemasNotPlatformSurfaceable=*/ Collections.emptyList(),
                /*schemasPackageAccessible=*/ Collections.emptyMap(),
                /*forceOverride=*/ false,
                /*version=*/ 0);

        // Insert documents
        GenericDocument document1 =
                new GenericDocument.Builder<>("namespace", "id", "typeA").build();
        mAppSearchImpl.putDocument("package", "database1", document1, /*logger=*/ null);

        GenericDocument document2 =
                new GenericDocument.Builder<>("namespace", "id", "typeB").build();
        mAppSearchImpl.putDocument("package", "database2", document2, /*logger=*/ null);

        // Rewrite SearchSpec
        mAppSearchImpl.rewriteSearchSpecForPrefixesLocked(
                searchSpecProto,
                ImmutableSet.of(
                        createPrefix("package", "database1"), createPrefix("package", "database2")),
                ImmutableSet.of(
                        "package$database1/typeA", "package$database1/typeB",
                        "package$database2/typeA", "package$database2/typeB"));
        assertThat(searchSpecProto.getSchemaTypeFiltersList())
                .containsExactly(
                        "package$database1/typeA",
                        "package$database1/typeB",
                        "package$database2/typeA",
                        "package$database2/typeB");
        assertThat(searchSpecProto.getNamespaceFiltersList())
                .containsExactly("package$database1/namespace", "package$database2/namespace");
    }

    @Test
    public void testRewriteSearchSpec_ignoresSearchSpecSchemaFilters() throws Exception {
        SearchSpecProto.Builder searchSpecProto =
                SearchSpecProto.newBuilder().setQuery("").addSchemaTypeFilters("type");

        // Insert schema
        List<AppSearchSchema> schemas =
                Collections.singletonList(new AppSearchSchema.Builder("type").build());
        mAppSearchImpl.setSchema(
                "package",
                "database",
                schemas,
                /*schemasNotPlatformSurfaceable=*/ Collections.emptyList(),
                /*schemasPackageAccessible=*/ Collections.emptyMap(),
                /*forceOverride=*/ false,
                /*version=*/ 0);

        // Insert document
        GenericDocument document = new GenericDocument.Builder<>("namespace", "id", "type").build();
        mAppSearchImpl.putDocument("package", "database", document, /*logger=*/ null);

        // If 'allowedPrefixedSchemas' is empty, this returns false since there's nothing to
        // search over. Despite the searchSpecProto having schema type filters.
        assertThat(
                        mAppSearchImpl.rewriteSearchSpecForPrefixesLocked(
                                searchSpecProto,
                                Collections.singleton(createPrefix("package", "database")),
                                /*allowedPrefixedSchemas=*/ Collections.emptySet()))
                .isFalse();
    }

    @Test
    public void testQueryEmptyDatabase() throws Exception {
        SearchSpec searchSpec =
                new SearchSpec.Builder().setTermMatch(TermMatchType.Code.PREFIX_VALUE).build();
        SearchResultPage searchResultPage =
                mAppSearchImpl.query("package", "EmptyDatabase", "", searchSpec, /*logger=*/ null);
        assertThat(searchResultPage.getResults()).isEmpty();
    }

    /**
     * TODO(b/169883602): This should be an integration test at the cts-level. This is a short-term
     * test until we have official support for multiple-apps indexing at once.
     */
    @Test
    public void testQueryWithMultiplePackages_noPackageFilters() throws Exception {
        // Insert package1 schema
        List<AppSearchSchema> schema1 =
                ImmutableList.of(new AppSearchSchema.Builder("schema1").build());
        mAppSearchImpl.setSchema(
                "package1",
                "database1",
                schema1,
                /*schemasNotPlatformSurfaceable=*/ Collections.emptyList(),
                /*schemasPackageAccessible=*/ Collections.emptyMap(),
                /*forceOverride=*/ false,
                /*version=*/ 0);

        // Insert package2 schema
        List<AppSearchSchema> schema2 =
                ImmutableList.of(new AppSearchSchema.Builder("schema2").build());
        mAppSearchImpl.setSchema(
                "package2",
                "database2",
                schema2,
                /*schemasNotPlatformSurfaceable=*/ Collections.emptyList(),
                /*schemasPackageAccessible=*/ Collections.emptyMap(),
                /*forceOverride=*/ false,
                /*version=*/ 0);

        // Insert package1 document
        GenericDocument document =
                new GenericDocument.Builder<>("namespace", "id", "schema1").build();
        mAppSearchImpl.putDocument("package1", "database1", document, /*logger=*/ null);

        // No query filters specified, package2 shouldn't be able to query for package1's documents.
        SearchSpec searchSpec =
                new SearchSpec.Builder().setTermMatch(TermMatchType.Code.PREFIX_VALUE).build();
        SearchResultPage searchResultPage =
                mAppSearchImpl.query("package2", "database2", "", searchSpec, /*logger=*/ null);
        assertThat(searchResultPage.getResults()).isEmpty();

        // Insert package2 document
        document = new GenericDocument.Builder<>("namespace", "id", "schema2").build();
        mAppSearchImpl.putDocument("package2", "database2", document, /*logger=*/ null);

        // No query filters specified. package2 should only get its own documents back.
        searchResultPage =
                mAppSearchImpl.query("package2", "database2", "", searchSpec, /*logger=
         */ null);
        assertThat(searchResultPage.getResults()).hasSize(1);
        assertThat(searchResultPage.getResults().get(0).getGenericDocument()).isEqualTo(document);
    }

    /**
     * TODO(b/169883602): This should be an integration test at the cts-level. This is a short-term
     * test until we have official support for multiple-apps indexing at once.
     */
    @Test
    public void testQueryWithMultiplePackages_withPackageFilters() throws Exception {
        // Insert package1 schema
        List<AppSearchSchema> schema1 =
                ImmutableList.of(new AppSearchSchema.Builder("schema1").build());
        mAppSearchImpl.setSchema(
                "package1",
                "database1",
                schema1,
                /*schemasNotPlatformSurfaceable=*/ Collections.emptyList(),
                /*schemasPackageAccessible=*/ Collections.emptyMap(),
                /*forceOverride=*/ false,
                /*version=*/ 0);

        // Insert package2 schema
        List<AppSearchSchema> schema2 =
                ImmutableList.of(new AppSearchSchema.Builder("schema2").build());
        mAppSearchImpl.setSchema(
                "package2",
                "database2",
                schema2,
                /*schemasNotPlatformSurfaceable=*/ Collections.emptyList(),
                /*schemasPackageAccessible=*/ Collections.emptyMap(),
                /*forceOverride=*/ false,
                /*version=*/ 0);

        // Insert package1 document
        GenericDocument document =
                new GenericDocument.Builder<>("namespace", "id", "schema1").build();
        mAppSearchImpl.putDocument("package1", "database1", document, /*logger=*/ null);

        // "package1" filter specified, but package2 shouldn't be able to query for package1's
        // documents.
        SearchSpec searchSpec =
                new SearchSpec.Builder()
                        .setTermMatch(TermMatchType.Code.PREFIX_VALUE)
                        .addFilterPackageNames("package1")
                        .build();
        SearchResultPage searchResultPage =
                mAppSearchImpl.query("package2", "database2", "", searchSpec, /*logger=*/ null);
        assertThat(searchResultPage.getResults()).isEmpty();

        // Insert package2 document
        document = new GenericDocument.Builder<>("namespace", "id", "schema2").build();
        mAppSearchImpl.putDocument("package2", "database2", document, /*logger=*/ null);

        // "package2" filter specified, package2 should only get its own documents back.
        searchSpec =
                new SearchSpec.Builder()
                        .setTermMatch(TermMatchType.Code.PREFIX_VALUE)
                        .addFilterPackageNames("package2")
                        .build();
        searchResultPage =
                mAppSearchImpl.query("package2", "database2", "", searchSpec, /*logger=
         */ null);
        assertThat(searchResultPage.getResults()).hasSize(1);
        assertThat(searchResultPage.getResults().get(0).getGenericDocument()).isEqualTo(document);
    }

    @Test
    public void testGlobalQueryEmptyDatabase() throws Exception {
        SearchSpec searchSpec =
                new SearchSpec.Builder().setTermMatch(TermMatchType.Code.PREFIX_VALUE).build();
        SearchResultPage searchResultPage =
                mAppSearchImpl.globalQuery(
                        "",
                        searchSpec,
                        /*callerPackageName=*/ "",
                        /*callerUid=*/ 0,
                        /*logger=*/ null);
        assertThat(searchResultPage.getResults()).isEmpty();
    }

    @Test
    public void testRemoveEmptyDatabase_noExceptionThrown() throws Exception {
        SearchSpec searchSpec =
                new SearchSpec.Builder()
                        .addFilterSchemas("FakeType")
                        .setTermMatch(TermMatchType.Code.PREFIX_VALUE)
                        .build();
        mAppSearchImpl.removeByQuery(
                "package", "EmptyDatabase", "", searchSpec, /*statsBuilder=*/ null);

        searchSpec =
                new SearchSpec.Builder()
                        .addFilterNamespaces("FakeNamespace")
                        .setTermMatch(TermMatchType.Code.PREFIX_VALUE)
                        .build();
        mAppSearchImpl.removeByQuery(
                "package", "EmptyDatabase", "", searchSpec, /*statsBuilder=*/ null);

        searchSpec = new SearchSpec.Builder().setTermMatch(TermMatchType.Code.PREFIX_VALUE).build();
        mAppSearchImpl.removeByQuery(
                "package", "EmptyDatabase", "", searchSpec, /*statsBuilder=*/ null);
    }

    @Test
    public void testSetSchema() throws Exception {
        List<SchemaTypeConfigProto> existingSchemas =
                mAppSearchImpl.getSchemaProtoLocked().getTypesList();

        List<AppSearchSchema> schemas =
                Collections.singletonList(new AppSearchSchema.Builder("Email").build());
        // Set schema Email to AppSearch database1
        mAppSearchImpl.setSchema(
                "package",
                "database1",
                schemas,
                /*schemasNotPlatformSurfaceable=*/ Collections.emptyList(),
                /*schemasPackageAccessible=*/ Collections.emptyMap(),
                /*forceOverride=*/ false,
                /*version=*/ 0);

        // Create expected schemaType proto.
        SchemaProto expectedProto =
                SchemaProto.newBuilder()
                        .addTypes(
                                SchemaTypeConfigProto.newBuilder()
                                        .setSchemaType("package$database1/Email")
                                        .setVersion(0))
                        .build();

        List<SchemaTypeConfigProto> expectedTypes = new ArrayList<>();
        expectedTypes.addAll(existingSchemas);
        expectedTypes.addAll(expectedProto.getTypesList());
        assertThat(mAppSearchImpl.getSchemaProtoLocked().getTypesList())
                .containsExactlyElementsIn(expectedTypes);
    }

    @Test
    public void testSetSchema_incompatible() throws Exception {
        List<SchemaTypeConfigProto> existingSchemas =
                mAppSearchImpl.getSchemaProtoLocked().getTypesList();

        List<AppSearchSchema> oldSchemas = new ArrayList<>();
        oldSchemas.add(
                new AppSearchSchema.Builder("Email")
                        .addProperty(
                                new AppSearchSchema.StringPropertyConfig.Builder("foo")
                                        .setCardinality(
                                                AppSearchSchema.PropertyConfig.CARDINALITY_REQUIRED)
                                        .setTokenizerType(
                                                AppSearchSchema.StringPropertyConfig
                                                        .TOKENIZER_TYPE_PLAIN)
                                        .setIndexingType(
                                                AppSearchSchema.StringPropertyConfig
                                                        .INDEXING_TYPE_PREFIXES)
                                        .build())
                        .build());
        oldSchemas.add(new AppSearchSchema.Builder("Text").build());
        // Set schema Email to AppSearch database1
        mAppSearchImpl.setSchema(
                "package",
                "database1",
                oldSchemas,
                /*schemasNotPlatformSurfaceable=*/ Collections.emptyList(),
                /*schemasPackageAccessible=*/ Collections.emptyMap(),
                /*forceOverride=*/ false,
                /*version=*/ 0);

        // Create incompatible schema
        List<AppSearchSchema> newSchemas =
                Collections.singletonList(new AppSearchSchema.Builder("Email").build());

        // set email incompatible and delete text
        SetSchemaResponse setSchemaResponse =
                mAppSearchImpl.setSchema(
                        "package",
                        "database1",
                        newSchemas,
                        /*schemasNotPlatformSurfaceable=*/ Collections.emptyList(),
                        /*schemasPackageAccessible=*/ Collections.emptyMap(),
                        /*forceOverride=*/ true,
                        /*version=*/ 0);
        assertThat(setSchemaResponse.getDeletedTypes()).containsExactly("Text");
        assertThat(setSchemaResponse.getIncompatibleTypes()).containsExactly("Email");
    }

    @Test
    public void testRemoveSchema() throws Exception {
        List<SchemaTypeConfigProto> existingSchemas =
                mAppSearchImpl.getSchemaProtoLocked().getTypesList();

        List<AppSearchSchema> schemas =
                ImmutableList.of(
                        new AppSearchSchema.Builder("Email").build(),
                        new AppSearchSchema.Builder("Document").build());
        // Set schema Email and Document to AppSearch database1
        mAppSearchImpl.setSchema(
                "package",
                "database1",
                schemas,
                /*schemasNotPlatformSurfaceable=*/ Collections.emptyList(),
                /*schemasPackageAccessible=*/ Collections.emptyMap(),
                /*forceOverride=*/ false,
                /*version=*/ 0);

        // Create expected schemaType proto.
        SchemaProto expectedProto =
                SchemaProto.newBuilder()
                        .addTypes(
                                SchemaTypeConfigProto.newBuilder()
                                        .setSchemaType("package$database1/Email")
                                        .setVersion(0))
                        .addTypes(
                                SchemaTypeConfigProto.newBuilder()
                                        .setSchemaType("package$database1/Document")
                                        .setVersion(0))
                        .build();

        // Check both schema Email and Document saved correctly.
        List<SchemaTypeConfigProto> expectedTypes = new ArrayList<>();
        expectedTypes.addAll(existingSchemas);
        expectedTypes.addAll(expectedProto.getTypesList());
        assertThat(mAppSearchImpl.getSchemaProtoLocked().getTypesList())
                .containsExactlyElementsIn(expectedTypes);

        final List<AppSearchSchema> finalSchemas =
                Collections.singletonList(new AppSearchSchema.Builder("Email").build());
        SetSchemaResponse setSchemaResponse =
                mAppSearchImpl.setSchema(
                        "package",
                        "database1",
                        finalSchemas,
                        /*schemasNotPlatformSurfaceable=*/ Collections.emptyList(),
                        /*schemasPackageAccessible=*/ Collections.emptyMap(),
                        /*forceOverride=*/ false,
                        /*version=*/ 0);

        // Check the Document type has been deleted.
        assertThat(setSchemaResponse.getDeletedTypes()).containsExactly("Document");

        // ForceOverride to delete.
        mAppSearchImpl.setSchema(
                "package",
                "database1",
                finalSchemas,
                /*schemasNotPlatformSurfaceable=*/ Collections.emptyList(),
                /*schemasPackageAccessible=*/ Collections.emptyMap(),
                /*forceOverride=*/ true,
                /*version=*/ 0);

        // Check Document schema is removed.
        expectedProto =
                SchemaProto.newBuilder()
                        .addTypes(
                                SchemaTypeConfigProto.newBuilder()
                                        .setSchemaType("package$database1/Email")
                                        .setVersion(0))
                        .build();

        expectedTypes = new ArrayList<>();
        expectedTypes.addAll(existingSchemas);
        expectedTypes.addAll(expectedProto.getTypesList());
        assertThat(mAppSearchImpl.getSchemaProtoLocked().getTypesList())
                .containsExactlyElementsIn(expectedTypes);
    }

    @Test
    public void testRemoveSchema_differentDataBase() throws Exception {
        List<SchemaTypeConfigProto> existingSchemas =
                mAppSearchImpl.getSchemaProtoLocked().getTypesList();

        // Create schemas
        List<AppSearchSchema> schemas =
                ImmutableList.of(
                        new AppSearchSchema.Builder("Email").build(),
                        new AppSearchSchema.Builder("Document").build());

        // Set schema Email and Document to AppSearch database1 and 2
        mAppSearchImpl.setSchema(
                "package",
                "database1",
                schemas,
                /*schemasNotPlatformSurfaceable=*/ Collections.emptyList(),
                /*schemasPackageAccessible=*/ Collections.emptyMap(),
                /*forceOverride=*/ false,
                /*version=*/ 0);
        mAppSearchImpl.setSchema(
                "package",
                "database2",
                schemas,
                /*schemasNotPlatformSurfaceable=*/ Collections.emptyList(),
                /*schemasPackageAccessible=*/ Collections.emptyMap(),
                /*forceOverride=*/ false,
                /*version=*/ 0);

        // Create expected schemaType proto.
        SchemaProto expectedProto =
                SchemaProto.newBuilder()
                        .addTypes(
                                SchemaTypeConfigProto.newBuilder()
                                        .setSchemaType("package$database1/Email")
                                        .setVersion(0))
                        .addTypes(
                                SchemaTypeConfigProto.newBuilder()
                                        .setSchemaType("package$database1/Document")
                                        .setVersion(0))
                        .addTypes(
                                SchemaTypeConfigProto.newBuilder()
                                        .setSchemaType("package$database2/Email")
                                        .setVersion(0))
                        .addTypes(
                                SchemaTypeConfigProto.newBuilder()
                                        .setSchemaType("package$database2/Document")
                                        .setVersion(0))
                        .build();

        // Check Email and Document is saved in database 1 and 2 correctly.
        List<SchemaTypeConfigProto> expectedTypes = new ArrayList<>();
        expectedTypes.addAll(existingSchemas);
        expectedTypes.addAll(expectedProto.getTypesList());
        assertThat(mAppSearchImpl.getSchemaProtoLocked().getTypesList())
                .containsExactlyElementsIn(expectedTypes);

        // Save only Email to database1 this time.
        schemas = Collections.singletonList(new AppSearchSchema.Builder("Email").build());
        mAppSearchImpl.setSchema(
                "package",
                "database1",
                schemas,
                /*schemasNotPlatformSurfaceable=*/ Collections.emptyList(),
                /*schemasPackageAccessible=*/ Collections.emptyMap(),
                /*forceOverride=*/ true,
                /*version=*/ 0);

        // Create expected schemaType list, database 1 should only contain Email but database 2
        // remains in same.
        expectedProto =
                SchemaProto.newBuilder()
                        .addTypes(
                                SchemaTypeConfigProto.newBuilder()
                                        .setSchemaType("package$database1/Email")
                                        .setVersion(0))
                        .addTypes(
                                SchemaTypeConfigProto.newBuilder()
                                        .setSchemaType("package$database2/Email")
                                        .setVersion(0))
                        .addTypes(
                                SchemaTypeConfigProto.newBuilder()
                                        .setSchemaType("package$database2/Document")
                                        .setVersion(0))
                        .build();

        // Check nothing changed in database2.
        expectedTypes = new ArrayList<>();
        expectedTypes.addAll(existingSchemas);
        expectedTypes.addAll(expectedProto.getTypesList());
        assertThat(mAppSearchImpl.getSchemaProtoLocked().getTypesList())
                .containsExactlyElementsIn(expectedTypes);
    }

    @Test
    public void testClearPackageData() throws AppSearchException {
        List<SchemaTypeConfigProto> existingSchemas =
                mAppSearchImpl.getSchemaProtoLocked().getTypesList();

        // Insert package schema
        List<AppSearchSchema> schema =
                ImmutableList.of(new AppSearchSchema.Builder("schema").build());
        mAppSearchImpl.setSchema(
                "package",
                "database",
                schema,
                /*schemasNotPlatformSurfaceable=*/ Collections.emptyList(),
                /*schemasPackageAccessible=*/ Collections.emptyMap(),
                /*forceOverride=*/ false,
                /*version=*/ 0);

        // Insert package document
        GenericDocument document =
                new GenericDocument.Builder<>("namespace", "id", "schema").build();
        mAppSearchImpl.putDocument("package", "database", document, /*logger=*/ null);

        // Verify the document is indexed.
        SearchSpec searchSpec =
                new SearchSpec.Builder().setTermMatch(TermMatchType.Code.PREFIX_VALUE).build();
        SearchResultPage searchResultPage =
                mAppSearchImpl.query(
                        "package",
                        "database",
                        /*queryExpression=*/ "",
                        searchSpec,
                        /*logger=*/ null);
        assertThat(searchResultPage.getResults()).hasSize(1);
        assertThat(searchResultPage.getResults().get(0).getGenericDocument()).isEqualTo(document);

        // Remove the package
        mAppSearchImpl.clearPackageData("package");

        // Verify the document is cleared.
        searchResultPage =
                mAppSearchImpl.query(
                        "package2",
                        "database2",
                        /*queryExpression=*/ "",
                        searchSpec,
                        /*logger=*/ null);
        assertThat(searchResultPage.getResults()).isEmpty();

        // Verify the schema is cleared.
        assertThat(mAppSearchImpl.getSchemaProtoLocked().getTypesList())
                .containsExactlyElementsIn(existingSchemas);
    }

    @Test
    public void testGetPackageToDatabases() throws Exception {
        Map<String, Set<String>> existingMapping = mAppSearchImpl.getPackageToDatabases();
        Map<String, Set<String>> expectedMapping = new ArrayMap<>();
        expectedMapping.putAll(existingMapping);

        // Has database1
        expectedMapping.put("package1", ImmutableSet.of("database1"));
        mAppSearchImpl.setSchema(
                "package1",
                "database1",
                Collections.singletonList(new AppSearchSchema.Builder("schema").build()),
                /*schemasNotPlatformSurfaceable=*/ Collections.emptyList(),
                /*schemasPackageAccessible=*/ Collections.emptyMap(),
                /*forceOverride=*/ false,
                /*version=*/ 0);
        assertThat(mAppSearchImpl.getPackageToDatabases())
                .containsExactlyEntriesIn(expectedMapping);

        // Has both databases
        expectedMapping.put("package1", ImmutableSet.of("database1", "database2"));
        mAppSearchImpl.setSchema(
                "package1",
                "database2",
                Collections.singletonList(new AppSearchSchema.Builder("schema").build()),
                /*schemasNotPlatformSurfaceable=*/ Collections.emptyList(),
                /*schemasPackageAccessible=*/ Collections.emptyMap(),
                /*forceOverride=*/ false,
                /*version=*/ 0);
        assertThat(mAppSearchImpl.getPackageToDatabases())
                .containsExactlyEntriesIn(expectedMapping);

        // Has both packages
        expectedMapping.put("package2", ImmutableSet.of("database1"));
        mAppSearchImpl.setSchema(
                "package2",
                "database1",
                Collections.singletonList(new AppSearchSchema.Builder("schema").build()),
                /*schemasNotPlatformSurfaceable=*/ Collections.emptyList(),
                /*schemasPackageAccessible=*/ Collections.emptyMap(),
                /*forceOverride=*/ false,
                /*version=*/ 0);
        assertThat(mAppSearchImpl.getPackageToDatabases())
                .containsExactlyEntriesIn(expectedMapping);
    }

    @Test
    public void testGetPrefixes() throws Exception {
        Set<String> existingPrefixes = mAppSearchImpl.getPrefixesLocked();

        // Has database1
        Set<String> expectedPrefixes = new ArraySet<>(existingPrefixes);
        expectedPrefixes.add(createPrefix("package", "database1"));
        mAppSearchImpl.setSchema(
                "package",
                "database1",
                Collections.singletonList(new AppSearchSchema.Builder("schema").build()),
                /*schemasNotPlatformSurfaceable=*/ Collections.emptyList(),
                /*schemasPackageAccessible=*/ Collections.emptyMap(),
                /*forceOverride=*/ false,
                /*version=*/ 0);
        assertThat(mAppSearchImpl.getPrefixesLocked()).containsExactlyElementsIn(expectedPrefixes);

        // Has both databases
        expectedPrefixes.add(createPrefix("package", "database2"));
        mAppSearchImpl.setSchema(
                "package",
                "database2",
                Collections.singletonList(new AppSearchSchema.Builder("schema").build()),
                /*schemasNotPlatformSurfaceable=*/ Collections.emptyList(),
                /*schemasPackageAccessible=*/ Collections.emptyMap(),
                /*forceOverride=*/ false,
                /*version=*/ 0);
        assertThat(mAppSearchImpl.getPrefixesLocked()).containsExactlyElementsIn(expectedPrefixes);
    }

    @Test
    public void testRewriteSearchResultProto() throws Exception {
        final String prefix =
                "com.package.foo"
                        + PrefixUtil.PACKAGE_DELIMITER
                        + "databaseName"
                        + PrefixUtil.DATABASE_DELIMITER;
        final String id = "id";
        final String namespace = prefix + "namespace";
        final String schemaType = prefix + "schema";

        // Building the SearchResult received from query.
        DocumentProto documentProto =
                DocumentProto.newBuilder()
                        .setUri(id)
                        .setNamespace(namespace)
                        .setSchema(schemaType)
                        .build();
        SearchResultProto.ResultProto resultProto =
                SearchResultProto.ResultProto.newBuilder().setDocument(documentProto).build();
        SearchResultProto searchResultProto =
                SearchResultProto.newBuilder().addResults(resultProto).build();
        SchemaTypeConfigProto schemaTypeConfigProto =
                SchemaTypeConfigProto.newBuilder().setSchemaType(schemaType).build();
        Map<String, Map<String, SchemaTypeConfigProto>> schemaMap =
                ImmutableMap.of(prefix, ImmutableMap.of(schemaType, schemaTypeConfigProto));

        DocumentProto.Builder strippedDocumentProto = documentProto.toBuilder();
        removePrefixesFromDocument(strippedDocumentProto);
        SearchResultPage searchResultPage =
                AppSearchImpl.rewriteSearchResultProto(searchResultProto, schemaMap);
        for (SearchResult result : searchResultPage.getResults()) {
            assertThat(result.getPackageName()).isEqualTo("com.package.foo");
            assertThat(result.getDatabaseName()).isEqualTo("databaseName");
            assertThat(result.getGenericDocument())
                    .isEqualTo(
                            GenericDocumentToProtoConverter.toGenericDocument(
                                    strippedDocumentProto.build(), prefix, schemaMap.get(prefix)));
        }
    }

    @Test
    public void testReportUsage() throws Exception {
        // Insert schema
        List<AppSearchSchema> schemas =
                Collections.singletonList(new AppSearchSchema.Builder("type").build());
        mAppSearchImpl.setSchema(
                "package",
                "database",
                schemas,
                /*schemasNotPlatformSurfaceable=*/ Collections.emptyList(),
                /*schemasPackageAccessible=*/ Collections.emptyMap(),
                /*forceOverride=*/ false,
                /*version=*/ 0);

        // Insert two docs
        GenericDocument document1 =
                new GenericDocument.Builder<>("namespace", "id1", "type").build();
        GenericDocument document2 =
                new GenericDocument.Builder<>("namespace", "id2", "type").build();
        mAppSearchImpl.putDocument("package", "database", document1, /*logger=*/ null);
        mAppSearchImpl.putDocument("package", "database", document2, /*logger=*/ null);

        // Report some usages. id1 has 2 app and 1 system usage, id2 has 1 app and 2 system usage.
        mAppSearchImpl.reportUsage(
                "package",
                "database",
                "namespace",
                "id1",
                /*usageTimestampMillis=*/ 10,
                /*systemUsage=*/ false);
        mAppSearchImpl.reportUsage(
                "package",
                "database",
                "namespace",
                "id1",
                /*usageTimestampMillis=*/ 20,
                /*systemUsage=*/ false);
        mAppSearchImpl.reportUsage(
                "package",
                "database",
                "namespace",
                "id1",
                /*usageTimestampMillis=*/ 1000,
                /*systemUsage=*/ true);

        mAppSearchImpl.reportUsage(
                "package",
                "database",
                "namespace",
                "id2",
                /*usageTimestampMillis=*/ 100,
                /*systemUsage=*/ false);
        mAppSearchImpl.reportUsage(
                "package",
                "database",
                "namespace",
                "id2",
                /*usageTimestampMillis=*/ 200,
                /*systemUsage=*/ true);
        mAppSearchImpl.reportUsage(
                "package",
                "database",
                "namespace",
                "id2",
                /*usageTimestampMillis=*/ 150,
                /*systemUsage=*/ true);

        // Sort by app usage count: id1 should win
        List<SearchResult> page =
                mAppSearchImpl
                        .query(
                                "package",
                                "database",
                                "",
                                new SearchSpec.Builder()
                                        .setTermMatch(SearchSpec.TERM_MATCH_EXACT_ONLY)
                                        .setRankingStrategy(SearchSpec.RANKING_STRATEGY_USAGE_COUNT)
                                        .build(),
                                /*logger=*/ null)
                        .getResults();
        assertThat(page).hasSize(2);
        assertThat(page.get(0).getGenericDocument().getId()).isEqualTo("id1");
        assertThat(page.get(1).getGenericDocument().getId()).isEqualTo("id2");

        // Sort by app usage timestamp: id2 should win
        page =
                mAppSearchImpl
                        .query(
                                "package",
                                "database",
                                "",
                                new SearchSpec.Builder()
                                        .setTermMatch(SearchSpec.TERM_MATCH_EXACT_ONLY)
                                        .setRankingStrategy(
                                                SearchSpec
                                                        .RANKING_STRATEGY_USAGE_LAST_USED_TIMESTAMP)
                                        .build(),
                                /*logger=*/ null)
                        .getResults();
        assertThat(page).hasSize(2);
        assertThat(page.get(0).getGenericDocument().getId()).isEqualTo("id2");
        assertThat(page.get(1).getGenericDocument().getId()).isEqualTo("id1");

        // Sort by system usage count: id2 should win
        page =
                mAppSearchImpl
                        .query(
                                "package",
                                "database",
                                "",
                                new SearchSpec.Builder()
                                        .setTermMatch(SearchSpec.TERM_MATCH_EXACT_ONLY)
                                        .setRankingStrategy(
                                                SearchSpec.RANKING_STRATEGY_SYSTEM_USAGE_COUNT)
                                        .build(),
                                /*logger=*/ null)
                        .getResults();
        assertThat(page).hasSize(2);
        assertThat(page.get(0).getGenericDocument().getId()).isEqualTo("id2");
        assertThat(page.get(1).getGenericDocument().getId()).isEqualTo("id1");

        // Sort by system usage timestamp: id1 should win
        page =
                mAppSearchImpl
                        .query(
                                "package",
                                "database",
                                "",
                                new SearchSpec.Builder()
                                        .setTermMatch(SearchSpec.TERM_MATCH_EXACT_ONLY)
                                        .setRankingStrategy(
                                                SearchSpec
                                                        .RANKING_STRATEGY_SYSTEM_USAGE_LAST_USED_TIMESTAMP)
                                        .build(),
                                /*logger=*/ null)
                        .getResults();
        assertThat(page).hasSize(2);
        assertThat(page.get(0).getGenericDocument().getId()).isEqualTo("id1");
        assertThat(page.get(1).getGenericDocument().getId()).isEqualTo("id2");
    }

    @Test
    public void testGetStorageInfoForPackage_nonexistentPackage() throws Exception {
        // "package2" doesn't exist yet, so it shouldn't have any storage size
        StorageInfo storageInfo = mAppSearchImpl.getStorageInfoForPackage("nonexistent.package");
        assertThat(storageInfo.getSizeBytes()).isEqualTo(0);
        assertThat(storageInfo.getAliveDocumentsCount()).isEqualTo(0);
        assertThat(storageInfo.getAliveNamespacesCount()).isEqualTo(0);
    }

    @Test
    public void testGetStorageInfoForPackage_withoutDocument() throws Exception {
        // Insert schema for "package1"
        List<AppSearchSchema> schemas =
                Collections.singletonList(new AppSearchSchema.Builder("type").build());
        mAppSearchImpl.setSchema(
                "package1",
                "database",
                schemas,
                /*schemasNotPlatformSurfaceable=*/ Collections.emptyList(),
                /*schemasPackageAccessible=*/ Collections.emptyMap(),
                /*forceOverride=*/ false,
                /*version=*/ 0);

        // Since "package1" doesn't have a document, it get any space attributed to it.
        StorageInfo storageInfo = mAppSearchImpl.getStorageInfoForPackage("package1");
        assertThat(storageInfo.getSizeBytes()).isEqualTo(0);
        assertThat(storageInfo.getAliveDocumentsCount()).isEqualTo(0);
        assertThat(storageInfo.getAliveNamespacesCount()).isEqualTo(0);
    }

    @Test
    public void testGetStorageInfoForPackage_proportionalToDocuments() throws Exception {
        List<AppSearchSchema> schemas =
                Collections.singletonList(new AppSearchSchema.Builder("type").build());

        // Insert schema for "package1"
        mAppSearchImpl.setSchema(
                "package1",
                "database",
                schemas,
                /*schemasNotPlatformSurfaceable=*/ Collections.emptyList(),
                /*schemasPackageAccessible=*/ Collections.emptyMap(),
                /*forceOverride=*/ false,
                /*version=*/ 0);

        // Insert document for "package1"
        GenericDocument document =
                new GenericDocument.Builder<>("namespace", "id1", "type").build();
        mAppSearchImpl.putDocument("package1", "database", document, /*logger=*/ null);

        // Insert schema for "package2"
        mAppSearchImpl.setSchema(
                "package2",
                "database",
                schemas,
                /*schemasNotPlatformSurfaceable=*/ Collections.emptyList(),
                /*schemasPackageAccessible=*/ Collections.emptyMap(),
                /*forceOverride=*/ false,
                /*version=*/ 0);

        // Insert two documents for "package2"
        document = new GenericDocument.Builder<>("namespace", "id1", "type").build();
        mAppSearchImpl.putDocument("package2", "database", document, /*logger=*/ null);
        document = new GenericDocument.Builder<>("namespace", "id2", "type").build();
        mAppSearchImpl.putDocument("package2", "database", document, /*logger=*/ null);

        StorageInfo storageInfo = mAppSearchImpl.getStorageInfoForPackage("package1");
        long size1 = storageInfo.getSizeBytes();
        assertThat(size1).isGreaterThan(0);
        assertThat(storageInfo.getAliveDocumentsCount()).isEqualTo(1);
        assertThat(storageInfo.getAliveNamespacesCount()).isEqualTo(1);

        storageInfo = mAppSearchImpl.getStorageInfoForPackage("package2");
        long size2 = storageInfo.getSizeBytes();
        assertThat(size2).isGreaterThan(0);
        assertThat(storageInfo.getAliveDocumentsCount()).isEqualTo(2);
        assertThat(storageInfo.getAliveNamespacesCount()).isEqualTo(1);

        // Size is proportional to number of documents. Since "package2" has twice as many
        // documents as "package1", its size is twice as much too.
        assertThat(size2).isAtLeast(2 * size1);
    }

    @Test
    public void testGetStorageInfoForDatabase_nonexistentPackage() throws Exception {
        // "package2" doesn't exist yet, so it shouldn't have any storage size
        StorageInfo storageInfo =
                mAppSearchImpl.getStorageInfoForDatabase(
                        "nonexistent.package", "nonexistentDatabase");
        assertThat(storageInfo.getSizeBytes()).isEqualTo(0);
        assertThat(storageInfo.getAliveDocumentsCount()).isEqualTo(0);
        assertThat(storageInfo.getAliveNamespacesCount()).isEqualTo(0);
    }

    @Test
    public void testGetStorageInfoForDatabase_nonexistentDatabase() throws Exception {
        // Insert schema for "package1"
        List<AppSearchSchema> schemas =
                Collections.singletonList(new AppSearchSchema.Builder("type").build());
        mAppSearchImpl.setSchema(
                "package1",
                "database",
                schemas,
                /*schemasNotPlatformSurfaceable=*/ Collections.emptyList(),
                /*schemasPackageAccessible=*/ Collections.emptyMap(),
                /*forceOverride=*/ false,
                /*version=*/ 0);

        // "package2" doesn't exist yet, so it shouldn't have any storage size
        StorageInfo storageInfo =
                mAppSearchImpl.getStorageInfoForDatabase("package1", "nonexistentDatabase");
        assertThat(storageInfo.getSizeBytes()).isEqualTo(0);
        assertThat(storageInfo.getAliveDocumentsCount()).isEqualTo(0);
        assertThat(storageInfo.getAliveNamespacesCount()).isEqualTo(0);
    }

    @Test
    public void testGetStorageInfoForDatabase_withoutDocument() throws Exception {
        // Insert schema for "package1"
        List<AppSearchSchema> schemas =
                Collections.singletonList(new AppSearchSchema.Builder("type").build());
        mAppSearchImpl.setSchema(
                "package1",
                "database1",
                schemas,
                /*schemasNotPlatformSurfaceable=*/ Collections.emptyList(),
                /*schemasPackageAccessible=*/ Collections.emptyMap(),
                /*forceOverride=*/ false,
                /*version=*/ 0);

        // Since "package1", "database1" doesn't have a document, it get any space attributed to it.
        StorageInfo storageInfo = mAppSearchImpl.getStorageInfoForDatabase("package1", "database1");
        assertThat(storageInfo.getSizeBytes()).isEqualTo(0);
        assertThat(storageInfo.getAliveDocumentsCount()).isEqualTo(0);
        assertThat(storageInfo.getAliveNamespacesCount()).isEqualTo(0);
    }

    @Test
    public void testGetStorageInfoForDatabase_proportionalToDocuments() throws Exception {
        // Insert schema for "package1", "database1" and "database2"
        List<AppSearchSchema> schemas =
                Collections.singletonList(new AppSearchSchema.Builder("type").build());
        mAppSearchImpl.setSchema(
                "package1",
                "database1",
                schemas,
                /*schemasNotPlatformSurfaceable=*/ Collections.emptyList(),
                /*schemasPackageAccessible=*/ Collections.emptyMap(),
                /*forceOverride=*/ false,
                /*version=*/ 0);
        mAppSearchImpl.setSchema(
                "package1",
                "database2",
                schemas,
                /*schemasNotPlatformSurfaceable=*/ Collections.emptyList(),
                /*schemasPackageAccessible=*/ Collections.emptyMap(),
                /*forceOverride=*/ false,
                /*version=*/ 0);

        // Add a document for "package1", "database1"
        GenericDocument document =
                new GenericDocument.Builder<>("namespace1", "id1", "type").build();
        mAppSearchImpl.putDocument("package1", "database1", document, /*logger=*/ null);

        // Add two documents for "package1", "database2"
        document = new GenericDocument.Builder<>("namespace1", "id1", "type").build();
        mAppSearchImpl.putDocument("package1", "database2", document, /*logger=*/ null);
        document = new GenericDocument.Builder<>("namespace1", "id2", "type").build();
        mAppSearchImpl.putDocument("package1", "database2", document, /*logger=*/ null);

        StorageInfo storageInfo = mAppSearchImpl.getStorageInfoForDatabase("package1", "database1");
        long size1 = storageInfo.getSizeBytes();
        assertThat(size1).isGreaterThan(0);
        assertThat(storageInfo.getAliveDocumentsCount()).isEqualTo(1);
        assertThat(storageInfo.getAliveNamespacesCount()).isEqualTo(1);

        storageInfo = mAppSearchImpl.getStorageInfoForDatabase("package1", "database2");
        long size2 = storageInfo.getSizeBytes();
        assertThat(size2).isGreaterThan(0);
        assertThat(storageInfo.getAliveDocumentsCount()).isEqualTo(2);
        assertThat(storageInfo.getAliveNamespacesCount()).isEqualTo(1);

        // Size is proportional to number of documents. Since "database2" has twice as many
        // documents as "database1", its size is twice as much too.
        assertThat(size2).isAtLeast(2 * size1);
    }

    @Test
    public void testThrowsExceptionIfClosed() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        AppSearchImpl appSearchImpl =
                AppSearchImpl.create(
                        mTemporaryFolder.newFolder(),
                        context,
                        VisibilityStore.NO_OP_USER_ID,
                        /*globalQuerierPackage=*/ "",
                        /*logger=*/ null);

        // Initial check that we could do something at first.
        List<AppSearchSchema> schemas =
                Collections.singletonList(new AppSearchSchema.Builder("type").build());
        appSearchImpl.setSchema(
                "package",
                "database",
                schemas,
                /*schemasNotPlatformSurfaceable=*/ Collections.emptyList(),
                /*schemasPackageAccessible=*/ Collections.emptyMap(),
                /*forceOverride=*/ false,
                /*version=*/ 0);

        appSearchImpl.close();

        // Check all our public APIs
        expectThrows(
                IllegalStateException.class,
                () -> {
                    appSearchImpl.setSchema(
                            "package",
                            "database",
                            schemas,
                            /*schemasNotPlatformSurfaceable=*/ Collections.emptyList(),
                            /*schemasPackageAccessible=*/ Collections.emptyMap(),
                            /*forceOverride=*/ false,
                            /*version=*/ 0);
                });

        expectThrows(
                IllegalStateException.class,
                () -> {
                    appSearchImpl.getSchema("package", "database");
                });

        expectThrows(
                IllegalStateException.class,
                () -> {
                    appSearchImpl.putDocument(
                            "package",
                            "database",
                            new GenericDocument.Builder<>("namespace", "id", "type").build(),
                            /*logger=*/ null);
                });

        expectThrows(
                IllegalStateException.class,
                () -> {
                    appSearchImpl.getDocument(
                            "package", "database", "namespace", "id", Collections.emptyMap());
                });

        expectThrows(
                IllegalStateException.class,
                () -> {
                    appSearchImpl.query(
                            "package",
                            "database",
                            "query",
                            new SearchSpec.Builder()
                                    .setTermMatch(TermMatchType.Code.PREFIX_VALUE)
                                    .build(),
                            /*logger=*/ null);
                });

        expectThrows(
                IllegalStateException.class,
                () -> {
                    appSearchImpl.globalQuery(
                            "query",
                            new SearchSpec.Builder()
                                    .setTermMatch(TermMatchType.Code.PREFIX_VALUE)
                                    .build(),
                            "package",
                            /*callerUid=*/ 1,
                            /*logger=*/ null);
                });

        expectThrows(
                IllegalStateException.class,
                () -> {
                    appSearchImpl.getNextPage(/*nextPageToken=*/ 1L);
                });

        expectThrows(
                IllegalStateException.class,
                () -> {
                    appSearchImpl.invalidateNextPageToken(/*nextPageToken=*/ 1L);
                });

        expectThrows(
                IllegalStateException.class,
                () -> {
                    appSearchImpl.reportUsage(
                            "package",
                            "database",
                            "namespace",
                            "id",
                            /*usageTimestampMillis=*/ 1000L,
                            /*systemUsage=*/ false);
                });

        expectThrows(
                IllegalStateException.class,
                () -> {
                    appSearchImpl.remove(
                            "package", "database", "namespace", "id", /*statsBuilder=*/ null);
                });

        expectThrows(
                IllegalStateException.class,
                () -> {
                    appSearchImpl.removeByQuery(
                            "package",
                            "database",
                            "query",
                            new SearchSpec.Builder()
                                    .setTermMatch(TermMatchType.Code.PREFIX_VALUE)
                                    .build(),
                            /*statsBuilder=*/ null);
                });

        expectThrows(
                IllegalStateException.class,
                () -> {
                    appSearchImpl.getStorageInfoForPackage("package");
                });

        expectThrows(
                IllegalStateException.class,
                () -> {
                    appSearchImpl.getStorageInfoForDatabase("package", "database");
                });

        expectThrows(
                IllegalStateException.class,
                () -> {
                    appSearchImpl.persistToDisk(PersistType.Code.FULL);
                });
    }

    @Test
    public void testPutPersistsWithLiteFlush() throws Exception {
        // Setup the index
        Context context = ApplicationProvider.getApplicationContext();
        File appsearchDir = mTemporaryFolder.newFolder();
        AppSearchImpl appSearchImpl =
                AppSearchImpl.create(
                        appsearchDir,
                        context,
                        VisibilityStore.NO_OP_USER_ID,
                        /*globalQuerierPackage=*/ "",
                        /*logger=*/ null);

        List<AppSearchSchema> schemas =
                Collections.singletonList(new AppSearchSchema.Builder("type").build());
        appSearchImpl.setSchema(
                "package",
                "database",
                schemas,
                /*schemasNotPlatformSurfaceable=*/ Collections.emptyList(),
                /*schemasPackageAccessible=*/ Collections.emptyMap(),
                /*forceOverride=*/ false,
                /*version=*/ 0);

        // Add a document and persist it.
        GenericDocument document =
                new GenericDocument.Builder<>("namespace1", "id1", "type").build();
        appSearchImpl.putDocument("package", "database", document, /*logger=*/ null);
        appSearchImpl.persistToDisk(PersistType.Code.LITE);

        GenericDocument getResult =
                appSearchImpl.getDocument(
                        "package", "database", "namespace1", "id1", Collections.emptyMap());
        assertThat(getResult).isEqualTo(document);

        // That document should be visible even from another instance.
        AppSearchImpl appSearchImpl2 =
                AppSearchImpl.create(
                        appsearchDir,
                        context,
                        VisibilityStore.NO_OP_USER_ID,
                        /*globalQuerierPackage=*/ "",
                        /*logger=*/ null);
        getResult =
                appSearchImpl2.getDocument(
                        "package", "database", "namespace1", "id1", Collections.emptyMap());
        assertThat(getResult).isEqualTo(document);
    }

    @Test
    public void testDeletePersistsWithLiteFlush() throws Exception {
        // Setup the index
        Context context = ApplicationProvider.getApplicationContext();
        File appsearchDir = mTemporaryFolder.newFolder();
        AppSearchImpl appSearchImpl =
                AppSearchImpl.create(
                        appsearchDir,
                        context,
                        VisibilityStore.NO_OP_USER_ID,
                        /*globalQuerierPackage=*/ "",
                        /*logger=*/ null);

        List<AppSearchSchema> schemas =
                Collections.singletonList(new AppSearchSchema.Builder("type").build());
        appSearchImpl.setSchema(
                "package",
                "database",
                schemas,
                /*schemasNotPlatformSurfaceable=*/ Collections.emptyList(),
                /*schemasPackageAccessible=*/ Collections.emptyMap(),
                /*forceOverride=*/ false,
                /*version=*/ 0);

        // Add two documents and persist them.
        GenericDocument document1 =
                new GenericDocument.Builder<>("namespace1", "id1", "type").build();
        appSearchImpl.putDocument("package", "database", document1, /*logger=*/ null);
        GenericDocument document2 =
                new GenericDocument.Builder<>("namespace1", "id2", "type").build();
        appSearchImpl.putDocument("package", "database", document2, /*logger=*/ null);
        appSearchImpl.persistToDisk(PersistType.Code.LITE);

        GenericDocument getResult =
                appSearchImpl.getDocument(
                        "package", "database", "namespace1", "id1", Collections.emptyMap());
        assertThat(getResult).isEqualTo(document1);
        getResult =
                appSearchImpl.getDocument(
                        "package", "database", "namespace1", "id2", Collections.emptyMap());
        assertThat(getResult).isEqualTo(document2);

        // Delete the first document
        appSearchImpl.remove("package", "database", "namespace1", "id1", /*statsBuilder=*/ null);
        appSearchImpl.persistToDisk(PersistType.Code.LITE);
        expectThrows(
                AppSearchException.class,
                () ->
                        appSearchImpl.getDocument(
                                "package",
                                "database",
                                "namespace1",
                                "id1",
                                Collections.emptyMap()));
        getResult =
                appSearchImpl.getDocument(
                        "package", "database", "namespace1", "id2", Collections.emptyMap());
        assertThat(getResult).isEqualTo(document2);

        // Only the second document should be retrievable from another instance.
        AppSearchImpl appSearchImpl2 =
                AppSearchImpl.create(
                        appsearchDir,
                        context,
                        VisibilityStore.NO_OP_USER_ID,
                        /*globalQuerierPackage=*/ "",
                        /*logger=*/ null);
        expectThrows(
                AppSearchException.class,
                () ->
                        appSearchImpl2.getDocument(
                                "package",
                                "database",
                                "namespace1",
                                "id1",
                                Collections.emptyMap()));
        getResult =
                appSearchImpl2.getDocument(
                        "package", "database", "namespace1", "id2", Collections.emptyMap());
        assertThat(getResult).isEqualTo(document2);
    }

    @Test
    public void testDeleteByQueryPersistsWithLiteFlush() throws Exception {
        // Setup the index
        Context context = ApplicationProvider.getApplicationContext();
        File appsearchDir = mTemporaryFolder.newFolder();
        AppSearchImpl appSearchImpl =
                AppSearchImpl.create(
                        appsearchDir,
                        context,
                        VisibilityStore.NO_OP_USER_ID,
                        /*globalQuerierPackage=*/ "",
                        /*logger=*/ null);

        List<AppSearchSchema> schemas =
                Collections.singletonList(new AppSearchSchema.Builder("type").build());
        appSearchImpl.setSchema(
                "package",
                "database",
                schemas,
                /*schemasNotPlatformSurfaceable=*/ Collections.emptyList(),
                /*schemasPackageAccessible=*/ Collections.emptyMap(),
                /*forceOverride=*/ false,
                /*version=*/ 0);

        // Add two documents and persist them.
        GenericDocument document1 =
                new GenericDocument.Builder<>("namespace1", "id1", "type").build();
        appSearchImpl.putDocument("package", "database", document1, /*logger=*/ null);
        GenericDocument document2 =
                new GenericDocument.Builder<>("namespace2", "id2", "type").build();
        appSearchImpl.putDocument("package", "database", document2, /*logger=*/ null);
        appSearchImpl.persistToDisk(PersistType.Code.LITE);

        GenericDocument getResult =
                appSearchImpl.getDocument(
                        "package", "database", "namespace1", "id1", Collections.emptyMap());
        assertThat(getResult).isEqualTo(document1);
        getResult =
                appSearchImpl.getDocument(
                        "package", "database", "namespace2", "id2", Collections.emptyMap());
        assertThat(getResult).isEqualTo(document2);

        // Delete the first document
        appSearchImpl.removeByQuery(
                "package",
                "database",
                "",
                new SearchSpec.Builder()
                        .addFilterNamespaces("namespace1")
                        .setTermMatch(SearchSpec.TERM_MATCH_EXACT_ONLY)
                        .build(),
                /*statsBuilder=*/ null);
        appSearchImpl.persistToDisk(PersistType.Code.LITE);
        expectThrows(
                AppSearchException.class,
                () ->
                        appSearchImpl.getDocument(
                                "package",
                                "database",
                                "namespace1",
                                "id1",
                                Collections.emptyMap()));
        getResult =
                appSearchImpl.getDocument(
                        "package", "database", "namespace2", "id2", Collections.emptyMap());
        assertThat(getResult).isEqualTo(document2);

        // Only the second document should be retrievable from another instance.
        AppSearchImpl appSearchImpl2 =
                AppSearchImpl.create(
                        appsearchDir,
                        context,
                        VisibilityStore.NO_OP_USER_ID,
                        /*globalQuerierPackage=*/ "",
                        /*logger=*/ null);
        expectThrows(
                AppSearchException.class,
                () ->
                        appSearchImpl2.getDocument(
                                "package",
                                "database",
                                "namespace1",
                                "id1",
                                Collections.emptyMap()));
        getResult =
                appSearchImpl2.getDocument(
                        "package", "database", "namespace2", "id2", Collections.emptyMap());
        assertThat(getResult).isEqualTo(document2);
    }
}
