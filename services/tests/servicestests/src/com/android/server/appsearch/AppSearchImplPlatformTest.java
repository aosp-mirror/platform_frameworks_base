/*
 * Copyright (C) 2021 The Android Open Source Project
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

// TODO(b/169883602): This is purposely a different package from the path so that it can access
// AppSearchImpl's methods without having to make them public. This should be replaced by proper
// global query integration tests that can test AppSearchImpl-VisibilityStore integration logic.
package com.android.server.appsearch.external.localstorage;

import static com.google.common.truth.Truth.assertThat;

import android.app.appsearch.AppSearchSchema;
import android.app.appsearch.GenericDocument;
import android.app.appsearch.PackageIdentifier;
import android.app.appsearch.SearchResultPage;
import android.app.appsearch.SearchSpec;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.pm.PackageManager;

import androidx.test.core.app.ApplicationProvider;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.util.Collections;
import java.util.List;

/** This tests AppSearchImpl when it's running with a platform-backed VisibilityStore. */
public class AppSearchImplPlatformTest {
    @Rule public TemporaryFolder mTemporaryFolder = new TemporaryFolder();
    private MockPackageManager mMockPackageManager = new MockPackageManager();
    private Context mContext;
    private AppSearchImpl mAppSearchImpl;
    private int mGlobalQuerierUid;

    @Before
    public void setUp() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        mContext =
                new ContextWrapper(context) {
                    @Override
                    public PackageManager getPackageManager() {
                        return mMockPackageManager.getMockPackageManager();
                    }
                };

        // Give ourselves global query permissions
        mAppSearchImpl =
                AppSearchImpl.create(
                        mTemporaryFolder.newFolder(),
                        mContext,
                        mContext.getUserId(),
                        mContext.getPackageName());
        mGlobalQuerierUid =
                mContext.getPackageManager().getPackageUid(mContext.getPackageName(), /*flags=*/ 0);
    }
    /**
     * TODO(b/169883602): This should be an integration test at the cts-level. This is a short-term
     * test until we have official support for multiple-apps indexing at once.
     */
    @Test
    public void testGlobalQueryWithMultiplePackages_noPackageFilters() throws Exception {
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
                /*schemaVersion=*/ 0);

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
                /*schemaVersion=*/ 0);

        // Insert package1 document
        GenericDocument document1 =
                new GenericDocument.Builder<>("uri", "schema1").setNamespace("namespace").build();
        mAppSearchImpl.putDocument("package1", "database1", document1, /*logger=*/ null);

        // Insert package2 document
        GenericDocument document2 =
                new GenericDocument.Builder<>("uri", "schema2").setNamespace("namespace").build();
        mAppSearchImpl.putDocument("package2", "database2", document2, /*logger=*/ null);

        // No query filters specified, global query can retrieve all documents.
        SearchSpec searchSpec =
                new SearchSpec.Builder().setTermMatch(SearchSpec.TERM_MATCH_EXACT_ONLY).build();
        SearchResultPage searchResultPage =
                mAppSearchImpl.globalQuery(
                        "", searchSpec, mContext.getPackageName(), mGlobalQuerierUid);
        assertThat(searchResultPage.getResults()).hasSize(2);

        // Document2 will be first since it got indexed later and has a "better", aka more recent
        // score.
        assertThat(searchResultPage.getResults().get(0).getDocument()).isEqualTo(document2);
        assertThat(searchResultPage.getResults().get(1).getDocument()).isEqualTo(document1);
    }

    /**
     * TODO(b/169883602): This should be an integration test at the cts-level. This is a short-term
     * test until we have official support for multiple-apps indexing at once.
     */
    @Test
    public void testGlobalQueryWithMultiplePackages_withPackageFilters() throws Exception {
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
                /*schemaVersion=*/ 0);

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
                /*schemaVersion=*/ 0);

        // Insert package1 document
        GenericDocument document1 =
                new GenericDocument.Builder<>("uri", "schema1").setNamespace("namespace").build();
        mAppSearchImpl.putDocument("package1", "database1", document1, /*logger=*/ null);

        // Insert package2 document
        GenericDocument document2 =
                new GenericDocument.Builder<>("uri", "schema2").setNamespace("namespace").build();
        mAppSearchImpl.putDocument("package2", "database2", document2, /*logger=*/ null);

        // "package1" filter specified
        SearchSpec searchSpec =
                new SearchSpec.Builder()
                        .setTermMatch(SearchSpec.TERM_MATCH_PREFIX)
                        .addFilterPackageNames("package1")
                        .build();
        SearchResultPage searchResultPage =
                mAppSearchImpl.globalQuery(
                        "", searchSpec, mContext.getPackageName(), mGlobalQuerierUid);
        assertThat(searchResultPage.getResults()).hasSize(1);
        assertThat(searchResultPage.getResults().get(0).getDocument()).isEqualTo(document1);

        // "package2" filter specified
        searchSpec =
                new SearchSpec.Builder()
                        .setTermMatch(SearchSpec.TERM_MATCH_PREFIX)
                        .addFilterPackageNames("package2")
                        .build();
        searchResultPage =
                mAppSearchImpl.globalQuery(
                        "", searchSpec, mContext.getPackageName(), mGlobalQuerierUid);
        assertThat(searchResultPage.getResults()).hasSize(1);
        assertThat(searchResultPage.getResults().get(0).getDocument()).isEqualTo(document2);
    }

    @Test
    public void testSetSchema_existingSchemaRetainsVisibilitySetting() throws Exception {
        // Values for a "foo" client
        String packageNameFoo = "packageFoo";
        byte[] sha256CertFoo = new byte[] {10};
        int uidFoo = 1;

        // Make sure foo package will pass package manager checks.
        mMockPackageManager.mockGetPackageUidAsUser(packageNameFoo, mContext.getUserId(), uidFoo);
        mMockPackageManager.mockAddSigningCertificate(packageNameFoo, sha256CertFoo);

        // Set schema1
        String prefix = AppSearchImpl.createPrefix("package", "database");
        mAppSearchImpl.setSchema(
                "package",
                "database",
                Collections.singletonList(new AppSearchSchema.Builder("schema1").build()),
                /*schemasNotPlatformSurfaceable=*/ Collections.singletonList("schema1"),
                /*schemasPackageAccessible=*/ ImmutableMap.of(
                        "schema1",
                        ImmutableList.of(new PackageIdentifier(packageNameFoo, sha256CertFoo))),
                /*forceOverride=*/ false,
                /*schemaVersion=*/ 0);

        // "schema1" is platform hidden now and package visible to package1
        assertThat(
                        mAppSearchImpl
                                .getVisibilityStoreLocked()
                                .isSchemaSearchableByCaller(
                                        prefix, prefix + "schema1", mGlobalQuerierUid))
                .isFalse();
        assertThat(
                        mAppSearchImpl
                                .getVisibilityStoreLocked()
                                .isSchemaSearchableByCaller(prefix, prefix + "schema1", uidFoo))
                .isTrue();

        // Add a new schema, and include the already-existing "schema1"
        mAppSearchImpl.setSchema(
                "package",
                "database",
                ImmutableList.of(
                        new AppSearchSchema.Builder("schema1").build(),
                        new AppSearchSchema.Builder("schema2").build()),
                /*schemasNotPlatformSurfaceable=*/ Collections.singletonList("schema1"),
                /*schemasPackageAccessible=*/ ImmutableMap.of(
                        "schema1",
                        ImmutableList.of(new PackageIdentifier(packageNameFoo, sha256CertFoo))),
                /*forceOverride=*/ false,
                /*schemaVersion=*/ 0);

        // Check that "schema1" still has the same visibility settings
        assertThat(
                        mAppSearchImpl
                                .getVisibilityStoreLocked()
                                .isSchemaSearchableByCaller(
                                        prefix, prefix + "schema1", mGlobalQuerierUid))
                .isFalse();
        assertThat(
                        mAppSearchImpl
                                .getVisibilityStoreLocked()
                                .isSchemaSearchableByCaller(prefix, prefix + "schema1", uidFoo))
                .isTrue();

        // "schema2" has default visibility settings
        assertThat(
                        mAppSearchImpl
                                .getVisibilityStoreLocked()
                                .isSchemaSearchableByCaller(
                                        prefix, prefix + "schema2", mGlobalQuerierUid))
                .isTrue();
        assertThat(
                        mAppSearchImpl
                                .getVisibilityStoreLocked()
                                .isSchemaSearchableByCaller(prefix, prefix + "schema2", uidFoo))
                .isFalse();
    }

    @Test
    public void testRemoveSchema_removedFromVisibilityStore() throws Exception {
        // Values for a "foo" client
        String packageNameFoo = "packageFoo";
        byte[] sha256CertFoo = new byte[] {10};
        int uidFoo = 1;

        // Make sure foo package will pass package manager checks.
        mMockPackageManager.mockGetPackageUidAsUser(packageNameFoo, mContext.getUserId(), uidFoo);
        mMockPackageManager.mockAddSigningCertificate(packageNameFoo, sha256CertFoo);

        String prefix = AppSearchImpl.createPrefix("package", "database");
        mAppSearchImpl.setSchema(
                "package",
                "database",
                Collections.singletonList(new AppSearchSchema.Builder("schema1").build()),
                /*schemasNotPlatformSurfaceable=*/ Collections.singletonList("schema1"),
                /*schemasPackageAccessible=*/ ImmutableMap.of(
                        "schema1",
                        ImmutableList.of(new PackageIdentifier(packageNameFoo, sha256CertFoo))),
                /*forceOverride=*/ false,
                /*schemaVersion=*/ 0);

        // "schema1" is platform hidden now and package accessible
        assertThat(
                        mAppSearchImpl
                                .getVisibilityStoreLocked()
                                .isSchemaSearchableByCaller(
                                        prefix, prefix + "schema1", mGlobalQuerierUid))
                .isFalse();
        assertThat(
                        mAppSearchImpl
                                .getVisibilityStoreLocked()
                                .isSchemaSearchableByCaller(prefix, prefix + "schema1", uidFoo))
                .isTrue();

        // Remove "schema1" by force overriding
        mAppSearchImpl.setSchema(
                "package",
                "database",
                /*schemas=*/ Collections.emptyList(),
                /*schemasNotPlatformSurfaceable=*/ Collections.emptyList(),
                /*schemasPackageAccessible=*/ Collections.emptyMap(),
                /*forceOverride=*/ true,
                /*schemaVersion=*/ 0);

        // Check that "schema1" is no longer considered platform hidden or package accessible
        assertThat(
                        mAppSearchImpl
                                .getVisibilityStoreLocked()
                                .isSchemaSearchableByCaller(
                                        prefix, prefix + "schema1", mGlobalQuerierUid))
                .isTrue();
        assertThat(
                        mAppSearchImpl
                                .getVisibilityStoreLocked()
                                .isSchemaSearchableByCaller(prefix, prefix + "schema1", uidFoo))
                .isFalse();

        // Add "schema1" back, it gets default visibility settings which means it's not platform
        // hidden and not package accessible
        mAppSearchImpl.setSchema(
                "package",
                "database",
                Collections.singletonList(new AppSearchSchema.Builder("schema1").build()),
                /*schemasNotPlatformSurfaceable=*/ Collections.emptyList(),
                /*schemasPackageAccessible=*/ Collections.emptyMap(),
                /*forceOverride=*/ false,
                /*schemaVersion=*/ 0);
        assertThat(
                        mAppSearchImpl
                                .getVisibilityStoreLocked()
                                .isSchemaSearchableByCaller(
                                        prefix, prefix + "schema1", mGlobalQuerierUid))
                .isTrue();
        assertThat(
                        mAppSearchImpl
                                .getVisibilityStoreLocked()
                                .isSchemaSearchableByCaller(prefix, prefix + "schema1", uidFoo))
                .isFalse();
    }

    @Test
    public void testSetSchema_defaultPlatformVisible() throws Exception {
        String prefix = AppSearchImpl.createPrefix("package", "database");
        mAppSearchImpl.setSchema(
                "package",
                "database",
                Collections.singletonList(new AppSearchSchema.Builder("Schema").build()),
                /*schemasNotPlatformSurfaceable=*/ Collections.emptyList(),
                /*schemasPackageAccessible=*/ Collections.emptyMap(),
                /*forceOverride=*/ false,
                /*schemaVersion=*/ 0);
        assertThat(
                        mAppSearchImpl
                                .getVisibilityStoreLocked()
                                .isSchemaSearchableByCaller(
                                        prefix, prefix + "Schema", mGlobalQuerierUid))
                .isTrue();
    }

    @Test
    public void testSetSchema_platformHidden() throws Exception {
        String prefix = AppSearchImpl.createPrefix("package", "database");
        mAppSearchImpl.setSchema(
                "package",
                "database",
                Collections.singletonList(new AppSearchSchema.Builder("Schema").build()),
                /*schemasNotPlatformSurfaceable=*/ Collections.singletonList("Schema"),
                /*schemasPackageAccessible=*/ Collections.emptyMap(),
                /*forceOverride=*/ false,
                /*schemaVersion=*/ 0);
        assertThat(
                        mAppSearchImpl
                                .getVisibilityStoreLocked()
                                .isSchemaSearchableByCaller(
                                        prefix, prefix + "Schema", mGlobalQuerierUid))
                .isFalse();
    }

    @Test
    public void testSetSchema_defaultNotPackageAccessible() throws Exception {
        String prefix = AppSearchImpl.createPrefix("package", "database");
        mAppSearchImpl.setSchema(
                "package",
                "database",
                Collections.singletonList(new AppSearchSchema.Builder("Schema").build()),
                /*schemasNotPlatformSurfaceable=*/ Collections.emptyList(),
                /*schemasPackageAccessible=*/ Collections.emptyMap(),
                /*forceOverride=*/ false,
                /*schemaVersion=*/ 0);
        assertThat(
                        mAppSearchImpl
                                .getVisibilityStoreLocked()
                                .isSchemaSearchableByCaller(
                                        prefix, prefix + "Schema", /*callerUid=*/ 42))
                .isFalse();
    }

    @Test
    public void testSetSchema_packageAccessible() throws Exception {
        // Values for a "foo" client
        String packageNameFoo = "packageFoo";
        byte[] sha256CertFoo = new byte[] {10};
        int uidFoo = 1;

        // Make sure foo package will pass package manager checks.
        mMockPackageManager.mockGetPackageUidAsUser(packageNameFoo, mContext.getUserId(), uidFoo);
        mMockPackageManager.mockAddSigningCertificate(packageNameFoo, sha256CertFoo);

        String prefix = AppSearchImpl.createPrefix("package", "database");
        mAppSearchImpl.setSchema(
                "package",
                "database",
                Collections.singletonList(new AppSearchSchema.Builder("Schema").build()),
                /*schemasNotPlatformSurfaceable=*/ Collections.emptyList(),
                /*schemasPackageAccessible=*/ ImmutableMap.of(
                        "Schema",
                        ImmutableList.of(new PackageIdentifier(packageNameFoo, sha256CertFoo))),
                /*forceOverride=*/ false,
                /*schemaVersion=*/ 0);
        assertThat(
                        mAppSearchImpl
                                .getVisibilityStoreLocked()
                                .isSchemaSearchableByCaller(prefix, prefix + "Schema", uidFoo))
                .isTrue();
    }
}
